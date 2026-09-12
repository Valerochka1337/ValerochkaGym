package com.valerochka1337.valerochkagym.ui.coach

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.CoachDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.domain.CoachReply
import com.valerochka1337.valerochkagym.service.CoachConversationService
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class CoachChatViewModel
@Inject
constructor(
    private val savedStateHandle: SavedStateHandle,
    private val coachDao: CoachDao,
    private val workoutDao: WorkoutDao,
    private val conversation: CoachConversationService,
    private val coachAlertNotifier: com.valerochka1337.valerochkagym.service.CoachAlertNotifier,
) : ViewModel() {
  private val workoutId = requireNotNull(savedStateHandle.get<String>(GymRoutes.WORKOUT_ID_ARG))
  private val draft = MutableStateFlow(savedStateHandle.get<String>(DRAFT) ?: "")
  private val status = MutableStateFlow<String?>(null)
  private val error = MutableStateFlow<String?>(null)
  private val busyAction = MutableStateFlow(false)

  private val persisted =
      combine(
          coachDao.observeMessages(workoutId),
          coachDao.observePendingProposal(workoutId),
          coachDao.observeContext(workoutId),
          draft,
      ) { messages, proposal, context, draftValue ->
        PersistedChat(
            messages.map {
              CoachChatMessage(
                  it.id,
                  it.role,
                  if (it.text.startsWith("Не удалось обработать запрос тренера."))
                      "Не удалось обработать запрос"
                  else it.text,
                  it.status.toUiStatus(),
                  it.quickRepliesJson?.let(CoachReply::decodeQuickReplies),
                  failed =
                      it.role == "assistant" &&
                          (it.status == "ERROR" ||
                              it.text.startsWith("Не удалось обработать запрос тренера.")),
              )
            },
            proposal?.let {
              CoachChatProposal(
                  it.id,
                  it.beforeSummary,
                  it.afterSummary,
                  com.valerochka1337.valerochkagym.domain.WorkoutApprovalPreview.decode(
                      it.previewJson
                  ),
              )
            },
            context,
            draftValue,
        )
      }
  private val transient =
      combine(status, error, busyAction) { statusValue, errorValue, actionBusy ->
        TransientChat(statusValue, errorValue, actionBusy)
      }

  val uiState: StateFlow<CoachChatUiState> =
      combine(
              persisted,
              transient,
              conversation.runningWorkouts,
              conversation.runningStages,
              workoutDao.observeWorkout(workoutId),
          ) { persistedValue, transientValue, running, stages, workout ->
            CoachChatUiState(
                workoutName = workout?.name ?: "Тренировка",
                messages = persistedValue.messages,
                proposal = persistedValue.proposal,
                draft = persistedValue.draft,
                busy = transientValue.actionBusy || workoutId in running,
                status = transientValue.status ?: stages[workoutId],
                error = transientValue.error,
                readOnly = workout == null || workout.finishedAt != null,
                initiativeEnabled = persistedValue.context?.initiativeEnabled ?: true,
                canUndo =
                    workout?.finishedAt == null && persistedValue.context?.lastUndoRevision != null,
            )
          }
          .stateIn(
              viewModelScope,
              SharingStarted.WhileSubscribed(5_000),
              CoachChatUiState(readOnly = true),
          )

  fun changeDraft(value: String) {
    draft.value = value
    savedStateHandle[DRAFT] = value
  }

  fun send(text: String) {
    if (text.isBlank() || busyAction.value) return
    busyAction.value = true
    error.value = null
    status.value = "Отправляем сообщение тренеру…"
    viewModelScope.launch {
      try {
        if (conversation.send(workoutId, text)) {
          if (draft.value.trim() == text.trim()) changeDraft("")
          status.value = null
        } else {
          error.value = "Тренер доступен только в активной тренировке с подключённым аккаунтом."
        }
      } catch (_: Exception) {
        error.value = "Не удалось отправить сообщение тренеру. Попробуйте ещё раз."
      } finally {
        busyAction.value = false
      }
    }
  }

  fun retry(errorMessageId: String) = action { conversation.retry(workoutId, errorMessageId) }

  fun confirm(id: String) = action { conversation.confirm(workoutId, id) }

  fun cancel(id: String) = action { conversation.cancel(workoutId, id) }

  fun undo() = action { conversation.undo(workoutId) }

  fun disableInitiative() = action { conversation.disableInitiative(workoutId) }

  /** Called by the visible chat host, including when a reply arrives while it is open. */
  fun markAssistantMessagesRead() {
    coachAlertNotifier.chatViewed(workoutId)
    viewModelScope.launch { coachDao.markAssistantMessagesRead(workoutId) }
  }

  private fun action(block: suspend () -> Boolean) {
    if (busyAction.value) return
    busyAction.value = true
    error.value = null
    viewModelScope.launch {
      try {
        if (!block()) error.value = "Действие больше недоступно. Обновите состояние тренировки."
      } catch (_: Exception) {
        error.value = "Не удалось выполнить действие. Попробуйте ещё раз."
      } finally {
        busyAction.value = false
      }
    }
  }

  private data class PersistedChat(
      val messages: List<CoachChatMessage>,
      val proposal: CoachChatProposal?,
      val context: com.valerochka1337.valerochkagym.data.db.entity.CoachSessionContextEntity?,
      val draft: String,
  )

  private data class TransientChat(val status: String?, val error: String?, val actionBusy: Boolean)

  private fun String.toUiStatus(): String? =
      when (this) {
        "PENDING",
        "PROCESSING" -> "Обрабатывается"
        "INTERRUPTED" -> "Запрос прерван"
        else -> null
      }

  private companion object {
    const val DRAFT = "coach_draft"
  }
}
