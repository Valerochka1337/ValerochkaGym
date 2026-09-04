package com.valerochka1337.valerochkagym.data.ai

import android.util.Log
import com.valerochka1337.valerochkagym.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/** Источник ответа модели — помогает отделить создание упражнения от распознавания InBody. */
enum class AiResponseSource(val label: String) {
  EXERCISE("exercise"),
  INBODY("inbody"),
}

/**
 * Отладочный вывод уже полученного ответа модели. В него намеренно не передаются запрос, API key и
 * изображение InBody. Реализация должна быть best-effort и никогда не ломать сценарий.
 */
interface AiResponseLogger {
  fun log(
      source: AiResponseSource,
      requestedModelId: String,
      response: AiApiChatResponse,
  )

  /**
   * Диагностика неуспешного запроса. Реализация по умолчанию сохраняет совместимость тестовых no-op
   * логгеров; production binding пишет только в debug Logcat.
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
 * Пишет ответ только в debug Logcat. Ответ InBody может содержать чувствительные показатели,
 * поэтому в release эта ветка не выполняется и ничего не сохраняется на устройстве.
 */
@Singleton
class DebugAiResponseLogger
@Inject
constructor(
    private val json: Json,
) : AiResponseLogger {

  override fun log(
      source: AiResponseSource,
      requestedModelId: String,
      response: AiApiChatResponse,
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

  override fun logFailure(
      source: AiResponseSource,
      requestedModelId: String,
      stage: String,
      httpCode: Int?,
      responseBody: String?,
      throwable: Throwable?,
  ) {
    if (!BuildConfig.DEBUG) return
    val prefix = buildString {
      append(source.label)
      append(" model=")
      append(requestedModelId)
      append(" failure stage=")
      append(stage)
      httpCode?.let {
        append(" http=")
        append(it)
      }
    }
    val boundedBody =
        responseBody?.take(MAX_ERROR_BODY_LENGTH)?.let { body ->
          if (responseBody.length > MAX_ERROR_BODY_LENGTH) "$body…[truncated]" else body
        }
    if (boundedBody.isNullOrEmpty()) {
      if (throwable == null) Log.e(TAG, "$prefix body=<empty>")
      else Log.e(TAG, "$prefix body=<empty>", throwable)
      return
    }

    val chunks = boundedBody.chunked(MAX_LOG_CHUNK_LENGTH)
    chunks.forEachIndexed { index, chunk ->
      val message = "$prefix body ${index + 1}/${chunks.size}: $chunk"
      if (throwable != null && index == chunks.lastIndex) Log.e(TAG, message, throwable)
      else Log.e(TAG, message)
    }
  }

  private companion object {
    const val TAG = "GymAiResponse"
    const val MAX_LOG_CHUNK_LENGTH = 3_000
    const val MAX_ERROR_BODY_LENGTH = 12_000
  }
}
