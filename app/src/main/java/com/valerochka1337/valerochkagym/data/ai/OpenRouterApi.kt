package com.valerochka1337.valerochkagym.data.ai

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/** Минимальный OpenAI-совместимый контракт OpenRouter для одной нестриминговой генерации. */
interface OpenRouterApi {

    @POST("api/v1/chat/completions")
    suspend fun createCompletion(
        @Header("Authorization") authorization: String,
        @Body request: OpenRouterChatRequest,
    ): OpenRouterChatResponse
}

/** Публичный каталог OpenRouter; ключ не нужен, потому его не передаём в заголовке. */
interface OpenRouterModelsApi {

    @GET("api/v1/models")
    suspend fun getModels(): OpenRouterModelsResponse
}

@Serializable
data class OpenRouterModelsResponse(
    val data: List<OpenRouterModelDto> = emptyList(),
)

@Serializable
data class OpenRouterModelDto(
    val id: String,
    val name: String = "",
    @SerialName("context_length") val contextLength: Int = 0,
    val architecture: OpenRouterModelArchitecture = OpenRouterModelArchitecture(),
    val pricing: OpenRouterModelPricing = OpenRouterModelPricing(),
    @SerialName("supported_parameters") val supportedParameters: List<String> = emptyList(),
    val reasoning: OpenRouterModelReasoning? = null,
    @SerialName("expiration_date") val expirationDate: String? = null,
)

@Serializable
data class OpenRouterModelArchitecture(
    @SerialName("input_modalities") val inputModalities: List<String> = emptyList(),
    @SerialName("output_modalities") val outputModalities: List<String> = emptyList(),
)

@Serializable
data class OpenRouterModelPricing(
    val prompt: String? = null,
    val completion: String? = null,
    val image: String? = null,
)

/** Возможности reasoning из публичного каталога OpenRouter для конкретной модели. */
@Serializable
data class OpenRouterModelReasoning(
    val mandatory: Boolean = false,
    @SerialName("supported_efforts") val supportedEfforts: List<String>? = null,
)

@Serializable
data class OpenRouterChatRequest(
    val model: String,
    val messages: List<OpenRouterMessage>,
    @SerialName("response_format") val responseFormat: OpenRouterResponseFormat,
    val provider: OpenRouterProviderPreferences,
    @SerialName("max_tokens") val maxTokens: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reasoning: OpenRouterReasoningPreferences? = null,
    val temperature: Double? = null,
    val stream: Boolean = false,
)

@Serializable
data class OpenRouterMessage(
    val role: String,
    /** OpenRouter accepts either a text primitive or a multimodal array of parts. */
    val content: JsonElement,
) {
    companion object {
        fun text(role: String, text: String): OpenRouterMessage =
            OpenRouterMessage(role = role, content = JsonPrimitive(text))

        fun textAndImage(
            role: String,
            text: String,
            imageDataUrl: String,
        ): OpenRouterMessage = OpenRouterMessage(
            role = role,
            content = multimodalContent(text, imageDataUrl),
        )

        private fun multimodalContent(text: String, imageDataUrl: String): JsonArray = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
            add(
                buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", imageDataUrl) }
                },
            )
        }
    }
}

@Serializable
data class OpenRouterResponseFormat(
    val type: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("json_schema") val jsonSchema: OpenRouterJsonSchema? = null,
)

@Serializable
data class OpenRouterJsonSchema(
    val name: String,
    val strict: Boolean,
    val schema: JsonObject,
)

@Serializable
data class OpenRouterProviderPreferences(
    @SerialName("require_parameters") val requireParameters: Boolean,
)

/** Нормализованный OpenRouter способ оставить достаточно токенов для финального JSON-ответа. */
@Serializable
data class OpenRouterReasoningPreferences(
    val effort: String,
)

@Serializable
data class OpenRouterChatResponse(
    val choices: List<OpenRouterChoice> = emptyList(),
    val error: OpenRouterApiError? = null,
)

@Serializable
data class OpenRouterChoice(
    val message: OpenRouterResponseMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
    val error: OpenRouterApiError? = null,
)

/**
 * Ответное сообщение отделено от [OpenRouterMessage]: при ошибке провайдера OpenRouter может
 * прислать HTTP 200 с пустым choice или контентом не в строковом виде.
 */
@Serializable
data class OpenRouterResponseMessage(
    val role: String? = null,
    val content: JsonElement? = null,
)

/** Ошибка OpenRouter может быть как HTTP-ошибкой, так и полем успешного HTTP-ответа. */
@Serializable
data class OpenRouterApiError(
    val code: Int? = null,
    val message: String? = null,
    val metadata: OpenRouterErrorMetadata? = null,
)

@Serializable
data class OpenRouterErrorMetadata(
    @SerialName("error_type") val errorType: String? = null,
)
