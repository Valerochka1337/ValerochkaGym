package com.valerochka1337.valerochkagym.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.backend.BackendResponse
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.health.AiDisclosureReceipt
import com.valerochka1337.valerochkagym.data.health.HealthAiDisclosureRepository
import com.valerochka1337.valerochkagym.data.health.HealthAiDisclosureResult
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthAiDisclosureRepositoryTest : RoomDaoTest() {
  private class Store(owner: String? = "owner-a") : BackendSessionStore {
    override val session =
        MutableStateFlow(
            owner?.let { BackendTokens(it, "$it@example.com", "access", "refresh") },
        )

    override fun save(tokens: BackendTokens?) {
      session.value = tokens
    }
  }

  private data class Request(
      val method: String,
      val path: String,
      val body: JsonElement?,
      val headers: Map<String, String>,
  )

  private class Api(private val owner: String = "owner-a") : BackendTransport {
    override val json = Json { encodeDefaults = true }
    override var acceptedCapabilities: Set<String> = setOf("health-ledger-v1")
    val requests = mutableListOf<Request>()
    var failPost = false
    var failure: BackendException? = null
    var receipt = AiDisclosureReceipt(3, 1, true, 1_700_000_000_000)
    var rawReceipt =
        "{\"revision\":3,\"noticeVersion\":1,\"enabled\":true,\"recordedAtEpochMs\":1700000000000}"
            .encodeToByteArray()
    var respondWithRequestChoice = false
    val rawBodies = mutableListOf<ByteArray>()
    var postStarted: CompletableDeferred<Unit>? = null
    var postGate: CompletableDeferred<Unit>? = null

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
      requests += Request(method, path, body, headers)
      failure?.let { throw it }
      if (method == "POST" && failPost) throw IOException("offline")
      if (method == "POST") {
        postStarted?.complete(Unit)
        postGate?.await()
      }
      if (method == "POST" && respondWithRequestChoice) {
        receipt =
            receipt.copy(
                revision = receipt.revision + 1,
                enabled = body!!.jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean(),
            )
        rawReceipt =
            json.encodeToString(AiDisclosureReceipt.serializer(), receipt).encodeToByteArray()
      }
      return json.encodeToJsonElement(receipt)
    }

    override suspend fun authorizedResponse(
        method: String,
        path: String,
        body: JsonElement?,
        headers: Map<String, String>,
        retryOnUnauthorized: Boolean,
    ): BackendResponse =
        BackendResponse(
            authorized(method, path, body, headers),
            rawReceipt,
            acceptedCapabilities,
            owner,
        )

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
      rawBodies += rawBody.copyOf()
      return BackendResponse(
          authorized(method, path, json.parseToJsonElement(rawBody.decodeToString()), headers),
          rawReceipt,
          acceptedCapabilities,
          expectedOwner,
          expectedSessionEpoch ?: 0L,
      )
    }
  }

  @Test
  fun `grant persists a receipt and uses the health capability on disclosure routes`() = runTest {
    val api = Api()
    val settings = settings(backgroundScope)
    val repository =
        HealthAiDisclosureRepository(db, db.healthAiConsentDao(), api, Store(), settings)

    val grant = repository.setEnabled(true)
    val refreshed = repository.refresh()

    assertEquals(HealthAiDisclosureResult.Updated(api.receipt), grant)
    assertEquals(HealthAiDisclosureResult.Updated(api.receipt), refreshed)
    assertTrue(settings.settings.first().healthAiDisclosureEnabled)
    assertEquals(api.receipt.revision, db.healthAiConsentDao().state("owner-a")?.revision)
    assertTrue(db.healthAiConsentDao().state("owner-a")?.receiptBytes contentEquals api.rawReceipt)
    assertNull(db.healthAiConsentDao().outbox("owner-a"))
    assertEquals(listOf("POST", "GET"), api.requests.map(Request::method))
    assertTrue(api.requests.all { it.path == "/health-ai-disclosure" })
    assertTrue(
        api.requests.all { it.headers["X-Gym-Capabilities"] == "calendar-plans,health-ledger-v1" },
    )
    assertTrue(
        api.requests
            .first()
            .body
            .toString()
            .matches(
                Regex(
                    "\\{\\\"operationId\\\":\\\"[0-9a-f-]{36}\\\",\\\"baseRevision\\\":0,\\\"noticeVersion\\\":1,\\\"enabled\\\":true}",
                ),
            ),
    )
  }

  @Test
  fun `guest cannot set a local consent and failed grant retains exact request bytes`() = runTest {
    val guestSettings = settings(backgroundScope, "guest")
    val guestApi = Api()
    val guestRepository =
        HealthAiDisclosureRepository(
            db,
            db.healthAiConsentDao(),
            guestApi,
            Store(owner = null),
            guestSettings,
        )

    assertEquals(HealthAiDisclosureResult.Blocked, guestRepository.setEnabled(true))
    assertFalse(guestSettings.settings.first().healthAiDisclosureEnabled)
    assertTrue(guestApi.requests.isEmpty())

    val api = Api().apply { failPost = true }
    val settings = settings(backgroundScope, "offline")
    val repository =
        HealthAiDisclosureRepository(db, db.healthAiConsentDao(), api, Store(), settings)

    assertTrue(repository.setEnabled(true) is HealthAiDisclosureResult.Failure)
    val outbox = db.healthAiConsentDao().outbox("owner-a") ?: error("missing durable operation")
    assertTrue(settings.settings.first().healthAiDisclosureEnabled)
    assertEquals(
        sha256(outbox.requestBytes),
        outbox.requestSha256,
    )
    assertTrue(
        outbox.requestBytes
            .decodeToString()
            .matches(
                Regex(
                    "\\{\\\"operationId\\\":\\\"${outbox.operationId}\\\",\\\"baseRevision\\\":0,\\\"noticeVersion\\\":1,\\\"enabled\\\":true}",
                ),
            ),
    )
    assertTrue(outbox.dispatched)
    assertTrue(api.rawBodies.single() contentEquals outbox.requestBytes)
  }

  @Test
  fun `missing, downgraded and 426 capability responses fail closed without erasing evidence`() =
      runTest {
        val missingApi = Api("owner-missing").apply { acceptedCapabilities = emptySet() }
        val missingSettings = settings(backgroundScope, "missing")
        val missing =
            HealthAiDisclosureRepository(
                db,
                db.healthAiConsentDao(),
                missingApi,
                Store("owner-missing"),
                missingSettings,
            )

        assertEquals(HealthAiDisclosureResult.Blocked, missing.setEnabled(true))
        assertTrue(missingSettings.settings.first().healthAiDisclosureEnabled)
        assertTrue(db.healthAiConsentDao().outbox("owner-missing") != null)
        assertNull(db.healthAiConsentDao().state("owner-missing"))

        val downgradeApi = Api("owner-downgrade")
        val downgradeSettings = settings(backgroundScope, "downgrade")
        val downgrade =
            HealthAiDisclosureRepository(
                db,
                db.healthAiConsentDao(),
                downgradeApi,
                Store("owner-downgrade"),
                downgradeSettings,
            )
        assertTrue(downgrade.setEnabled(true) is HealthAiDisclosureResult.Updated)
        downgradeApi.acceptedCapabilities = emptySet()

        assertEquals(HealthAiDisclosureResult.Blocked, downgrade.refresh())
        assertNull(downgrade.admission())
        assertEquals(3L, db.healthAiConsentDao().state("owner-downgrade")?.revision)

        val requiredApi =
            Api("owner-required").apply {
              failure = BackendException(426, "capability_required", "Capability required")
            }
        val required =
            HealthAiDisclosureRepository(
                db,
                db.healthAiConsentDao(),
                requiredApi,
                Store("owner-required"),
                settings(backgroundScope, "required"),
            )

        assertEquals(HealthAiDisclosureResult.Blocked, required.setEnabled(true))
        assertTrue(db.healthAiConsentDao().outbox("owner-required") != null)
      }

  @Test
  fun `recreated recovery drains an old operation before the latest explicit owner choice`() =
      runTest {
        val settings = settings(backgroundScope, "recovery")
        val api = Api("owner-recovery").apply { failPost = true }
        val first =
            HealthAiDisclosureRepository(
                db,
                db.healthAiConsentDao(),
                api,
                Store("owner-recovery"),
                settings,
            )

        assertTrue(first.setEnabled(true) is HealthAiDisclosureResult.Failure)
        assertTrue(first.setEnabled(false) is HealthAiDisclosureResult.Failure)
        assertEquals(false, db.healthAiConsentDao().intent("owner-recovery")?.enabled)

        api.failPost = false
        api.respondWithRequestChoice = true
        val recreated =
            HealthAiDisclosureRepository(
                db,
                db.healthAiConsentDao(),
                api,
                Store("owner-recovery"),
                settings,
            )

        val result = recreated.recoverPending()

        assertTrue(result is HealthAiDisclosureResult.Updated)
        assertFalse(db.healthAiConsentDao().state("owner-recovery")!!.enabled)
        assertNull(db.healthAiConsentDao().outbox("owner-recovery"))
        assertNull(db.healthAiConsentDao().intent("owner-recovery"))
        assertFalse(settings.settings.first().healthAiDisclosureEnabled)
        assertEquals(
            listOf(true, true, true, false),
            api.requests.map {
              it.body?.jsonObject?.get("enabled")?.jsonPrimitive?.content?.toBoolean()
            },
        )
      }

  @Test
  fun `revoke during a suspended grant persists latest intent and drains it once`() = runTest {
    val api =
        Api().apply {
          respondWithRequestChoice = true
          postStarted = CompletableDeferred()
          postGate = CompletableDeferred()
        }
    val settings = settings(backgroundScope, "concurrent")
    val repository =
        HealthAiDisclosureRepository(db, db.healthAiConsentDao(), api, Store(), settings)

    val grant = async { repository.setEnabled(true) }
    api.postStarted!!.await()
    val revoke = async { repository.setEnabled(false) }
    settings.settings.first { !it.healthAiDisclosureEnabled }
    withContext(Dispatchers.Default) {
      withTimeout(5_000) {
        while (db.healthAiConsentDao().intent("owner-a")?.enabled != false) yield()
      }
    }

    assertFalse(settings.settings.first().healthAiDisclosureEnabled)
    assertEquals(false, db.healthAiConsentDao().intent("owner-a")?.enabled)
    api.postGate!!.complete(Unit)
    grant.await()
    revoke.await()

    assertEquals(
        listOf(true, false),
        api.rawBodies.map { bytes ->
          api.json
              .parseToJsonElement(bytes.decodeToString())
              .jsonObject["enabled"]!!
              .jsonPrimitive
              .content
              .toBoolean()
        },
    )
    assertFalse(db.healthAiConsentDao().state("owner-a")!!.enabled)
    assertNull(db.healthAiConsentDao().outbox("owner-a"))
    assertNull(db.healthAiConsentDao().intent("owner-a"))
  }

  private fun settings(
      scope: CoroutineScope,
      prefix: String = UUID.randomUUID().toString(),
  ): SettingsRepository {
    val file = File.createTempFile("health-ai-$prefix", ".preferences_pb")
    file.delete()
    return SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
  }

  private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
