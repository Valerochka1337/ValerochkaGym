package com.valerochka1337.valerochkagym.data.backend

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@Singleton
class BackendSync
@Inject
constructor(
    private val database: GymDatabase,
    private val api: BackendTransport,
    private val tokens: BackendSessionStore,
) {
  val mutex = Mutex()
  private val catalog = CatalogSync(database, api)
  private val mutableCatalogConflict = MutableStateFlow(false)
  val catalogConflict = mutableCatalogConflict.asStateFlow()
  private val mutableTransfer =
      MutableStateFlow(GuestTransferState(GuestSyncPhase.GUEST, null, null, false))
  val transfer = mutableTransfer.asStateFlow()

  suspend fun personalCopy(kind: String, id: String): String =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          database.withTransaction {
            check(!active()) { "Завершите тренировку перед созданием копии" }
            val payload = PortableData(db).snapshot(includeStandard = true).getValue("$kind:$id")
            catalog.personalCopy(kind, payload)
          }
        }
      }

  private val mutableStatus = MutableStateFlow("Ожидает синхронизации")
  val status = mutableStatus.asStateFlow()
  private val mutableConflict = MutableStateFlow(false)
  val conflict = mutableConflict.asStateFlow()
  private val db
    get() = database.openHelper.writableDatabase

  init {
    mutableTransfer.value = transferState()
  }

  private fun baseline(): Map<String, CloudRecord> =
      db.query("SELECT recordJson FROM backend_baseline").use { c ->
        buildMap {
          while (c.moveToNext()) {
            val r = api.json.decodeFromString<CloudRecord>(c.getString(0))
            put(r.key, r)
          }
        }
      }

  private fun saveBaseline(r: CloudRecord) {
    db.execSQL(
        "INSERT OR REPLACE INTO backend_baseline(`key`,recordJson) VALUES (?,?)",
        arrayOf(r.key, api.json.encodeToString(r)),
    )
  }

  private fun transferState(): GuestTransferState =
      db.query("SELECT owner,phase,mergeId,initialMergeAcknowledged FROM backend_state WHERE id=1")
          .use {
            if (!it.moveToFirst()) GuestTransferState(GuestSyncPhase.GUEST, null, null, false)
            else {
              val owner = if (it.isNull(0)) null else it.getString(0)
              val phase = GuestSyncPhase.valueOf(it.getString(1))
              GuestTransferState(
                  phase,
                  owner,
                  if (it.isNull(2)) null else it.getString(2),
                  it.getInt(3) != 0,
              )
            }
          }

  fun owner(): String? = transferState().owner

  suspend fun claim(user: String): GuestClaimResult =
      withContext(Dispatchers.IO) {
        database.withTransaction {
          SyncSchema.install(db)
          val previous = transferState()
          if (previous.phase == GuestSyncPhase.CLAIMED && previous.owner != user)
              throw BackendException(
                  409,
                  "claim_owned_by_other",
                  "Перенос данных ожидает входа в прежний аккаунт",
              )
          if (previous.owner == user && previous.phase != GuestSyncPhase.GUEST) {
            mutableTransfer.value = previous
            return@withTransaction GuestClaimResult.Claimed(previous)
          }
          if (active())
              throw BackendException(409, "workout_active", "Сначала завершите тренировку")
          if (previous.phase == GuestSyncPhase.OWNED && previous.owner != null) {
            val footprint = preservationFootprint(previous)
            if (footprint != null) return@withTransaction GuestClaimResult.Blocked(footprint)
            clearAccountData()
          }
          val claimed =
              GuestTransferState(GuestSyncPhase.CLAIMED, user, UUID.randomUUID().toString(), false)
          db.execSQL(
              "UPDATE backend_state SET owner=?,phase='CLAIMED',mergeId=?,initialMergeAcknowledged=0 WHERE id=1",
              arrayOf(user, claimed.mergeId),
          )
          mutableTransfer.value = claimed
          GuestClaimResult.Claimed(claimed)
        }
      }

  // Called in the same Room transaction as the owner change. Cascades remove child rows;
  // the old outbox and baseline must never be reused with another account's credentials.
  private fun clearAccountData() {
    listOf(
            "scheduled_workouts",
            "workouts",
            "routines",
            "gyms",
            "exercises",
            "body_measurements",
            "configuration_tombstones",
            "muscle_load_upgrade_notice",
            "backend_outbox",
            "backend_baseline",
            "backend_conflict_copies",
            "backend_rejected_operations",
        )
        .forEach {
          val personal =
              if (it in setOf("exercises", "gyms", "routines")) " WHERE origin='PERSONAL'" else ""
          db.execSQL("DELETE FROM $it$personal")
        }
    db.execSQL("UPDATE catalog_state SET pendingSnapshot=NULL,originalOutbox=NULL WHERE id=1")
  }

  suspend fun signIn(session: BackendTokens) =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          // Persist the new token only after its account owns a clean cache. If interrupted,
          // assertOwner prevents the previous session from accessing the new cache.
          when (val claim = claim(session.userId)) {
            is GuestClaimResult.Blocked ->
                throw BackendException(
                    409,
                    "guest_data_preservation_required",
                    "Сначала сохраните данные прежнего аккаунта",
                )
            is GuestClaimResult.Claimed -> Unit
          }
          tokens.save(session)
          mutableConflict.value = false
          mutableCatalogConflict.value = false
          mutableStatus.value = "Загружаем ваши тренировки…"
        }
      }

  suspend fun signOut(all: Boolean = false) =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          if (active())
              throw BackendException(409, "workout_active", "Сначала завершите тренировку")
          val user = tokens.session.value?.userId
          if (user != null) assertOwner(user)
          try {
            if (user != null) authorized(user, "POST", if (all) "/logout-all" else "/logout")
          } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Local logout must work offline and with an expired session. Logging out every
            // device needs a server acknowledgement, except when this session is already gone.
            if (all && !(e is BackendException && e.status == 401)) throw e
          }
          tokens.save(null)
          mutableConflict.value = false
          mutableCatalogConflict.value = false
          mutableStatus.value = "Войдите в аккаунт"
        }
      }

  suspend fun deleteAccount(code: String) =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          if (active())
              throw BackendException(409, "workout_active", "Сначала завершите тренировку")
          val user = requireNotNull(tokens.session.value?.userId)
          authorized(user, "DELETE", "/me", buildJsonObject { put("code", code) })
          database.withTransaction {
            clearAccountData()
            db.execSQL("DELETE FROM backend_rejected_operations")
            db.execSQL(
                "UPDATE backend_state SET owner=NULL,phase='GUEST',mergeId=NULL,initialMergeAcknowledged=0 WHERE id=1"
            )
            mutableTransfer.value = GuestTransferState(GuestSyncPhase.GUEST, null, null, false)
          }
          tokens.save(null)
          mutableConflict.value = false
          mutableCatalogConflict.value = false
          mutableStatus.value = "Войдите в аккаунт"
        }
      }

  /** The entire request chain and its UI commit belong to one captured account. */
  suspend fun accountRequests(
      expectedOwner: String,
      requests: List<Pair<String, String>>,
      commit: (JsonElement) -> Unit,
  ) =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          assertOwner(expectedOwner)
          var result: JsonElement = JsonNull
          for ((method, path) in requests) {
            result = authorized(expectedOwner, method, path)
          }
          kotlinx.coroutines.currentCoroutineContext().ensureActive()
          assertOwner(expectedOwner)
          commit(result)
        }
      }

  suspend fun accountRequest(method: String, path: String, body: JsonElement? = null): JsonElement =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          val user = requireNotNull(tokens.session.value?.userId)
          authorized(user, method, path, body)
        }
      }

  private fun assertOwner(user: String) {
    val state = transferState()
    if (
        state.owner != user ||
            state.phase !in setOf(GuestSyncPhase.CLAIMED, GuestSyncPhase.OWNED) ||
            tokens.session.value?.userId != user
    )
        throw BackendException(401, "owner_changed", "Аккаунт изменился")
  }

  private suspend fun authorized(
      user: String,
      method: String,
      path: String,
      body: JsonElement? = null,
  ): JsonElement {
    assertOwner(user)
    return api.authorized(method, path, body).also { assertOwner(user) }
  }

  private fun active(): Boolean =
      db.query("SELECT 1 FROM workouts WHERE finishedAt IS NULL LIMIT 1").use { it.moveToFirst() }

  private fun preservationFootprint(state: GuestTransferState): GuestPreservationFootprint? {
    val local = PortableData(db).snapshot()
    val base = baseline()
    val divergent = (local.keys + base.keys).any { key -> local[key] != base[key]?.payload }
    val generation =
        db.query("SELECT generation FROM backend_state WHERE id=1").use {
          it.moveToFirst()
          it.getLong(0)
        }
    val outbox =
        db.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
          if (it.moveToFirst()) it.getString(0) else null
        }
    val catalogJournal =
        db.query("SELECT pendingSnapshot,originalOutbox,applying FROM catalog_state WHERE id=1")
            .use {
              if (it.moveToFirst())
                  Triple(
                      if (it.isNull(0)) null else it.getString(0),
                      if (it.isNull(1)) null else it.getString(1),
                      it.getInt(2),
                  )
              else Triple(null, null, 0)
            }
    val tombstoneRows =
        db.query("SELECT kind,syncId,updatedAt FROM configuration_tombstones ORDER BY kind,syncId")
            .use {
              buildList {
                while (it.moveToNext()) add(
                    "${it.getString(0)}:${it.getString(1)}:${it.getLong(2)}"
                )
              }
            }
    if (
        !divergent &&
            outbox == null &&
            catalogJournal == Triple(null, null, 0) &&
            tombstoneRows.isEmpty()
    )
        return null
    val material = buildString {
      append(state.phase).append('|').append(state.owner).append('|').append(generation).append('|')
      local.toSortedMap().forEach { (key, value) ->
        append(key).append('=').append(value).append('|')
      }
      base.toSortedMap().forEach { (key, value) ->
        append(key).append('=').append(value).append('|')
      }
      append(outbox).append('|').append(catalogJournal.first).append('|')
      append(catalogJournal.second).append('|').append(catalogJournal.third).append('|')
      tombstoneRows.forEach { append(it).append('|') }
    }
    val fingerprint =
        MessageDigest.getInstance("SHA-256").digest(material.encodeToByteArray()).joinToString("") {
          "%02x".format(it)
        }
    return GuestPreservationFootprint(
        local.size,
        base.size,
        outbox != null,
        catalogJournal != Triple(null, null, 0),
        tombstoneRows.size,
        fingerprint,
    )
  }

  private fun canonicalFingerprint(value: JsonElement): String {
    fun canonical(element: JsonElement): String =
        when (element) {
          is JsonObject ->
              element.entries
                  .sortedBy { it.key }
                  .joinToString(prefix = "{", postfix = "}") { "${it.key}:${canonical(it.value)}" }
          is JsonArray -> element.joinToString(prefix = "[", postfix = "]") { canonical(it) }
          else -> element.toString()
        }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical(value).encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
  }

  suspend fun hasActiveWorkout() = withContext(Dispatchers.IO) { active() }

  suspend fun run(resolve: String? = null) =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          try {
            if (active()) {
              mutableStatus.value = "Синхронизация продолжится после тренировки"
              return@withLock
            }
            val user =
                tokens.session.value?.userId
                    ?: run {
                      catalog.refresh(resolve)
                      return@withLock
                    }
            assertOwner(user)
            val wasCatalogConflict =
                mutableCatalogConflict.value ||
                    db.query("SELECT pendingSnapshot FROM catalog_state WHERE id=1").use {
                      it.moveToFirst() && !it.isNull(0)
                    }
            catalog.refresh(resolve) { assertOwner(user) }
            val personalResolve = if (wasCatalogConflict) null else resolve
            mutableCatalogConflict.value = false
            assertOwner(user)
            mutableStatus.value = "Синхронизация…"
            // No network response is allowed to mutate the set IDs currently used by the foreground
            // service.
            if (active()) {
              mutableStatus.value = "Синхронизация продолжится после тренировки"
              return@withLock
            }
            val pending =
                db.query("SELECT owner,requestJson FROM backend_outbox WHERE id=1").use { c ->
                  if (c.moveToFirst()) {
                    check(c.getString(0) == user)
                    val push = api.json.decodeFromString<CloudPush>(c.getString(1))
                    val rejected =
                        db.query(
                                "SELECT 1 FROM backend_rejected_operations WHERE operationId=? AND owner=?",
                                arrayOf(push.operationId, user),
                            )
                            .use { it.moveToFirst() }
                    push to rejected
                  } else null
                }
            var rejectedByRevisionConflict = false
            if (pending != null) {
              if (!pending.second) {
                try {
                  send(user, pending.first)
                } catch (error: BackendException) {
                  if (error.status == 409 && error.code == "revision_conflict") {
                    database.withTransaction {
                      assertOwner(user)
                      db.execSQL(
                          "INSERT OR IGNORE INTO backend_rejected_operations(operationId,owner) VALUES(?,?)",
                          arrayOf(pending.first.operationId, user),
                      )
                    }
                    rejectedByRevisionConflict = true
                  } else {
                    throw error
                  }
                }
              }
              rejectedByRevisionConflict = rejectedByRevisionConflict || pending.second
            }
            val remote =
                api.json.decodeFromJsonElement<CloudSnapshot>(authorized(user, "GET", "/sync"))
            database.withTransaction {
              assertOwner(user)
              if (active())
                  throw BackendException(
                      409,
                      "workout_active",
                      "Завершите тренировку перед синхронизацией",
                  )
              val local = PortableData(db).snapshot()
              val base = baseline()
              val conflicts = CloudMerge.conflicts(local, base, remote.records)
              val automatic =
                  remote.records.filter { record ->
                    record.key in conflicts &&
                        (record.kind == "workout" ||
                            record.kind == "routine" &&
                                !record.deleted &&
                                local[record.key] != null)
                  }
              val manual = conflicts - automatic.map { it.key }.toSet()
              if (manual.isNotEmpty() && personalResolve == null) {
                mutableConflict.value = true
                throw BackendException(
                    409,
                    "revision_conflict",
                    "Есть изменения с другого устройства. Выберите версию в настройках аккаунта",
                )
              }
              automatic
                  .filter { it.kind == "routine" }
                  .forEach { remoteRoutine ->
                    val state = transferState()
                    val mergeId = state.mergeId ?: "owned:${requireNotNull(state.owner)}"
                    val localPayload = requireNotNull(local[remoteRoutine.key])
                    val fingerprint = canonicalFingerprint(localPayload)
                    val existing =
                        db.query(
                                "SELECT localCopySyncId FROM backend_conflict_copies WHERE mergeId=? AND kind=? AND originalSyncId=? AND remoteRevision=? AND localPayloadFingerprint=?",
                                arrayOf<Any>(
                                    mergeId,
                                    remoteRoutine.kind,
                                    remoteRoutine.id,
                                    remoteRoutine.revision,
                                    fingerprint,
                                ),
                            )
                            .use { cursor ->
                              if (cursor.moveToFirst()) cursor.getString(0) else null
                            }
                    if (existing == null) {
                      val copyId = UUID.randomUUID().toString()
                      PortableData(db)
                          .apply(
                              listOf(CloudRecord("routine", copyId, 0, false, localPayload)),
                              emptyList(),
                          )
                      db.execSQL(
                          "INSERT INTO backend_conflict_copies(mergeId,kind,originalSyncId,remoteRevision,localPayloadFingerprint,localCopySyncId) VALUES(?,?,?,?,?,?)",
                          arrayOf<Any>(
                              mergeId,
                              "routine",
                              remoteRoutine.id,
                              remoteRoutine.revision,
                              fingerprint,
                              copyId,
                          ),
                      )
                    }
                  }
              val incoming =
                  remote.records.filter { r ->
                    val changed = base[r.key]?.revision != r.revision
                    val localChanged = local[r.key] != base[r.key]?.payload
                    changed &&
                        (!localChanged ||
                            local[r.key] == r.payload ||
                            r.key in automatic.map { it.key }.toSet() ||
                            personalResolve == "server" && r.key in conflicts ||
                            base[r.key] == null &&
                                r.kind == "exercise" &&
                                local[r.key]?.get("isCustom")?.toString() == "false")
                  }
              PortableData(db)
                  .apply(incoming.filter { !it.deleted }, incoming.filter { it.deleted })
              remote.records.forEach(::saveBaseline)
              if (rejectedByRevisionConflict) {
                db.execSQL("DELETE FROM backend_outbox WHERE id=1")
                db.execSQL("DELETE FROM backend_rejected_operations WHERE owner=?", arrayOf(user))
              }
            }
            // Capture each exact payload durably before HTTP. A local edit during HTTP is compared
            // against
            // the acknowledged payload in the next iteration, so acknowledgement never clears a
            // newer edit.
            repeat(100) {
              val batch =
                  database.withTransaction {
                    assertOwner(user)
                    if (active()) return@withTransaction null
                    val local = PortableData(db).snapshot()
                    val base = baseline()
                    val order =
                        listOf("exercise", "gym", "routine", "workout", "measurement", "schedule")
                    val changes =
                        (local.keys + base.keys)
                            .mapNotNull { key ->
                              val old = base[key]
                              val payload = local[key]
                              if (payload == old?.payload) null
                              else {
                                val kind = key.substringBefore(':')
                                val id = key.substringAfter(':')
                                CloudChange(kind, id, old?.revision ?: 0, payload == null, payload)
                              }
                            }
                            .sortedBy {
                              if (it.deleted) 20 - order.indexOf(it.kind)
                              else order.indexOf(it.kind)
                            }
                            .take(1000)
                    if (changes.isEmpty()) null
                    else
                        CloudPush(UUID.randomUUID().toString(), changes, catalog.revision()).also {
                          db.execSQL(
                              "INSERT OR REPLACE INTO backend_outbox(id,owner,requestJson) VALUES (1,?,?)",
                              arrayOf(user, api.json.encodeToString(it)),
                          )
                        }
                  }
              if (batch == null) {
                val acknowledged =
                    api.json.decodeFromJsonElement<CloudSnapshot>(authorized(user, "GET", "/sync"))
                database.withTransaction {
                  assertOwner(user)
                  val local = PortableData(db).snapshot()
                  val base = baseline()
                  baseline()
                      .values
                      .filter { !it.deleted && it.payload == local[it.key] }
                      .forEach { r ->
                        val table =
                            when (r.kind) {
                              "workout" -> "workouts"
                              "measurement" -> "body_measurements"
                              else -> null
                            }
                        if (table != null)
                            db.execSQL(
                                "UPDATE $table SET uploadStatus='UPLOADED',uploadError=NULL WHERE id=? AND uploadStatus!='UPLOADED'",
                                arrayOf(r.id),
                            )
                      }
                  val state = transferState()
                  val matchesAcknowledgedSnapshot =
                      acknowledged.records.all { record ->
                        base[record.key] == record &&
                            (record.deleted || local[record.key] == record.payload)
                      } &&
                          local.keys.all { key ->
                            val record = base[key]
                            record != null && !record.deleted && record.payload == local[key]
                          }
                  if (
                      state.phase == GuestSyncPhase.CLAIMED &&
                          matchesAcknowledgedSnapshot &&
                          !db.query("SELECT 1 FROM backend_outbox WHERE id=1").use {
                            it.moveToFirst()
                          }
                  ) {
                    db.execSQL(
                        "UPDATE backend_state SET phase='OWNED',initialMergeAcknowledged=1 WHERE id=1"
                    )
                    mutableTransfer.value =
                        state.copy(phase = GuestSyncPhase.OWNED, initialMergeAcknowledged = true)
                  }
                }
                mutableStatus.value = "Данные синхронизированы"
                mutableConflict.value = false
                mutableCatalogConflict.value = false
                return@withLock
              }
              send(user, batch)
            }
            mutableStatus.value = "Синхронизация продолжится в фоне"
          } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is BackendException && e.code == "catalog_transition_required") {
              mutableCatalogConflict.value = true
              mutableConflict.value = true
            }
            if (e is BackendException && e.code == "revision_conflict") mutableConflict.value = true
            mutableStatus.value =
                if (e is BackendException) e.message
                else "Нет подключения. Повторим сохранение позже"
            throw e
          }
        }
      }

  private suspend fun send(user: String, push: CloudPush) {
    assertOwner(user)
    var sent = push
    suspend fun post(value: CloudPush) =
        api.json.decodeFromJsonElement<CloudAck>(
            authorized(user, "POST", "/sync", api.json.encodeToJsonElement(value))
        )
    val ack =
        try {
          try {
            post(sent)
          } catch (e: BackendException) {
            if (e.code != "catalog_stale") throw e
            catalog.refresh { assertOwner(user) }
            sent =
                push.copy(
                    operationId = UUID.randomUUID().toString(),
                    catalogRevision = catalog.revision(),
                )
            database.withTransaction {
              assertOwner(user)
              if (active())
                  throw BackendException(
                      409,
                      "workout_active",
                      "Завершите тренировку перед синхронизацией",
                  )
              db.execSQL(
                  "UPDATE backend_outbox SET requestJson=? WHERE id=1",
                  arrayOf(api.json.encodeToString(sent)),
              )
            }
            post(sent)
          }
        } catch (error: BackendException) {
          if (error.status == 409 && error.code == "revision_conflict")
              database.withTransaction {
                assertOwner(user)
                db.execSQL(
                    "INSERT OR IGNORE INTO backend_rejected_operations(operationId,owner) VALUES(?,?)",
                    arrayOf(sent.operationId, user),
                )
              }
          throw error
        }
    database.withTransaction {
      assertOwner(user)
      sent.changes.forEach {
        saveBaseline(CloudRecord(it.kind, it.id, ack.revision, it.deleted, it.payload))
      }
      db.execSQL("DELETE FROM backend_outbox WHERE id=1")
    }
  }
}
