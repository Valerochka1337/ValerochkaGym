package com.valerochka1337.valerochkagym.ui.calendarai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.ai.*
import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.trainingproposal.*
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

@HiltViewModel
class WorkoutPreparationViewModel
@Inject
constructor(private val repository: WorkoutPreparationRepository) : ViewModel() {
  val current =
      repository.current.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  suspend fun refreshWhileVisible() {
    // Bounded foreground refresh. Background uses WorkManager's exponential backoff.
    repeat(12) {
      if (!repository.step()) return
      delay(10_000)
    }
  }
}

@Composable
fun WorkoutPreparationCard(
    onPrepare: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: WorkoutPreparationViewModel = hiltViewModel(),
) {
  val row by viewModel.current.collectAsStateWithLifecycle()
  LaunchedEffect(row?.requestId) { viewModel.refreshWhileVisible() }
  WorkoutPreparationCardContent(row, onPrepare, onOpen)
}

@Composable
internal fun WorkoutPreparationCardContent(
    row: PreparationEntity?,
    onPrepare: () -> Unit,
    onOpen: (String) -> Unit,
) {
  val haptics = gymHaptics()
  val intent =
      row?.let {
        runCatching { ProposalWire.json.decodeFromString<CalendarAiIntent>(it.intentJson) }
            .getOrNull()
      }
  val proposal =
      row?.proposalJson?.let {
        runCatching { ProposalWire.json.decodeFromString<TrainingProposal>(it) }.getOrNull()
      }
  val date =
      intent
          ?.let {
            Instant.ofEpochMilli(it.startsAtMillis)
                .atZone(ZoneId.of(it.timeZoneId))
                .format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
          }
          .orEmpty()
  val status =
      when (row?.state) {
        null -> "Выберите дату и условия следующей тренировки"
        "WAITING" -> "Ожидает отправки и синхронизации. При подключении расчёт начнётся в фоне"
        "QUEUED",
        "RUNNING" -> "Готовим предложение на $date"
        "PAUSED_WAITING",
        "PAUSED_STATUS" -> "Не удалось завершить обмен с сервером. Повторите попытку"
        "READY" ->
            "На $date · ${proposal?.snapshot?.draft?.exercises?.size ?: 0} упр. · ${intent?.availableDurationMinutes} мин"
        "STALE",
        "SUPERSEDED" -> "Предложение требует обновления"
        "EXPIRED" -> "Выбранная дата прошла. Выберите новую дату"
        else ->
            calendarAiErrorMessage(BackendException(400, row.errorCode ?: "ai_unknown_error", ""))
      }
  GymCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Text("Следующая тренировка", style = MaterialTheme.typography.titleLarge)
    Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (proposal != null && row?.state != "READY") {
      Text(
          if (row?.state in WorkoutPreparationRepository.activeStates)
              "Готовим новый вариант. Предыдущий доступен для просмотра"
          else "Предыдущий вариант требует повторной проверки"
      )
    }
    if (proposal != null)
        TextButton(
            onClick = {
              haptics.tap()
              onOpen(proposal.proposalId)
            }
        ) {
          Text("Посмотреть")
        }
    TextButton(
        onClick = {
          haptics.tap()
          onPrepare()
        }
    ) {
      Text(
          when (row?.state) {
            null -> "Подготовить тренировку"
            "WAITING",
            "QUEUED",
            "RUNNING" -> "Изменить условия"
            "PAUSED_WAITING",
            "PAUSED_STATUS",
            "FAILED" -> "Повторить"
            else -> "Пересчитать"
          }
      )
    }
  }
}
