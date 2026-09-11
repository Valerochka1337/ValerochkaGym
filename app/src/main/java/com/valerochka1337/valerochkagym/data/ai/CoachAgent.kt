package com.valerochka1337.valerochkagym.data.ai

import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.domain.WorkoutSnapshot
import java.io.IOException
import java.io.InterruptedIOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException

enum class CoachRunStatus {
  ANSWER,
  APPLIED,
  PROPOSAL,
  LIMIT,
  ERROR,
}

data class CoachRunResult(
    val text: String,
    val requestCount: Int,
    val toolCount: Int,
    val status: CoachRunStatus = CoachRunStatus.ANSWER,
)

/** Only visible conversation text; imported system/tool messages never enter the model history. */
data class CoachHistoryMessage(val role: String, val text: String) {
  init {
    require(role == "user" || role == "assistant")
  }
}

/** The application, never model arguments, decides whether an operation ends the turn. */
enum class CoachToolOutcomeKind {
  STANDARD,
  RECOVERY,
}

data class CoachToolOutcome(
    val content: String,
    val terminal: CoachRunStatus? = null,
    val kind: CoachToolOutcomeKind = CoachToolOutcomeKind.STANDARD,
) {
  init {
    require(
        terminal == null ||
            terminal in setOf(CoachRunStatus.APPLIED, CoachRunStatus.PROPOSAL, CoachRunStatus.ERROR)
    )
    require(kind != CoachToolOutcomeKind.RECOVERY || terminal == null)
  }
}

/** Network loop has no DAO, command authority, or service lifetime of its own. */
@Singleton
class CoachAgent
@Inject
constructor(
    private val gateway: CoachModelGateway,
) {
  suspend fun reply(
      snapshot: WorkoutSnapshot,
      userText: String,
      tools: List<AiApiTool>,
      history: List<CoachHistoryMessage> = emptyList(),
      expectedSessionEpoch: Long? = null,
      dispatch: suspend (AiApiToolCall) -> CoachToolOutcome,
  ): CoachRunResult {
    var requests = 0
    var calls = 0
    fun result(text: String, status: CoachRunStatus = CoachRunStatus.ERROR) =
        CoachRunResult(text, requests, calls, status)
    if (userText.isBlank() || userText.length > MAX_MESSAGE_CHARS) {
      return result("Напишите сообщение длиной до $MAX_MESSAGE_CHARS символов.")
    }
    return try {
      withTimeout(TOTAL_MILLIS) {
        val names = tools.map { it.function.name }.toSet()
        require(names.size == tools.size && tools.all { it.type == "function" })
        val messages = mutableListOf(AiApiMessage.text("system", coachSystemPrompt))
        messages +=
            AiApiMessage.text(
                "system",
                "Текущая тренировка: ${snapshot.workoutId}; ревизия при отправке: ${snapshot.revision}. Сведения и закреплённые ссылки получай через get_workout_state.",
            )
        history.takeLast(MAX_HISTORY_MESSAGES).forEach {
          messages += AiApiMessage.text(it.role, it.text.take(MAX_MESSAGE_CHARS))
        }
        messages += AiApiMessage.text("user", userText)
        val seenIds = mutableSetOf<String>()
        var recoveryUsed = false
        for (request in 1..MAX_REQUESTS) {
          currentCoroutineContext().ensureActive()
          requests = request
          val response =
              withTimeout(PER_REQUEST_MILLIS) {
                gateway.complete(snapshot.accountId, expectedSessionEpoch, messages.toList(), tools)
              }
          currentCoroutineContext().ensureActive()
          val choice = response.choices.firstOrNull()
          val error = response.error ?: choice?.error
          if (error != null) return@withTimeout result(providerFailure(error.httpCode))
          val message =
              choice?.message
                  ?: return@withTimeout result("Модель не вернула ответ. Повторите запрос.")
          if (choice.finishReason in setOf("length", "content_filter")) {
            return@withTimeout result("Ответ модели не завершён. Попробуйте уточнить запрос.")
          }
          if (message.toolCalls.isEmpty()) {
            val text = (message.content as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            if (text.isNullOrEmpty())
                return@withTimeout result(
                    "Модель вернула пустой ответ. Проверьте выбранную модель."
                )
            if (text.length > MAX_ANSWER_CHARS)
                return@withTimeout result("Ответ модели слишком длинный. Уточните запрос.")
            return@withTimeout result(text, CoachRunStatus.ANSWER)
          }
          // Preflight the entire response before dispatching anything, including a mutation.
          val incoming = message.toolCalls
          if (incoming.size > MAX_TOOLS - calls) {
            return@withTimeout result(
                "Достигнут предел действий за один запрос. Уточните запрос.",
                CoachRunStatus.LIMIT,
            )
          }
          if (
              incoming.any {
                it.type != "function" ||
                    it.id.isBlank() ||
                    it.id.length > 200 ||
                    it.function.name !in names ||
                    it.function.arguments.length > MAX_ARGUMENT_CHARS
              } ||
                  incoming.map { it.id }.distinct().size != incoming.size ||
                  incoming.any { it.id in seenIds } ||
                  incoming.count { it.function.name == MUTATION_TOOL } > 1
          ) {
            return@withTimeout result(
                "Модель вернула некорректные действия. Проверьте поддержку инструментов выбранной моделью."
            )
          }
          seenIds += incoming.map { it.id }
          messages += AiApiMessage("assistant", message.content ?: JsonNull, incoming)
          for (call in incoming) {
            currentCoroutineContext().ensureActive()
            calls++
            val outcome = dispatch(call)
            currentCoroutineContext().ensureActive()
            // No further call, including already returned calls, is executed after this result.
            if (outcome.terminal != null)
                return@withTimeout result(outcome.content, outcome.terminal)
            if (outcome.kind == CoachToolOutcomeKind.RECOVERY) {
              if (call.function.name != MUTATION_TOOL || recoveryUsed) {
                return@withTimeout result(
                    "Инструмент не вернул корректный результат изменения. Проверьте состояние тренировки."
                )
              }
              recoveryUsed = true
            }
            if (call.function.name == MUTATION_TOOL) {
              if (outcome.kind != CoachToolOutcomeKind.RECOVERY)
                  return@withTimeout result(
                      "Инструмент не вернул подтверждение изменения. Проверьте состояние тренировки."
                  )
            }
            if (outcome.content.length > MAX_TOOL_RESULT_CHARS)
                return@withTimeout result("Контекст слишком большой. Уточните упражнение.")
            messages += AiApiMessage("tool", JsonPrimitive(outcome.content), toolCallId = call.id)
          }
        }
        result("Достигнут предел запросов к модели. Уточните запрос.", CoachRunStatus.LIMIT)
      }
    } catch (_: TimeoutCancellationException) {
      currentCoroutineContext().ensureActive()
      result(
          "Модель не успела ответить. Повторите запрос; уже сохранённые действия остаются в истории."
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: BackendException) {
      result(
          when {
            e.code == "coach_unconfigured" ->
                "Тренер пока не настроен на сервере. Проверьте его доступность в настройках."
            e.status == 401 || e.status == 403 ->
                "Сессия недоступна. Войдите в аккаунт и повторите запрос."
            e.status == 404 ->
                "Сервер пока не поддерживает тренера. Попробуйте после его обновления."
            else -> providerFailure(e.status)
          }
      )
    } catch (e: HttpException) {
      result(providerFailure(e.code()))
    } catch (_: InterruptedIOException) {
      currentCoroutineContext().ensureActive()
      result(
          "Модель не успела ответить. Повторите запрос; уже сохранённые действия остаются в истории."
      )
    } catch (_: IOException) {
      result("Нет подключения к модели. Проверьте сеть и повторите запрос.")
    } catch (_: Exception) {
      result("Не удалось обработать ответ. Проверьте модель и повторите запрос.")
    }
  }

  companion object {
    const val MAX_REQUESTS = 6
    const val MAX_TOOLS = 16
    const val PER_REQUEST_MILLIS = 60_000L
    const val TOTAL_MILLIS = 180_000L
    const val MAX_MESSAGE_CHARS = 4000
    private const val MAX_HISTORY_MESSAGES = 30
    private const val MAX_ANSWER_CHARS = 16_000
    private const val MAX_ARGUMENT_CHARS = 32_000
    private const val MAX_TOOL_RESULT_CHARS = 96_000
    private const val MUTATION_TOOL = "submit_workout_changes"
  }
}

private fun providerFailure(code: Int?): String =
    when (code) {
      401,
      403 -> "Сервер не получил доступ к модели. Проверьте доступность тренера в настройках."
      400,
      404,
      422 -> "Модель не принимает этот запрос. Выберите модель с поддержкой инструментов."
      402 -> "Недостаточно средств у провайдера модели."
      429 -> "Слишком много запросов. Попробуйте немного позже."
      else -> "Сервер модели недоступен. Повторите запрос позже."
    }
