package com.valerochka1337.valerochkagym.data

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.valerochka1337.valerochkagym.data.ai.BackendAiRepository
import com.valerochka1337.valerochkagym.data.ai.ExerciseAiGenerationResult
import com.valerochka1337.valerochkagym.data.ai.InBodyPhotoEncoder
import com.valerochka1337.valerochkagym.data.ai.InBodyPhotoEncodingResult
import com.valerochka1337.valerochkagym.data.ai.InBodyReportAiResult
import com.valerochka1337.valerochkagym.data.backend.BackendResponse
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.backend.SyncReady
import com.valerochka1337.valerochkagym.data.backend.SyncReadySource
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.HealthAiConsentStateEntity
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.health.AiDisclosureReceipt
import com.valerochka1337.valerochkagym.data.health.HealthAiDisclosureRepository
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendAiRepositoryTest : RoomDaoTest() {
  private class Store : BackendSessionStore {
    override val session =
        MutableStateFlow<BackendTokens?>(
            BackendTokens("owner-a", "owner@example.com", "access", "refresh"),
        )

    override fun save(tokens: BackendTokens?) {
      session.value = tokens
    }
  }

  private class Ready : SyncReadySource {
    override suspend fun await(): SyncReady = SyncReady.Ready("owner-a", 8, 9)

    override suspend fun isCurrent(ready: SyncReady.Ready): Boolean = true
  }

  private class Encoder : InBodyPhotoEncoder {
    var calls = 0
    var started: CompletableDeferred<Unit>? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun encode(uri: Uri): InBodyPhotoEncodingResult {
      calls++
      started?.complete(Unit)
      gate?.await()
      return InBodyPhotoEncodingResult.Success("data:image/jpeg;base64,aW1hZ2U=")
    }
  }

  private class GateReady : SyncReadySource {
    val started = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    var current = true

    override suspend fun await(): SyncReady {
      started.complete(Unit)
      gate.await()
      return SyncReady.Ready("owner-a", 8, 9)
    }

    override suspend fun isCurrent(ready: SyncReady.Ready): Boolean = current
  }

  /** Simulates A → B → A where equal revisions are backed by a new cache generation. */
  private class AbaReady : SyncReadySource {
    private var checks = 0
    private var current = true

    override suspend fun await(): SyncReady =
        SyncReady.Ready("owner-a", 8, 9, sessionEpoch = 4, cacheGeneration = 11)

    override suspend fun isCurrent(ready: SyncReady.Ready): Boolean {
      checks++
      if (checks == 2) current = false // just after the pre-map guard, before DAO mapping completes
      return checks == 2 || current
    }
  }

  private class Api : BackendTransport {
    override val json = Json { encodeDefaults = true }
    override var acceptedCapabilities: Set<String> = emptySet()
    var requests = 0
    var disclosureRequests = 0
    var headers: Map<String, String> = emptyMap()
    var body: JsonObject? = null
    var disclosureReceipt = AiDisclosureReceipt(7, 1, true, 1)
    var afterDraft: suspend () -> Unit = {}
    var draftRetriesOnUnauthorized: Boolean? = null

    override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement =
        error("No public request is expected")

    override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement =
        authorized(method, path, body, emptyMap())

    override suspend fun authorized(
        method: String,
        path: String,
        body: JsonElement?,
        headers: Map<String, String>,
    ): JsonElement {
      if (method == "GET" && path == "/health-ai-disclosure") {
        disclosureRequests++
        acceptedCapabilities = setOf("health-ledger-v1")
        return json.encodeToJsonElement(disclosureReceipt)
      }
      check(method == "POST")
      check(path == "/ai/inbody-drafts")
      requests++
      this.headers = headers
      this.body = body?.jsonObject
      val request = requireNotNull(this.body)
      afterDraft()
      return buildJsonObject {
        put("requestId", request["requestId"]!!.jsonPrimitive.content)
        put(
            "context",
            buildJsonObject {
              put("revision", 8)
              put("catalogRevision", 9)
            },
        )
        put("result", buildJsonObject { put("kind", "INVALID") })
      }
    }

    override suspend fun authorizedResponse(
        method: String,
        path: String,
        body: JsonElement?,
        headers: Map<String, String>,
        retryOnUnauthorized: Boolean,
    ): BackendResponse {
      if (path == "/ai/inbody-drafts") draftRetriesOnUnauthorized = retryOnUnauthorized
      return BackendResponse(
          authorized(method, path, body, headers),
          "{}".encodeToByteArray(),
          acceptedCapabilities,
          "owner-a",
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
    ): BackendResponse =
        authorizedResponse(
            method,
            path,
            json.parseToJsonElement(rawBody.decodeToString()),
            headers,
            retryOnUnauthorized,
        )
  }

  private class ExerciseApi(
      private val result: JsonObject,
      private val responseOwner: String = "owner-a",
      private val responseEpoch: Long = 0L,
  ) : BackendTransport {
    override val json = Json { encodeDefaults = true }
    var retriesOnUnauthorized: Boolean? = null

    override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement =
        error("No public request is expected")

    override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement =
        error("Response wrapper is required")

    override suspend fun authorizedResponse(
        method: String,
        path: String,
        body: JsonElement?,
        headers: Map<String, String>,
        retryOnUnauthorized: Boolean,
    ): BackendResponse {
      check(method == "POST")
      check(path == "/ai/exercise-drafts")
      retriesOnUnauthorized = retryOnUnauthorized
      val request = body!!.jsonObject
      return BackendResponse(
          buildJsonObject {
            put("requestId", request["requestId"]!!.jsonPrimitive.content)
            put(
                "context",
                buildJsonObject {
                  put("revision", 8)
                  put("catalogRevision", 9)
                },
            )
            put("result", result)
          },
          "{}".encodeToByteArray(),
          emptySet(),
          responseOwner,
          responseEpoch,
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
    ): BackendResponse =
        authorizedResponse(
            method,
            path,
            json.parseToJsonElement(rawBody.decodeToString()),
            headers,
            retryOnUnauthorized,
        )
  }

  @Test
  fun `InBody blocks before photo encoding without an enabled owner receipt`() = runTest {
    val settings = settings(backgroundScope)
    val api = Api()
    val encoder = Encoder()
    val repository =
        BackendAiRepository(
            api,
            Ready(),
            db.exerciseDao(),
            encoder,
            HealthAiDisclosureRepository(db, db.healthAiConsentDao(), api, Store(), settings),
        )

    repository.read(Uri.EMPTY)

    assertEquals(0, encoder.calls)
    assertEquals(0, api.requests)
  }

  @Test
  fun `exercise rejects malformed draft fields and never retries its POST after unauthorized`() =
      runTest {
        val remoteId = "11111111-1111-4111-8111-111111111111"
        val api =
            ExerciseApi(
                buildJsonObject {
                  put("kind", "EXISTING")
                  put("exerciseId", remoteId)
                  put("unexpected", true)
                },
            )
        val settings = settings(backgroundScope)
        val repository =
            BackendAiRepository(
                api,
                Ready(),
                db.exerciseDao(),
                Encoder(),
                HealthAiDisclosureRepository(db, db.healthAiConsentDao(), api, Store(), settings),
            )
        db.exerciseDao()
            .insert(
                ExerciseEntity(
                    name = "Тест",
                    muscleGroup = MuscleGroup.LEGS,
                    type = ExerciseType.STRENGTH,
                    syncId = remoteId,
                ),
            )

        val result = repository.generate("Тест")

        assertTrue(
            result is com.valerochka1337.valerochkagym.data.ai.ExerciseAiGenerationResult.Failure
        )
        assertEquals(false, api.retriesOnUnauthorized)
      }

  @Test
  fun `exercise accepts valid existing and new contract drafts`() = runTest {
    val existingId = "11111111-1111-4111-8111-111111111111"
    db.exerciseDao()
        .insert(
            ExerciseEntity(
                name = "Моё",
                muscleGroup = MuscleGroup.LEGS,
                type = ExerciseType.STRENGTH,
                syncId = existingId,
            ),
        )
    val settings = settings(backgroundScope)
    val existingRepository =
        BackendAiRepository(
            ExerciseApi(
                buildJsonObject {
                  put("kind", "EXISTING")
                  put("exerciseId", existingId)
                }
            ),
            Ready(),
            db.exerciseDao(),
            Encoder(),
            HealthAiDisclosureRepository(db, db.healthAiConsentDao(), Api(), Store(), settings),
        )

    assertTrue(existingRepository.generate("описание") is ExerciseAiGenerationResult.Existing)

    val newRepository =
        BackendAiRepository(
            ExerciseApi(
                buildJsonObject {
                  put("kind", "NEW")
                  put("name", "Тяга")
                  put("type", "STRENGTH")
                  put(
                      "muscles",
                      kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                              put("muscle", "LATS")
                              put("contribution", 100)
                            }
                        )
                      },
                  )
                },
            ),
            Ready(),
            db.exerciseDao(),
            Encoder(),
            HealthAiDisclosureRepository(db, db.healthAiConsentDao(), Api(), Store(), settings),
        )

    assertTrue(newRepository.generate("описание") is ExerciseAiGenerationResult.New)
  }

  @Test
  fun `exercise rejects coerced fields and a description above its Unicode limit`() = runTest {
    val api =
        ExerciseApi(
            buildJsonObject {
              put("kind", "NEW")
              put("name", 42)
              put("type", "STRENGTH")
              put("muscles", kotlinx.serialization.json.buildJsonArray {})
            },
        )
    val repository =
        BackendAiRepository(
            api,
            Ready(),
            db.exerciseDao(),
            Encoder(),
            HealthAiDisclosureRepository(
                db,
                db.healthAiConsentDao(),
                Api(),
                Store(),
                settings(backgroundScope),
            ),
        )

    assertTrue(repository.generate("описание") is ExerciseAiGenerationResult.Failure)
    assertTrue(repository.generate("😀".repeat(2_001)) is ExerciseAiGenerationResult.Failure)
  }

  @Test
  fun `A to B to A cache change rejects an existing mapping before a local editor id escapes`() =
      runTest {
        val remoteId = "11111111-1111-4111-8111-111111111111"
        db.exerciseDao()
            .insert(
                ExerciseEntity(
                    name = "Кэш B",
                    muscleGroup = MuscleGroup.LEGS,
                    type = ExerciseType.STRENGTH,
                    syncId = remoteId,
                ),
            )
        val repository =
            BackendAiRepository(
                ExerciseApi(
                    buildJsonObject {
                      put("kind", "EXISTING")
                      put("exerciseId", remoteId)
                    },
                    responseEpoch = 4,
                ),
                AbaReady(),
                db.exerciseDao(),
                Encoder(),
                HealthAiDisclosureRepository(
                    db,
                    db.healthAiConsentDao(),
                    Api(),
                    Store(),
                    settings(backgroundScope),
                ),
            )

        assertTrue(repository.generate("описание") is ExerciseAiGenerationResult.Failure)
      }

  @Test
  fun `response metadata from another session epoch rejects a valid exercise draft`() = runTest {
    val repository =
        BackendAiRepository(
            ExerciseApi(
                buildJsonObject {
                  put("kind", "NEW")
                  put("name", "Тяга")
                  put("type", "STRENGTH")
                  put(
                      "muscles",
                      kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                              put("muscle", "LATS")
                              put("contribution", 100)
                            }
                        )
                      },
                  )
                },
                responseEpoch = 7,
            ),
            Ready(),
            db.exerciseDao(),
            Encoder(),
            HealthAiDisclosureRepository(
                db,
                db.healthAiConsentDao(),
                Api(),
                Store(),
                settings(backgroundScope),
            ),
        )

    assertTrue(repository.generate("описание") is ExerciseAiGenerationResult.Failure)
  }

  @Test
  fun `InBody sends the enabled receipt revision only after local photo encoding`() = runTest {
    val settings = settings(backgroundScope)
    settings.setHealthAiDisclosureEnabled(true)
    db.healthAiConsentDao()
        .upsertState(
            HealthAiConsentStateEntity(
                owner = "owner-a",
                revision = 7,
                noticeVersion = 1,
                enabled = true,
                recordedAtEpochMs = 1,
                receiptBytes = "receipt".encodeToByteArray(),
            ),
        )
    val api = Api()
    val encoder = Encoder()
    val repository =
        BackendAiRepository(
            api,
            Ready(),
            db.exerciseDao(),
            encoder,
            HealthAiDisclosureRepository(db, db.healthAiConsentDao(), api, Store(), settings),
        )

    repository.read(Uri.EMPTY)

    assertEquals(1, encoder.calls)
    // Admission is rechecked before encoding, before upload and before applying the response.
    assertEquals(4, api.disclosureRequests)
    assertEquals(1, api.requests)
    assertEquals(false, api.draftRetriesOnUnauthorized)
    assertEquals("7", api.headers["X-Health-AI-Disclosure-Revision"])
    assertEquals(8, api.body?.get("expectedRevision")?.jsonPrimitive?.content?.toInt())
    assertEquals(9, api.body?.get("expectedCatalogRevision")?.jsonPrimitive?.content?.toInt())
    assertTrue(api.body?.get("image")?.jsonObject?.containsKey("base64") == true)
  }

  @Test
  fun `InBody discards a result when disclosure revokes during the backend draft`() = runTest {
    val settings = settings(backgroundScope)
    settings.setHealthAiDisclosureEnabled(true)
    db.healthAiConsentDao()
        .upsertState(
            HealthAiConsentStateEntity("owner-a", 7, 1, true, 1, "receipt".encodeToByteArray()),
        )
    val api = Api()
    api.afterDraft = { api.disclosureReceipt = AiDisclosureReceipt(8, 1, false, 2) }
    val encoder = Encoder()
    val repository =
        BackendAiRepository(
            api,
            Ready(),
            db.exerciseDao(),
            encoder,
            HealthAiDisclosureRepository(db, db.healthAiConsentDao(), api, Store(), settings),
        )

    val result = repository.read(Uri.EMPTY)

    assertTrue(result is InBodyReportAiResult.Failure)
    assertEquals(1, encoder.calls)
    assertEquals(1, api.requests)
    assertEquals(4, api.disclosureRequests)
  }

  @Test
  fun `InBody discards a result after the authenticated owner changes`() = runTest {
    val settings = settings(backgroundScope)
    settings.setHealthAiDisclosureEnabled(true)
    db.healthAiConsentDao()
        .upsertState(
            HealthAiConsentStateEntity("owner-a", 7, 1, true, 1, "receipt".encodeToByteArray()),
        )
    val store = Store()
    val api = Api()
    api.afterDraft = {
      store.session.value = BackendTokens("owner-b", "other@example.com", "access-b", "refresh-b")
    }
    val encoder = Encoder()
    val repository =
        BackendAiRepository(
            api,
            Ready(),
            db.exerciseDao(),
            encoder,
            HealthAiDisclosureRepository(db, db.healthAiConsentDao(), api, store, settings),
        )

    val result = repository.read(Uri.EMPTY)

    assertTrue(result is InBodyReportAiResult.Failure)
    assertEquals(1, encoder.calls)
    assertEquals(1, api.requests)
  }

  @Test
  fun `InBody revocation during readiness or encoding prevents its draft POST`() = runTest {
    val settings = settings(backgroundScope)
    settings.setHealthAiDisclosureEnabled(true)
    db.healthAiConsentDao()
        .upsertState(HealthAiConsentStateEntity("owner-a", 7, 1, true, 1, byteArrayOf(1)))
    val readiness = GateReady()
    val awaitingApi = Api()
    val awaitingEncoder = Encoder()
    val awaitingRepository =
        BackendAiRepository(
            awaitingApi,
            readiness,
            db.exerciseDao(),
            awaitingEncoder,
            HealthAiDisclosureRepository(
                db,
                db.healthAiConsentDao(),
                awaitingApi,
                Store(),
                settings,
            ),
        )

    val awaiting = async { awaitingRepository.read(Uri.EMPTY) }
    readiness.started.await()
    settings.setHealthAiDisclosureEnabled(false)
    readiness.gate.complete(Unit)
    awaiting.await()

    assertEquals(0, awaitingEncoder.calls)
    assertEquals(0, awaitingApi.requests)

    settings.setHealthAiDisclosureEnabled(true)
    val encodingApi = Api()
    val encoder =
        Encoder().apply {
          started = CompletableDeferred()
          gate = CompletableDeferred()
        }
    val encodingRepository =
        BackendAiRepository(
            encodingApi,
            Ready(),
            db.exerciseDao(),
            encoder,
            HealthAiDisclosureRepository(
                db,
                db.healthAiConsentDao(),
                encodingApi,
                Store(),
                settings,
            ),
        )

    val encoding = async { encodingRepository.read(Uri.EMPTY) }
    encoder.started!!.await()
    settings.setHealthAiDisclosureEnabled(false)
    encoder.gate!!.complete(Unit)
    encoding.await()

    assertEquals(1, encoder.calls)
    assertEquals(0, encodingApi.requests)
  }

  private fun settings(scope: CoroutineScope): SettingsRepository {
    val file = File.createTempFile("backend-ai-${UUID.randomUUID()}", ".preferences_pb")
    file.delete()
    return SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
  }
}
