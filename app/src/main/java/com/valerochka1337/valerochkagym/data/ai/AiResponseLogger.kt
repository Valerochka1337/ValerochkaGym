package com.valerochka1337.valerochkagym.data.ai

import android.util.Log
import com.valerochka1337.valerochkagym.BuildConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Источник ответа OpenRouter — помогает отделить создание упражнения от распознавания InBody. */
enum class AiResponseSource(val label: String) {
    EXERCISE("exercise"),
    INBODY("inbody"),
}

/**
 * Отладочный вывод уже полученного ответа OpenRouter. В него намеренно не передаются запрос,
 * API key и изображение InBody. Реализация должна быть best-effort и никогда не ломать сценарий.
 */
interface AiResponseLogger {
    fun log(
        source: AiResponseSource,
        requestedModelId: String,
        response: OpenRouterChatResponse,
    )
}

/**
 * Пишет ответ только в debug Logcat. Ответ InBody может содержать чувствительные показатели,
 * поэтому в release эта ветка не выполняется и ничего не сохраняется на устройстве.
 */
@Singleton
class DebugAiResponseLogger @Inject constructor(
    private val json: Json,
) : AiResponseLogger {

    override fun log(
        source: AiResponseSource,
        requestedModelId: String,
        response: OpenRouterChatResponse,
    ) {
        if (!BuildConfig.DEBUG) return
        val responseJson = runCatching { json.encodeToString(response) }.getOrNull() ?: return
        val chunks = responseJson.chunked(MAX_LOG_CHUNK_LENGTH)
        chunks.forEachIndexed { index, chunk ->
            Log.d(
                TAG,
                "${source.label} model=$requestedModelId response ${index + 1}/${chunks.size}: $chunk",
            )
        }
    }

    private companion object {
        const val TAG = "GymAiResponse"
        const val MAX_LOG_CHUNK_LENGTH = 3_000
    }
}
