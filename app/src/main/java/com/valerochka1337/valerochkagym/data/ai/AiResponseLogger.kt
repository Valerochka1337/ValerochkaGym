package com.valerochka1337.valerochkagym.data.ai

import javax.inject.Inject
import javax.inject.Singleton

/** Источник ответа модели — помогает отделить создание упражнения от распознавания InBody. */
enum class AiResponseSource(val label: String) {
    EXERCISE("exercise"),
    INBODY("inbody"),
}

/**
 * Отладочный вывод уже полученного ответа модели. В него намеренно не передаются запрос,
 * API key и изображение InBody. Реализация должна быть best-effort и никогда не ломать сценарий.
 */
interface AiResponseLogger {
    fun log(
        source: AiResponseSource,
        requestedModelId: String,
        response: AiApiChatResponse,
    )

    /**
     * Диагностика неуспешного запроса. Реализация по умолчанию сохраняет совместимость тестовых
     * no-op логгеров; production binding пишет только в debug Logcat.
     */
    fun logFailure(
        source: AiResponseSource,
        requestedModelId: String,
        stage: String,
        httpCode: Int? = null,
        responseBody: String? = null,
        throwable: Throwable? = null,
    ) = Unit
}

/**
 * Production logger is intentionally a no-op. A model response, error body or throwable text may
 * carry health data; even debug Logcat is not an approved storage location for it.
 */
@Singleton
class DebugAiResponseLogger @Inject constructor() : AiResponseLogger {

    override fun log(
        source: AiResponseSource,
        requestedModelId: String,
        response: AiApiChatResponse,
    ) = Unit

    override fun logFailure(
        source: AiResponseSource,
        requestedModelId: String,
        stage: String,
        httpCode: Int?,
        responseBody: String?,
        throwable: Throwable?,
    ) = Unit
}
