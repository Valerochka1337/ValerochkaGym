package com.valerochka1337.valerochkagym.data.health

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.backend.BackendResponse
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.HealthAiConsentDao
import com.valerochka1337.valerochkagym.data.db.entity.HealthAiConsentIntentEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthAiConsentOutboxEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthAiConsentStateEntity
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class AiDisclosureRequest(
    val operationId: String,
    val baseRevision: Long,
    val noticeVersion: Int,
    val enabled: Boolean,
)

@Serializable
data class AiDisclosureReceipt(
    val revision: Long,
    val noticeVersion: Int,
    val enabled: Boolean,
    val recordedAtEpochMs: Long,
)

sealed interface HealthAiDisclosureResult {
  data class Updated(val receipt: AiDisclosureReceipt) : HealthAiDisclosureResult

  data object Blocked : HealthAiDisclosureResult

  data class Failure(val message: String) : HealthAiDisclosureResult
}

data class HealthAiAdmission(val owner: String, val revision: Long)

/**
 * Owns a literal one-at-a-time consent operation and the latest explicit owner choice. The intent
 * survives a process death, but is never invented by recovery.
 */
@Singleton
class HealthAiDisclosureRepository
@Inject
constructor(
    private val database: GymDatabase,
    private val dao: HealthAiConsentDao,
    private val api: BackendTransport,
    private val sessions: BackendSessionStore,
    private val settings: SettingsRepository,
) {
  val localEnabled: Flow<Boolean> = settings.settings.map { it.healthAiDisclosureEnabled }

  private val json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    prettyPrint = false
  }
  private val drainMutex = Mutex()

  suspend fun admission(): HealthAiAdmission? {
    val session = sessions.session.value ?: return null
    val sessionEpoch = sessions.sessionEpoch
    val owner = session.userId
    if (!settings.settings.first().healthAiDisclosureEnabled) return null
    dao.state(owner) ?: return null
    if (refresh() !is HealthAiDisclosureResult.Updated) return null
    val state = dao.state(owner) ?: return null
    if (
        sessions.session.value != session ||
            sessions.sessionEpoch != sessionEpoch ||
            !settings.settings.first().healthAiDisclosureEnabled
    )
        return null
    return state
        .takeIf { it.enabled && it.noticeVersion == CURRENT_NOTICE_VERSION && it.revision >= 0 }
        ?.let { HealthAiAdmission(owner, it.revision) }
  }

  suspend fun setEnabled(enabled: Boolean): HealthAiDisclosureResult {
    val owner = sessions.session.value?.userId ?: return HealthAiDisclosureResult.Blocked
    // A stalled grant never delays the immediate local privacy deny or its durable intent.
    settings.setHealthAiDisclosureEnabled(enabled)
    database.withTransaction { dao.upsertIntent(HealthAiConsentIntentEntity(owner, enabled)) }
    return drainMutex.withLock { drain(owner, retriedAfterConflict = false) }
  }

  /** Replays only an already durable owner intent; it never creates consent on its own. */
  suspend fun recoverPending(): HealthAiDisclosureResult? {
    val owner = sessions.session.value?.userId ?: return null
    if (dao.intent(owner) == null && dao.outbox(owner) == null) return null
    return drainMutex.withLock { drain(owner, retriedAfterConflict = false) }
  }

  suspend fun refresh(): HealthAiDisclosureResult {
    val session = sessions.session.value ?: return HealthAiDisclosureResult.Blocked
    val owner = session.userId
    val sessionEpoch = sessions.sessionEpoch
    return try {
      val response = api.authorizedResponse("GET", DISCLOSURE_PATH, headers = capabilityHeaders)
      if (!isCurrentHealthResponse(owner, session, sessionEpoch, response)) {
        return HealthAiDisclosureResult.Blocked
      }
      val receipt = api.json.decodeFromJsonElement<AiDisclosureReceipt>(response.body)
      if (applyReceipt(owner, session, sessionEpoch, receipt, response.rawBody)) {
        HealthAiDisclosureResult.Updated(receipt)
      } else HealthAiDisclosureResult.Blocked
    } catch (error: CancellationException) {
      throw error
    } catch (error: BackendException) {
      if (error.status in setOf(401, 403, 426)) HealthAiDisclosureResult.Blocked
      else HealthAiDisclosureResult.Failure("Не удалось обновить согласие")
    } catch (_: Exception) {
      HealthAiDisclosureResult.Failure("Не удалось обновить согласие")
    }
  }

  private suspend fun drain(
      owner: String,
      retriedAfterConflict: Boolean,
  ): HealthAiDisclosureResult {
    val session = sessions.session.value ?: return HealthAiDisclosureResult.Blocked
    if (session.userId != owner) return HealthAiDisclosureResult.Blocked
    val sessionEpoch = sessions.sessionEpoch
    val outbox = ensureOutbox(owner) ?: return HealthAiDisclosureResult.Blocked
    return try {
      database.withTransaction { dao.markDispatched(owner) }
      val response =
          api.authorizedRawResponse(
              method = "POST",
              path = DISCLOSURE_PATH,
              rawBody = outbox.requestBytes,
              headers = capabilityHeaders,
              expectedOwner = owner,
              expectedSessionEpoch = sessionEpoch,
              retryOnUnauthorized = false,
          )
      if (!isCurrentHealthResponse(owner, session, sessionEpoch, response)) {
        return HealthAiDisclosureResult.Blocked
      }
      val receipt = api.json.decodeFromJsonElement<AiDisclosureReceipt>(response.body)
      if (!applyReceipt(owner, session, sessionEpoch, receipt, response.rawBody)) {
        return HealthAiDisclosureResult.Blocked
      }
      val nextIntent =
          database.withTransaction {
            dao.deleteOutbox(owner, outbox.operationId)
            val latest = dao.intent(owner)
            if (latest == null || latest.enabled == receipt.enabled) {
              dao.deleteIntentIfEnabled(owner, receipt.enabled)
            }
            dao.intent(owner)
          }
      if (nextIntent == null) {
        HealthAiDisclosureResult.Updated(receipt)
      } else {
        drain(owner, retriedAfterConflict = false)
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: BackendException) {
      if (
          !retriedAfterConflict &&
              error.status == 409 &&
              error.code in setOf("consent_revision_conflict", "consent_operation_reused")
      ) {
        val refreshed = refresh()
        if (refreshed !is HealthAiDisclosureResult.Updated) return refreshed
        // Replacement is one transaction: a crash retains either the old literal request or the
        // latest durable intent plus its new exact request.
        replaceOutboxFromIntent(owner, outbox.operationId)
        drain(owner, retriedAfterConflict = true)
      } else if (error.status in setOf(401, 403, 426)) {
        HealthAiDisclosureResult.Blocked
      } else {
        HealthAiDisclosureResult.Failure("Не удалось сохранить согласие")
      }
    } catch (_: Exception) {
      HealthAiDisclosureResult.Failure("Не удалось сохранить согласие")
    }
  }

  private suspend fun ensureOutbox(owner: String): HealthAiConsentOutboxEntity? =
      database.withTransaction {
        dao.outbox(owner) ?: dao.intent(owner)?.let { createOutbox(owner, it.enabled) }
      }

  private suspend fun replaceOutboxFromIntent(owner: String, oldOperationId: String) {
    database.withTransaction {
      val intent = dao.intent(owner) ?: return@withTransaction
      dao.deleteOutbox(owner, oldOperationId)
      createOutbox(owner, intent.enabled)
    }
  }

  private suspend fun createOutbox(owner: String, enabled: Boolean): HealthAiConsentOutboxEntity {
    val request =
        AiDisclosureRequest(
            operationId = UUID.randomUUID().toString(),
            baseRevision = dao.state(owner)?.revision ?: 0L,
            noticeVersion = CURRENT_NOTICE_VERSION,
            enabled = enabled,
        )
    val bytes = json.encodeToString(request).encodeToByteArray()
    return HealthAiConsentOutboxEntity(owner, request.operationId, bytes, sha256(bytes)).also {
      dao.upsertOutbox(it)
    }
  }

  private suspend fun applyReceipt(
      owner: String,
      expectedSession: BackendTokens,
      expectedSessionEpoch: Long,
      receipt: AiDisclosureReceipt,
      raw: ByteArray,
  ): Boolean =
      database.withTransaction {
        if (
            sessions.session.value != expectedSession ||
                sessions.sessionEpoch != expectedSessionEpoch
        ) {
          return@withTransaction false
        }
        val old = dao.state(owner)
        if (old == null || receipt.revision >= old.revision) {
          dao.upsertState(
              HealthAiConsentStateEntity(
                  owner,
                  receipt.revision,
                  receipt.noticeVersion,
                  receipt.enabled,
                  receipt.recordedAtEpochMs,
                  raw,
              ),
          )
        }
        true
      }

  private fun isCurrentHealthResponse(
      owner: String,
      expectedSession: BackendTokens,
      expectedSessionEpoch: Long,
      response: BackendResponse,
  ): Boolean =
      sessions.session.value == expectedSession &&
          sessions.sessionEpoch == expectedSessionEpoch &&
          response.owner == owner &&
          response.sessionEpoch == expectedSessionEpoch &&
          "health-ledger-v1" in response.acceptedCapabilities

  private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private companion object {
    const val DISCLOSURE_PATH = "/health-ai-disclosure"
    const val CURRENT_NOTICE_VERSION = 1
    val capabilityHeaders = mapOf("X-Gym-Capabilities" to "calendar-plans,health-ledger-v1")
  }
}
