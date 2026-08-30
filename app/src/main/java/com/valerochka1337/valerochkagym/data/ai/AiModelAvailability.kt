package com.valerochka1337.valerochkagym.data.ai

/** Сообщение сопровождается кнопкой перехода к выбору модели в обоих ИИ-сценариях. */
internal const val MODEL_UNAVAILABLE_MESSAGE =
    "Выбранная модель сейчас недоступна. Выберите другую в настройках."

internal const val AI_REQUEST_TIMEOUT_MESSAGE =
    "Нейросеть отвечает слишком долго — попробуйте ещё раз"

/**
 * Совместимый сервер может вернуть эту ситуацию и HTTP-статусом, и полем error.type внутри
 * ответа 200. Для пользователя оба варианта означают, что полезно выбрать другую модель.
 */
internal fun isAiModelUnavailable(errorType: String?, code: Int?): Boolean =
    errorType in MODEL_UNAVAILABLE_ERROR_TYPES || code in MODEL_UNAVAILABLE_HTTP_CODES

private val MODEL_UNAVAILABLE_ERROR_TYPES = setOf(
    "model_not_found",
    "model_unavailable",
    "provider_overloaded",
    "provider_unavailable",
    "no_endpoints_found",
    "unsupported_model",
    "unsupported_parameters",
)

private val MODEL_UNAVAILABLE_HTTP_CODES = setOf(404, 422, 502, 503)
