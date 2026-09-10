package com.valerochka1337.valerochkagym.data.health

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.backend.BackendResponse
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.db.entity.HealthLogicalRecordEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRecordVersionEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncStateEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.domain.HealthConsentSnapshot
import com.valerochka1337.valerochkagym.domain.HealthConsentStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthLedgerSyncTest : RoomDaoTest() {
  private class Store : BackendSessionStore {
    override val session = MutableStateFlow<BackendTokens?>(null)
    private var valueEpoch = 0L
    override val sessionEpoch
      get() = valueEpoch

    override fun save(tokens: BackendTokens?) {
      valueEpoch++
      session.value = tokens
    }
  }

  private class Consent(enabled: Boolean) : HealthConsentStore {
    private val state = MutableStateFlow(HealthConsentSnapshot(1, enabled, false))

    override fun observe() = state

    override suspend fun acknowledgeCurrentStorageNotice() = Unit

    override suspend fun setBackendSyncEnabled(enabled: Boolean) {
      state.value = state.value.copy(backendSyncEnabled = enabled)
    }
  }

  private class Transport(private val store: Store, private val pages: ArrayDeque<JsonElement>) :
      BackendTransport {
    var beforeResponse: (suspend () -> Unit)? = null
    override val json = Json

    override suspend fun public(method: String, path: String, body: JsonElement?) = error("unused")

    override suspend fun authorized(method: String, path: String, body: JsonElement?) =
        error("unused")

    override suspend fun authorizedResponse(
        method: String,
        path: String,
        body: JsonElement?,
        headers: Map<String, String>,
        retryOnUnauthorized: Boolean,
    ): BackendResponse {
      beforeResponse?.let { action ->
        beforeResponse = null
        action()
      }
      val response = pages.removeFirst()
      return BackendResponse(
          response,
          response.toString().encodeToByteArray(),
          setOf(HealthLedgerSync.CAPABILITY),
          requireNotNull(store.session.value).userId,
          store.sessionEpoch,
      )
    }

    override suspend fun authorizedRawResponse(
        method: String,
        path: String,
        rawBody: ByteArray,
        headers: Map<String, String>,
        expectedOwner: String?,
        expectedSessionEpoch: Long?,
        retryOnUnauthorized: Boolean,
        maxResponseBytes: Int?,
    ): BackendResponse {
      beforeResponse?.let { action ->
        beforeResponse = null
        action()
      }
      val response = pages.removeFirst()
      return BackendResponse(
          response,
          response.toString().encodeToByteArray(),
          setOf(HealthLedgerSync.CAPABILITY),
          requireNotNull(store.session.value).userId,
          store.sessionEpoch,
      )
    }
  }

  private class RawTransport(
      private val store: Store,
      private val replies: ArrayDeque<JsonElement?>,
      private val pages: ArrayDeque<JsonElement> = ArrayDeque(),
  ) : BackendTransport {
    override val json = Json
    val rawPosts = mutableListOf<ByteArray>()
    val getPaths = mutableListOf<String>()

    override suspend fun public(method: String, path: String, body: JsonElement?) = error("unused")

    override suspend fun authorized(method: String, path: String, body: JsonElement?) =
        error("unused")

    override suspend fun authorizedRawResponse(
        method: String,
        path: String,
        rawBody: ByteArray,
        headers: Map<String, String>,
        expectedOwner: String?,
        expectedSessionEpoch: Long?,
        retryOnUnauthorized: Boolean,
        maxResponseBytes: Int?,
    ): BackendResponse {
      if (method == "GET") {
        getPaths += path
        val page = pages.removeFirst()
        return BackendResponse(
            page,
            page.toString().encodeToByteArray(),
            setOf(HealthLedgerSync.CAPABILITY),
            requireNotNull(store.session.value).userId,
            store.sessionEpoch,
        )
      }
      rawPosts += rawBody
      val reply = replies.removeFirst() ?: throw java.io.IOException("lost acknowledgement")
      return BackendResponse(
          reply,
          reply.toString().encodeToByteArray(),
          setOf(HealthLedgerSync.CAPABILITY),
          requireNotNull(store.session.value).userId,
          store.sessionEpoch,
      )
    }
  }

  @Test
  fun `partial frozen page stays invisible until final page`() = runTest {
    val store = Store()
    val owner = "owner"
    store.save(BackendTokens(owner, "o@x", "a", "22222222-2222-4222-8222-222222222222"))
    val page =
        page(
            next = "next",
            cursor = null,
            versions =
                listOf(
                    version(
                        "11111111-1111-4111-8111-111111111111",
                        "22222222-2222-4222-8222-222222222222",
                        1,
                    )
                ),
        )
    val transport = Transport(store, ArrayDeque(listOf(page)))
    val sync = BackendSync(db, transport, store)
    claimHealth(sync, owner)
    val ledger =
        HealthLedgerSync(
            db,
            db.healthDao(),
            db.healthSyncDao(),
            transport,
            store,
            sync,
            Consent(true),
        )
    assertFalse(ledger.replayPending())
    assertEquals(0, tableCount("health_logical_records"))
    assertEquals(1, tableCount("health_sync_staging"))
  }

  @Test
  fun `missing consent preserves a literal pending operation without dispatch`() = runTest {
    val store = Store()
    val owner = "owner"
    store.save(BackendTokens(owner, "o@x", "a", "22222222-2222-4222-8222-222222222222"))
    val transport = RawTransport(store, ArrayDeque())
    val sync = BackendSync(db, transport, store)
    claimHealth(sync, owner)
    val bytes =
        " { \"operationId\" : \"op\", \"versions\" : [], \"heads\" : [{\"logicalId\":\"r\"}] } "
            .encodeToByteArray()
    db.healthSyncDao()
        .upsertOutbox(
            com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity(
                "33333333-3333-4333-8333-333333333333",
                owner,
                bytes,
                "h",
            )
        )
    val ledger =
        HealthLedgerSync(
            db,
            db.healthDao(),
            db.healthSyncDao(),
            transport,
            store,
            sync,
            Consent(false),
        )
    assertFalse(ledger.replayPending())
    assertArrayEquals(bytes, requireNotNull(db.healthSyncDao().outbox(owner)).requestBytes)
    assertFalse(requireNotNull(db.healthSyncDao().outbox(owner)).dispatched)
    assertTrue(transport.rawPosts.isEmpty())
  }

  @Test
  fun `head event on final frozen page applies with its version only after cursor commit`() =
      runTest {
        val store = Store()
        val owner = "owner"
        store.save(BackendTokens(owner, "o@x", "a", "22222222-2222-4222-8222-222222222222"))
        val first =
            page(
                next = "next",
                cursor = null,
                versions =
                    listOf(
                        version(
                            "11111111-1111-4111-8111-111111111111",
                            "22222222-2222-4222-8222-222222222222",
                            1,
                        )
                    ),
            )
        val final = buildJsonObject {
          put("versions", JsonArray(emptyList()))
          put(
              "heads",
              JsonArray(
                  listOf(
                      head(
                          "22222222-2222-4222-8222-222222222222",
                          "11111111-1111-4111-8111-111111111111",
                          1,
                          2,
                      )
                  )
              ),
          )
          put("nextPageToken", JsonNull)
          put("commitCursor", "cursor-2")
        }
        val transport = Transport(store, ArrayDeque(listOf(first, final)))
        val sync = BackendSync(db, transport, store)
        claimHealth(sync, owner)
        val ledger =
            HealthLedgerSync(
                db,
                db.healthDao(),
                db.healthSyncDao(),
                transport,
                store,
                sync,
                Consent(true),
            )
        assertTrue(ledger.replayPending())
        assertEquals(
            "11111111-1111-4111-8111-111111111111",
            db.healthDao().record("22222222-2222-4222-8222-222222222222", owner)?.currentVersionId,
        )
        assertEquals(
            1,
            db.healthDao().observeHeadHistory("22222222-2222-4222-8222-222222222222").first().size,
        )
        assertEquals("cursor-2", db.healthSyncDao().state(owner)?.cursor)
      }

  @Test
  fun `interleaved version and head revisions apply in revision order`() = runTest {
    val store = Store()
    val owner = "owner"
    store.save(BackendTokens(owner, "o@x", "a", "22222222-2222-4222-8222-222222222222"))
    val first = "11111111-1111-4111-8111-111111111111"
    val second = "44444444-4444-4444-8444-444444444444"
    val logical = "22222222-2222-4222-8222-222222222222"
    val transport =
        Transport(
            store,
            ArrayDeque(
                listOf(
                    pageWithHeads(
                        versions =
                            listOf(version(first, logical, 1), version(second, logical, 3, first)),
                        heads = listOf(head(logical, first, 1, 2)),
                        next = null,
                        cursor = "cursor-3",
                    )
                )
            ),
        )
    val sync = BackendSync(db, transport, store)
    claimHealth(sync, owner)
    val ledger =
        HealthLedgerSync(
            db,
            db.healthDao(),
            db.healthSyncDao(),
            transport,
            store,
            sync,
            Consent(true),
        )

    assertTrue(ledger.replayPending())
    assertEquals(2, db.healthSyncDao().baseline(owner).size)
    assertEquals(first, db.healthDao().record(logical, owner)?.currentVersionId)
    assertEquals("cursor-3", db.healthSyncDao().state(owner)?.cursor)
  }

  @Test
  fun `head only incremental page retains acknowledged version baseline`() = runTest {
    val store = Store()
    val owner = "owner"
    val logical = "22222222-2222-4222-8222-222222222222"
    val versionId = "11111111-1111-4111-8111-111111111111"
    store.save(BackendTokens(owner, "o@x", "a", "22222222-2222-4222-8222-222222222222"))
    val transport =
        Transport(
            store,
            ArrayDeque(
                listOf(
                    pageWithHeads(emptyList(), listOf(head(logical, versionId, 1, 2)), null, "c2")
                )
            ),
        )
    val sync = BackendSync(db, transport, store)
    claimHealth(sync, owner)
    db.healthDao()
        .upsertRecord(
            HealthLogicalRecordEntity(logical, owner, "RESTRICTION", 1, versionId, 0, false, 1)
        )
    db.healthDao()
        .insertVersion(
            HealthRecordVersionEntity(
                versionId,
                logical,
                null,
                "RESTRICTION",
                "CONFIRMED",
                1,
                "{\"textOriginal\":\"x\",\"confirmedAtEpochMs\":1}",
                1,
                1,
            )
        )
    db.healthSyncDao()
        .upsertBaseline(
            com.valerochka1337.valerochkagym.data.db.entity.HealthSyncBaselineEntity(
                owner,
                versionId,
                "baseline",
            )
        )
    db.healthSyncDao().upsertState(HealthSyncStateEntity(owner, "c1", needsFullRefresh = false))
    val ledger =
        HealthLedgerSync(
            db,
            db.healthDao(),
            db.healthSyncDao(),
            transport,
            store,
            sync,
            Consent(true),
        )

    assertTrue(ledger.replayPending())
    assertEquals(listOf(versionId), db.healthSyncDao().baseline(owner).map { it.versionId })
  }

  @Test
  fun `malformed head rolls back every version from the frozen final transaction`() = runTest {
    val store = Store()
    val owner = "owner"
    val logical = "22222222-2222-4222-8222-222222222222"
    store.save(BackendTokens(owner, "o@x", "a", logical))
    val transport =
        Transport(
            store,
            ArrayDeque(
                listOf(
                    pageWithHeads(
                        listOf(version("11111111-1111-4111-8111-111111111111", logical, 1)),
                        listOf(head(logical, "11111111-1111-4111-8111-111111111111", 1, 2)),
                        null,
                        "broken",
                    )
                )
            ),
        )
    val sync = BackendSync(db, transport, store)
    claimHealth(sync, owner)
    val old = "44444444-4444-4444-8444-444444444444"
    db.healthDao()
        .upsertRecord(
            HealthLogicalRecordEntity(logical, owner, "RESTRICTION", 1, old, 1, false, 10)
        )
    db.healthDao()
        .insertVersion(
            HealthRecordVersionEntity(
                old,
                logical,
                null,
                "RESTRICTION",
                "CONFIRMED",
                1,
                version(old, logical, 1)["payload"].toString(),
                1,
                9,
            )
        )
    db.healthDao()
        .insertHeadHistory(
            com.valerochka1337.valerochkagym.data.db.entity.HealthHeadHistoryEntity(
                logical,
                1,
                old,
                "RESTRICTION",
                false,
                10,
            )
        )
    val ledger =
        HealthLedgerSync(
            db,
            db.healthDao(),
            db.healthSyncDao(),
            transport,
            store,
            sync,
            Consent(true),
        )

    assertFalse(ledger.replayPending())
    assertEquals(1, tableCount("health_logical_records"))
    assertEquals(null, db.healthDao().version("11111111-1111-4111-8111-111111111111"))
    assertEquals(old, db.healthDao().record(logical, owner)?.currentVersionId)
    assertEquals(0, db.healthSyncDao().baseline(owner).size)
    assertEquals(null, db.healthSyncDao().state(owner)?.cursor)
  }

  @Test
  fun `active workout beginning during GET keeps frozen health page invisible`() = runTest {
    val store = Store()
    val owner = "owner"
    store.save(BackendTokens(owner, "o@x", "a", "22222222-2222-4222-8222-222222222222"))
    val transport =
        Transport(
            store,
            ArrayDeque(
                listOf(
                    page(
                        null,
                        "cursor",
                        listOf(
                            version(
                                "11111111-1111-4111-8111-111111111111",
                                "22222222-2222-4222-8222-222222222222",
                                1,
                            )
                        ),
                    )
                )
            ),
        )
    transport.beforeResponse = {
      db.workoutDao()
          .insertWorkout(
              WorkoutEntity(
                  "active",
                  name = "Активная",
                  startedAt = 1,
                  uploadStatus = UploadStatus.PENDING,
              )
          )
    }
    val sync = BackendSync(db, transport, store)
    claimHealth(sync, owner)
    val ledger =
        HealthLedgerSync(
            db,
            db.healthDao(),
            db.healthSyncDao(),
            transport,
            store,
            sync,
            Consent(true),
        )

    assertFalse(ledger.replayPending())
    assertEquals(0, tableCount("health_logical_records"))
    assertEquals(0, tableCount("health_sync_staging"))
  }

  @Test
  fun `lost acknowledgement retries the same literal health operation bytes`() = runTest {
    val store = Store()
    val owner = "owner"
    store.save(BackendTokens(owner, "o@x", "a", "22222222-2222-4222-8222-222222222222"))
    val result = buildJsonObject {
      put("operationId", "33333333-3333-4333-8333-333333333333")
      put(
          "versionReceipts",
          JsonArray(
              listOf(
                  buildJsonObject {
                    put("versionId", "11111111-1111-4111-8111-111111111111")
                    put("serverSequence", 1)
                    put("healthRevision", 1)
                  }
              )
          ),
      )
      put(
          "headResults",
          JsonArray(
              listOf(
                  buildJsonObject {
                    put("logicalId", "22222222-2222-4222-8222-222222222222")
                    put("submittedCurrentVersionId", "11111111-1111-4111-8111-111111111111")
                    put("serverCurrentVersionId", "11111111-1111-4111-8111-111111111111")
                    put("headRevision", 1)
                    put("healthRevision", 2)
                    put("outcome", "APPLIED")
                  }
              )
          ),
      )
    }
    val transport =
        RawTransport(
            store,
            ArrayDeque(listOf(null, result)),
            ArrayDeque(
                listOf(page(null, "full-1", emptyList()), page(null, "full-2", emptyList()))
            ),
        )
    val sync = BackendSync(db, transport, store)
    claimHealth(sync, owner)
    db.healthDao()
        .upsertRecord(
            HealthLogicalRecordEntity(
                "22222222-2222-4222-8222-222222222222",
                owner,
                "RESTRICTION",
                1,
                "11111111-1111-4111-8111-111111111111",
                1,
                false,
                null,
            )
        )
    db.healthDao()
        .insertVersion(
            HealthRecordVersionEntity(
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
                null,
                "RESTRICTION",
                "CONFIRMED",
                1,
                "{\"textOriginal\":\"x\",\"confirmedAtEpochMs\":1}",
                null,
                null,
            )
        )
    val raw =
        " { \"operationId\" : \"33333333-3333-4333-8333-333333333333\", \"versions\" : [ { \"versionId\" : \"11111111-1111-4111-8111-111111111111\", \"logicalId\" : \"22222222-2222-4222-8222-222222222222\", \"parentVersionId\" : null, \"kind\" : \"health_restriction\", \"state\" : \"CONFIRMED\", \"enteredAtEpochMs\" : 1, \"payload\" : { \"textOriginal\" : \"x\", \"confirmedAtEpochMs\" : 1 } } ], \"heads\" : [ { \"logicalId\" : \"22222222-2222-4222-8222-222222222222\", \"currentVersionId\" : \"11111111-1111-4111-8111-111111111111\", \"baseHeadRevision\" : 0 } ] } "
            .encodeToByteArray()
    db.healthSyncDao()
        .upsertOutbox(
            HealthSyncOutboxEntity("33333333-3333-4333-8333-333333333333", owner, raw, "h")
        )
    db.healthSyncDao().upsertState(HealthSyncStateEntity(owner, null, needsFullRefresh = false))
    val consent = Consent(true)
    val ledger =
        HealthLedgerSync(
            db,
            db.healthDao(),
            db.healthSyncDao(),
            transport,
            store,
            sync,
            consent,
        )
    assertFalse(ledger.replayPending())
    assertTrue(db.healthSyncDao().outbox(owner)?.dispatched == true)
    val retainedState = db.healthSyncDao().state(owner)
    consent.setBackendSyncEnabled(false)
    assertFalse(ledger.replayPending())
    assertEquals(retainedState, db.healthSyncDao().state(owner))
    consent.setBackendSyncEnabled(true)
    db.openHelper.writableDatabase.execSQL(
        "UPDATE backend_state SET acceptedCapabilities='' WHERE id=1"
    )
    assertFalse(ledger.replayPending())
    assertEquals(retainedState, db.healthSyncDao().state(owner))
    assertEquals(1, transport.getPaths.size)
    claimHealth(sync, owner)
    assertTrue(ledger.replayPending())
    assertEquals(2, transport.getPaths.size)
    assertTrue(transport.getPaths.all { it.startsWith(HealthLedgerSync.SNAPSHOT_PATH) })
    assertEquals(2, transport.rawPosts.size)
    assertArrayEquals(raw, transport.rawPosts[0])
    assertArrayEquals(raw, transport.rawPosts[1])
    assertEquals(null, db.healthSyncDao().outbox(owner))
  }

  @Test
  fun `stale acknowledgement preserves later desired edits and captures equal timestamp parents first`() =
      runTest {
        val store = Store()
        val owner = "owner"
        store.save(BackendTokens(owner, "a@b", "a", "r"))
        val logical = "22222222-2222-4222-8222-222222222222"
        val first = "11111111-1111-4111-8111-111111111111"
        val parent = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val child = "44444444-4444-4444-8444-444444444444"
        val server = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        val operation = "33333333-3333-4333-8333-333333333333"
        val result = buildJsonObject {
          put("operationId", operation)
          put(
              "versionReceipts",
              JsonArray(
                  listOf(
                      buildJsonObject {
                        put("versionId", first)
                        put("serverSequence", 2)
                        put("healthRevision", 10)
                      }
                  )
              ),
          )
          put(
              "headResults",
              JsonArray(
                  listOf(
                      buildJsonObject {
                        put("logicalId", logical)
                        put("submittedCurrentVersionId", first)
                        put("serverCurrentVersionId", server)
                        put("headRevision", 5)
                        put("healthRevision", 9)
                        put("outcome", "STALE")
                      }
                  )
              ),
          )
        }
        val remote =
            pageWithHeads(
                listOf(version(server, logical, 1)),
                listOf(head(logical, server, 5, 9)),
                null,
                "c9",
            )
        val transport = RawTransport(store, ArrayDeque(listOf(result)), ArrayDeque(listOf(remote)))
        val sync = BackendSync(db, transport, store)
        claimHealth(sync, owner)
        db.healthDao()
            .upsertRecord(
                HealthLogicalRecordEntity(logical, owner, "RESTRICTION", 1, child, 3, false, null)
            )
        for ((id, preceding) in listOf(first to null, parent to first, child to parent)) {
          db.healthDao()
              .insertVersion(
                  HealthRecordVersionEntity(
                      id,
                      logical,
                      preceding,
                      "RESTRICTION",
                      "CONFIRMED",
                      1,
                      version(id, logical, 1)["payload"].toString(),
                      null,
                      null,
                  )
              )
        }
        val requestVersion =
            JsonObject(
                version(first, logical, 1).filterKeys {
                  it !in setOf("serverSequence", "healthRevision")
                }
            )
        val bytes =
            buildJsonObject {
                  put("operationId", operation)
                  put("versions", JsonArray(listOf(requestVersion)))
                  put(
                      "heads",
                      JsonArray(
                          listOf(
                              buildJsonObject {
                                put("logicalId", logical)
                                put("currentVersionId", first)
                                put("baseHeadRevision", 0)
                              }
                          )
                      ),
                  )
                }
                .toString()
                .encodeToByteArray()
        db.healthSyncDao().upsertOutbox(HealthSyncOutboxEntity(operation, owner, bytes, "h"))
        val ledger =
            HealthLedgerSync(
                db,
                db.healthDao(),
                db.healthSyncDao(),
                transport,
                store,
                sync,
                Consent(true),
            )
        assertTrue(ledger.replayPending())
        assertArrayEquals(bytes, transport.rawPosts.single())
        assertEquals(child, db.healthDao().record(logical, owner)?.currentVersionId)
        assertEquals(2L, db.healthDao().version(first)?.serverSequence)
        val next = requireNotNull(db.healthSyncDao().outbox(owner))
        assertTrue(next.operationId != operation)
        val captured = Json.parseToJsonElement(next.requestBytes.decodeToString()).jsonObject
        assertEquals(
            listOf(parent, child),
            captured.getValue("versions").jsonArray.map {
              it.jsonObject.getValue("versionId").jsonPrimitive.content
            },
        )
        assertEquals(
            5L,
            captured
                .getValue("heads")
                .jsonArray
                .single()
                .jsonObject
                .getValue("baseHeadRevision")
                .jsonPrimitive
                .long,
        )
        assertEquals(
            child,
            captured
                .getValue("heads")
                .jsonArray
                .single()
                .jsonObject
                .getValue("currentVersionId")
                .jsonPrimitive
                .content,
        )
      }

  private fun page(next: String?, cursor: String?, versions: List<JsonObject>) = buildJsonObject {
    put("versions", JsonArray(versions))
    put("heads", JsonArray(emptyList()))
    if (next == null) put("nextPageToken", JsonNull) else put("nextPageToken", next)
    if (cursor == null) put("commitCursor", JsonNull) else put("commitCursor", cursor)
  }

  private fun pageWithHeads(
      versions: List<JsonObject>,
      heads: List<JsonObject>,
      next: String?,
      cursor: String?,
  ) = buildJsonObject {
    put("versions", JsonArray(versions))
    put("heads", JsonArray(heads))
    if (next == null) put("nextPageToken", JsonNull) else put("nextPageToken", next)
    if (cursor == null) put("commitCursor", JsonNull) else put("commitCursor", cursor)
  }

  private suspend fun claimHealth(sync: BackendSync, owner: String) {
    sync.claim(owner)
    db.openHelper.writableDatabase.execSQL(
        "UPDATE backend_state SET capabilityOwner=?,acceptedCapabilities=? WHERE id=1",
        arrayOf(owner, HealthLedgerSync.CAPABILITY),
    )
  }

  private fun version(id: String, logical: String, revision: Long, parent: String? = null) =
      buildJsonObject {
        put("versionId", id)
        put("logicalId", logical)
        if (parent == null) put("parentVersionId", JsonNull) else put("parentVersionId", parent)
        put("kind", "health_restriction")
        put("state", "CONFIRMED")
        put("enteredAtEpochMs", 1)
        put(
            "payload",
            buildJsonObject {
              put("textOriginal", "Беречь колено")
              put("confirmedAtEpochMs", 1)
            },
        )
        put("serverSequence", revision)
        put("healthRevision", revision)
      }

  private fun head(logical: String, current: String, headRevision: Long, healthRevision: Long) =
      buildJsonObject {
        put("logicalId", logical)
        put("currentVersionId", current)
        put("headRevision", headRevision)
        put("kind", "health_restriction")
        put("deleted", false)
        put("healthRevision", healthRevision)
      }
}
