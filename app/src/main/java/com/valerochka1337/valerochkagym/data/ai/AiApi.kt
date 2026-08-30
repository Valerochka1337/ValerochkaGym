package com.valerochka1337.valerochkagym.data.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/** Минимальный OpenAI-совместимый контракт пользовательского сервера. */
interface AiApi {

    @POST
    suspend fun createCompletion(
        @Url endpoint: String,
        @Header("Authorization") authorization: String,
        @Body request: AiApiChatRequest,
    ): AiApiChatResponse

    @GET
    suspend fun getModels(
        @Url endpoint: String,
        @Header("Authorization") authorization: String,
    ): AiModelsResponse
}

@Serializable
data class AiModelsResponse(
    val data: List<AiModelDto> = emptyList(),
)

@Serializable
data class AiModelDto(
    val id: String,
    @SerialName("owned_by") val ownedBy: String? = null,
)

@Serializable
data class AiApiChatRequest(
    val model: String,
    val messages: List<AiApiMessage>,
    @SerialName("response_format") val responseFormat: AiApiResponseFormat,
    @SerialName("max_tokens") val maxTokens: Int,
    val stream: Boolean = false,
)

@Serializable
data class AiApiMessage(
    val role: String,
    /** Chat Completions принимает строку либо multimodal-массив частей. */
    val content: JsonElement,
) {
    companion object {
        fun text(role: String, text: String): AiApiMessage =
            AiApiMessage(role = role, content = JsonPrimitive(text))

        fun textAndImage(
            role: String,
            text: String,
            imageDataUrl: String,
        ): AiApiMessage = AiApiMessage(
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

/** `json_object` поддерживается шире strict structured outputs у разных upstream-моделей. */
@Serializable
data class AiApiResponseFormat(
    val type: String = "json_object",
)

@Serializable
data class AiApiChatResponse(
    val choices: List<AiApiChoice> = emptyList(),
    val error: AiApiError? = null,
)

@Serializable
data class AiApiChoice(
    val message: AiApiResponseMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
    /** Некоторые upstream-ы прокси возвращают ошибку внутри choice даже при HTTP 200. */
    val error: AiApiError? = null,
)

@Serializable
data class AiApiResponseMessage(
    val role: String? = null,
    val content: JsonElement? = null,
)

/** Код бывает числом или строкой, поэтому сохраняем исходный JSON без хрупкого преобразования. */
@Serializable
data class AiApiError(
    val code: JsonElement? = null,
    val message: String? = null,
    val type: String? = null,
    val metadata: AiApiErrorMetadata? = null,
)

@Serializable
data class AiApiErrorMetadata(
    @SerialName("error_type") val errorType: String? = null,
)

internal val AiApiError.httpCode: Int?
    get() = (code as? JsonPrimitive)?.takeUnless { it is JsonNull }?.intOrNull

internal val AiApiError.normalizedType: String?
    get() = type ?: metadata?.errorType
