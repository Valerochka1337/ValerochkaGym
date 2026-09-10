package com.valerochka1337.valerochkagym.ui.calendarai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.profile.AiProfilePromptDialog

@Composable
fun CalendarAiScreen(
    onBack: () -> Unit,
    onOpenProposal: (String) -> Unit,
    onOpenProfile: () -> Unit,
    viewModel: CalendarAiViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val haptics = gymHaptics()
  LaunchedEffect(viewModel, onOpenProposal) { viewModel.openProposal.collect(onOpenProposal) }
  LaunchedEffect(viewModel, onOpenProfile) { viewModel.openProfile.collect { onOpenProfile() } }
  CalendarAiContent(
      state = state,
      onBack = onBack,
      onDate = viewModel::setDate,
      onTime = viewModel::setTime,
      onZone = viewModel::setTimeZone,
      onGym = viewModel::toggleGym,
      onExcludedExercise = viewModel::toggleExcludedExercise,
      onExcludedEquipment = viewModel::toggleExcludedEquipment,
      onPriorityMuscle = viewModel::togglePriorityMuscle,
      onIncludeNotes = viewModel::setIncludeNotes,
      onDuration = viewModel::setDuration,
      onCurrentState = viewModel::setCurrentState,
      onPreferences = viewModel::setPreferences,
      onGenerate = {
        haptics.confirm()
        viewModel.generate()
      },
      onPromptVisible = viewModel::acknowledgeProfilePrompt,
      onPromptFill = viewModel::fillProfileFromPrompt,
      onPromptContinue = { viewModel.continueAfterProfilePrompt(it) },
      onPromptDisable = { viewModel.continueAfterProfilePrompt(it, disableFuturePrompts = true) },
      onPromptDismiss = viewModel::dismissProfilePrompt,
  )
}

@Composable
internal fun CalendarAiContent(
    state: CalendarAiUiState,
    onBack: () -> Unit,
    onDate: (String) -> Unit,
    onTime: (String) -> Unit,
    onZone: (String) -> Unit,
    onGym: (String) -> Unit,
    onExcludedExercise: (String) -> Unit,
    onExcludedEquipment: (String) -> Unit,
    onPriorityMuscle: (String) -> Unit,
    onIncludeNotes: (Boolean) -> Unit,
    onDuration: (String) -> Unit,
    onCurrentState: (String) -> Unit,
    onPreferences: (String) -> Unit,
    onGenerate: () -> Unit,
    onPromptVisible: (String) -> Unit,
    onPromptFill: (String) -> Unit,
    onPromptContinue: (String) -> Unit,
    onPromptDisable: (String) -> Unit,
    onPromptDismiss: (String) -> Unit,
) {
  GlowBackground {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val horizontalPadding = if (maxWidth < 600.dp) 16.dp else 24.dp
      Column(
          modifier =
              Modifier.fillMaxSize()
                  .widthIn(max = 840.dp)
                  .verticalScroll(rememberScrollState())
                  .padding(horizontal = horizontalPadding, vertical = 16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
          }
          Text("AI-план в календарь", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            "AI подготовит одно предложение. Программа и план появятся только после вашего утверждения.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CalendarAiTimeCard(state.form, onDate, onTime, onZone, onDuration)
        ChoiceCard("Залы", state.gyms, state.form.gymIds, onGym, "Доступных залов нет.")
        ChoiceCard(
            "Исключить упражнения",
            state.exercises,
            state.form.excludedExerciseIds,
            onExcludedExercise,
            "Упражнения загрузятся после синхронизации.",
        )
        ChoiceCard(
            "Исключить оборудование",
            state.equipment,
            state.form.excludedEquipmentIds,
            onExcludedEquipment,
            "Оборудование не найдено.",
        )
        ChoiceCard(
            "Приоритетные мышцы",
            state.muscles,
            state.form.priorityMuscles,
            onPriorityMuscle,
            null,
        )
        GymCard(modifier = Modifier.fillMaxWidth()) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Использовать заметки", style = MaterialTheme.typography.titleMedium)
              Text(
                  "Заметки завершённых тренировок и личные подсказки помогут составить запрос.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
                checked = state.form.includeNotes,
                onCheckedChange = onIncludeNotes,
                modifier = Modifier.semantics { contentDescription = "Использовать заметки" },
            )
          }
        }
        GymCard(modifier = Modifier.fillMaxWidth()) {
          Text("Дополнительно", style = MaterialTheme.typography.titleMedium)
          OutlinedTextField(
              value = state.form.currentState,
              onValueChange = onCurrentState,
              modifier = Modifier.fillMaxWidth(),
              label = { Text("Текущее состояние (необязательно)") },
              minLines = 2,
          )
          OutlinedTextField(
              value = state.form.preferences,
              onValueChange = onPreferences,
              modifier = Modifier.fillMaxWidth(),
              label = { Text("Предпочтения (необязательно)") },
              minLines = 2,
          )
        }
        state.error?.let { message ->
          Text(
              message,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.semantics { contentDescription = "Ошибка: $message" },
          )
        }
        PillButton(
            text = if (state.generating) "Готовим предложение…" else "Создать предложение",
            onClick = onGenerate,
            enabled = !state.generating,
            modifier =
                Modifier.fillMaxWidth().semantics { contentDescription = "Создать AI-предложение" },
        )
      }
    }
  }
  state.profilePrompt?.let { prompt ->
    AiProfilePromptDialog(
        token = prompt.token,
        onVisible = onPromptVisible,
        onFillProfile = onPromptFill,
        onContinue = onPromptContinue,
        onDisable = onPromptDisable,
        onDismiss = onPromptDismiss,
    )
  }
}

@Composable
private fun CalendarAiTimeCard(
    form: CalendarAiForm,
    onDate: (String) -> Unit,
    onTime: (String) -> Unit,
    onZone: (String) -> Unit,
    onDuration: (String) -> Unit,
) {
  GymCard(modifier = Modifier.fillMaxWidth()) {
    Text("Когда тренироваться", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = form.date,
        onValueChange = onDate,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Дата (ГГГГ-ММ-ДД)") },
        singleLine = true,
    )
    OutlinedTextField(
        value = form.time,
        onValueChange = onTime,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Время (ЧЧ:ММ)") },
        singleLine = true,
    )
    OutlinedTextField(
        value = form.timeZoneId,
        onValueChange = onZone,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Часовой пояс") },
        singleLine = true,
    )
    OutlinedTextField(
        value = form.availableDurationMinutes,
        onValueChange = onDuration,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Доступное время, мин") },
        singleLine = true,
    )
  }
}

@Composable
private fun ChoiceCard(
    title: String,
    choices: List<CalendarAiChoice>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    emptyMessage: String?,
) {
  GymCard(modifier = Modifier.fillMaxWidth()) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (choices.isEmpty()) {
      if (emptyMessage != null)
          Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
      FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        choices.forEach { choice ->
          FilterChip(
              selected = choice.id in selected,
              onClick = { onToggle(choice.id) },
              label = { Text(choice.label) },
              modifier = Modifier.heightIn(min = 48.dp),
          )
        }
      }
    }
  }
}
