package com.valerochka1337.valerochkagym.data.ai

import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CoachModelCatalog(
    val available: Boolean,
    val defaultModel: String?,
    val models: List<String>,
)

interface CoachModelCatalogSource {
  suspend fun catalog(expectedOwner: String, expectedSessionEpoch: Long?): CoachModelCatalog
}

/** Owner-bound proxy for the server-held coach credentials. It has no provider URL or secret. */
@Singleton
class BackendCoachModelGateway
@Inject
constructor(
    private val backend: BackendTransport,
    private val sessions: BackendSessionStore,
    private val settings: SettingsRepository,
) : CoachModelGateway, CoachModelCatalogSource {
  private val wireJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
  }

  override fun stream(
      expectedOwner: String,
      expectedSessionEpoch: Long?,
      messages: List<AiApiMessage>,
      tools: List<AiApiTool>,
  ): kotlinx.coroutines.flow.Flow<CoachModelEvent> =
      kotlinx.coroutines.flow.flow {
        val requestId = UUID.randomUUID().toString()
        val epoch = expectedSessionEpoch ?: sessions.snapshot()?.epoch
        require(messages.size <= MAX_MESSAGES) { "Too many coach messages" }
        require(tools.size <= MAX_TOOLS) { "Too many coach tools" }
        pin(expectedOwner, expectedSessionEpoch)
        val catalog = catalog(expectedOwner, expectedSessionEpoch)
        if (!catalog.available)
            throw BackendException(503, "coach_unconfigured", "Тренер не настроен на сервере")
        // DataStore is local, but the selected value still belongs to the session that started this
        // turn. Check on both sides of the suspension before putting it on the wire.
        pin(expectedOwner, expectedSessionEpoch)
        val selected = settings.coachModel(expectedOwner).first()
        pin(expectedOwner, expectedSessionEpoch)
        require(selected == null || selected in catalog.models) {
          "Saved coach model is not allowed"
        }
        val model =
            selected
                ?: requireNotNull(catalog.defaultModel) { "Available coach has no default model" }
        val request =
            CoachTurnRequest(
                requestId = requestId,
                model = model,
                messages = messages,
                tools = tools,
            )
        val body =
            wireJson.encodeToString(CoachTurnRequest.serializer(), request).encodeToByteArray()
        require(body.size <= MAX_REQUEST_BYTES) { "Coach request is too large" }
        pin(expectedOwner, expectedSessionEpoch)
        var completed = false
        var deltaChars = 0
        backend
            .authorizedEventStream("/ai/coach-turn/stream", body, expectedOwner, epoch)
            .collect { response ->
              require(!completed) { "Event after completion" }
              require(response.owner == expectedOwner && response.sessionEpoch == epoch) {
                "Coach response owner changed"
              }
              pin(expectedOwner, epoch)
              require(response.data.encodeToByteArray().size <= MAX_RESPONSE_BYTES) {
                "Coach event too large"
              }
              val value = wireJson.parseToJsonElement(response.data).jsonObject
              require(value["requestId"]?.jsonPrimitive?.content == requestId) {
                "Coach response correlation changed"
              }
              if (response.event != "error") {
                require(value["model"]?.jsonPrimitive?.content == model) {
                  "Coach response model changed"
                }
              }
              when (response.event) {
                "text_delta" -> {
                  val delta =
                      value["delta"]?.jsonPrimitive?.takeIf { it.isString }?.content
                          ?: error("Invalid text delta")
                  deltaChars += delta.length
                  require(deltaChars <= MAX_RESPONSE_BYTES) { "Coach text too large" }
                  pin(expectedOwner, epoch)
                  emit(CoachModelEvent.TextDelta(delta))
                }
                "completed" -> {
                  val decoded =
                      wireJson.decodeFromString(CoachTurnResponse.serializer(), response.data)
                  pin(expectedOwner, epoch)
                  completed = true
                  emit(CoachModelEvent.Completed(decoded.completion))
                }
                "error" -> {
                  val code = value["code"]?.jsonPrimitive?.content ?: "ai_unavailable"
                  if (code == "ai_timeout") throw java.io.InterruptedIOException("Coach timeout")
                  throw BackendException(
                      if (code == "unauthorized") 401 else 503,
                      code,
                      value["message"]?.jsonPrimitive?.content ?: "Сервер модели недоступен",
                  )
                }
                else -> error("Unexpected coach event")
              }
            }
        check(completed) { "Coach stream ended without completion" }
      }

  override suspend fun catalog(
      expectedOwner: String,
      expectedSessionEpoch: Long?,
  ): CoachModelCatalog {
    pin(expectedOwner, expectedSessionEpoch)
    val response =
        backend.authorizedRawResponse(
            method = "GET",
            path = "/ai/coach-models",
            rawBody = ByteArray(0),
            expectedOwner = expectedOwner,
            expectedSessionEpoch = expectedSessionEpoch,
            retryOnUnauthorized = true,
            maxResponseBytes = MAX_RESPONSE_BYTES,
        )
    require(
        response.owner == expectedOwner &&
            response.sessionEpoch == (expectedSessionEpoch ?: response.sessionEpoch)
    ) {
      "Coach catalog owner changed"
    }
    pin(expectedOwner, expectedSessionEpoch)
    val decoded =
        wireJson.decodeFromString(
            CoachModelsResponse.serializer(),
            response.rawBody.decodeToString(),
        )
    require(decoded.availability in setOf("AVAILABLE", "UNCONFIGURED")) {
      "Coach availability is invalid"
    }
    val models = decoded.models.distinct()
    require(
        models.size == decoded.models.size &&
            models.size <= MAX_MODELS &&
            models.all { it.isNotBlank() && it.length <= MAX_MODEL_ID_CHARS }
    ) {
      "Coach catalog is invalid"
    }
    when (decoded.availability) {
      "AVAILABLE" ->
          require(decoded.defaultModel != null && decoded.defaultModel in models) {
            "Available coach has no allowed default"
          }
      "UNCONFIGURED" ->
          require(decoded.defaultModel == null && models.isEmpty()) {
            "Unconfigured coach must not expose models"
          }
    }
    return CoachModelCatalog(decoded.availability == "AVAILABLE", decoded.defaultModel, models)
  }

  private fun pin(expectedOwner: String, expectedSessionEpoch: Long?) {
    val current =
        sessions.snapshot() ?: throw BackendException(401, "unauthorized", "Войдите в аккаунт")
    if (
        current.tokens.userId != expectedOwner ||
            (expectedSessionEpoch != null && current.epoch != expectedSessionEpoch)
    ) {
      throw BackendException(401, "owner_changed", "Аккаунт изменился")
    }
  }

  companion object {
    private const val MAX_REQUEST_BYTES = 512 * 1024
    private const val MAX_RESPONSE_BYTES = 256 * 1024
    private const val MAX_MESSAGES = 80
    private const val MAX_TOOLS = 4
    private const val MAX_MODELS = 20
    private const val MAX_MODEL_ID_CHARS = 200
  }
}

@Serializable
private data class CoachModelsResponse(
    val availability: String,
    val defaultModel: String? = null,
    val models: List<String> = emptyList(),
)

@Serializable
private data class CoachTurnRequest(
    val requestId: String,
    val model: String,
    val messages: List<AiApiMessage>,
    val tools: List<AiApiTool>,
)

@Serializable
private data class CoachTurnResponse(
    val requestId: String,
    val model: String? = null,
    val completion: AiApiChatResponse,
)
