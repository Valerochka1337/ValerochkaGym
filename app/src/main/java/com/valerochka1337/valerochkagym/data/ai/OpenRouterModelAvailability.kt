package com.valerochka1337.valerochkagym.data.ai

/** Сообщение сопровождается кнопкой перехода к выбору модели в обоих ИИ-сценариях. */
internal const val MODEL_UNAVAILABLE_MESSAGE =
    "Выбранная бесплатная модель сейчас недоступна. Выберите другую в настройках."

/**
 * OpenRouter может вернуть эту ситуацию и HTTP-статусом, и полем metadata.error_type в ответе
 * 200. Для пользователя оба варианта означают, что повтор с другим выбранным endpoint-ом полезен.
 */
internal fun isOpenRouterModelUnavailable(errorType: String?, code: Int?): Boolean =
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

private val MODEL_UNAVAILABLE_HTTP_CODES = setOf(404, 502, 503)
