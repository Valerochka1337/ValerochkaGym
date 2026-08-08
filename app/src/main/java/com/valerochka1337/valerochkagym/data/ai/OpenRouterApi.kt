package com.valerochka1337.valerochkagym.data.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
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

@Serializable
data class OpenRouterChatRequest(
    val model: String,
    val messages: List<OpenRouterMessage>,
    @SerialName("response_format") val responseFormat: OpenRouterResponseFormat,
    val provider: OpenRouterProviderPreferences,
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Double? = null,
    val stream: Boolean = false,
)

@Serializable
data class OpenRouterMessage(
    val role: String,
    val content: String,
)

@Serializable
data class OpenRouterResponseFormat(
    val type: String,
    @SerialName("json_schema") val jsonSchema: OpenRouterJsonSchema,
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
