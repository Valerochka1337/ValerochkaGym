package com.valerochka1337.valerochkagym.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.domain.ExperienceLevel
import com.valerochka1337.valerochkagym.domain.ProfileSex
import com.valerochka1337.valerochkagym.domain.TrainingGoal
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics

@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val haptics = gymHaptics()
  ProfileScreenContent(
      state = state,
      onBack = onBack,
      onGoal = viewModel::setGoal,
      onExperience = viewModel::setExperience,
      onSex = viewModel::setSex,
      onBirthDate = viewModel::setBirthDate,
      onSessions = viewModel::setSessions,
      onDuration = viewModel::setDuration,
      onConstraints = viewModel::setConstraints,
      onEquipment = viewModel::toggleEquipment,
      onPromptDisabled = viewModel::setPromptDisabled,
      onSave = {
        haptics.confirm()
        viewModel.save()
      },
      modifier = modifier,
  )
}

@Composable
internal fun ProfileScreenContent(
    state: ProfileEditorUiState,
    onBack: () -> Unit,
    onGoal: (TrainingGoal?) -> Unit,
    onExperience: (ExperienceLevel?) -> Unit,
    onSex: (ProfileSex?) -> Unit,
    onBirthDate: (String) -> Unit,
    onSessions: (String) -> Unit,
    onDuration: (String) -> Unit,
    onConstraints: (String) -> Unit,
    onEquipment: (String) -> Unit,
    onPromptDisabled: (Boolean) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
  GlowBackground(modifier = modifier) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val horizontalPadding = if (maxWidth < 600.dp) 16.dp else 24.dp
      Column(modifier = Modifier.fillMaxSize().widthIn(max = 840.dp)) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = 8.dp, end = 24.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
          Text("Профиль", style = MaterialTheme.typography.headlineLarge)
        }
        if (state.isLoading) {
          Text(
              "Загружаем профиль…",
              modifier =
                  Modifier.padding(horizontal = horizontalPadding).semantics {
                    contentDescription = "Загружаем профиль"
                  },
          )
          return@Column
        }
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          ProfileChoiceCard(
              "Цель тренировок",
              TrainingGoal.entries,
              state.trainingGoal,
              ::goalLabel,
              onGoal,
          )
          ProfileChoiceCard(
              "Опыт",
              ExperienceLevel.entries,
              state.experienceLevel,
              ::experienceLabel,
              onExperience,
          )
          ProfileChoiceCard("Пол", ProfileSex.entries, state.sex, ::sexLabel, onSex)
          GymCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                "О себе",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                state.birthDate,
                onBirthDate,
                Modifier.fillMaxWidth(),
                label = { Text("Дата рождения (ГГГГ-ММ-ДД)") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                state.plannedSessionsPerWeek,
                onSessions,
                Modifier.fillMaxWidth(),
                label = { Text("Тренировок в неделю") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                state.preferredSessionDurationMinutes,
                onDuration,
                Modifier.fillMaxWidth(),
                label = { Text("Длительность тренировки, мин") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                state.manualConstraints,
                onConstraints,
                Modifier.fillMaxWidth(),
                label = { Text("Ограничения и предпочтения") },
                minLines = 3,
            )
          }
          if (LocalEquipmentCatalog.alphabeticalEntries.isNotEmpty())
              GymCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Оборудование",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  LocalEquipmentCatalog.alphabeticalEntries.forEach { equipment ->
                    FilterChip(
                        selected = equipment.id in state.equipmentIds,
                        onClick = { onEquipment(equipment.id) },
                        label = { Text(equipment.name) },
                    )
                  }
                }
              }
          GymCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text("Не предлагать профиль перед AI", style = MaterialTheme.typography.titleMedium)
                Text(
                    "AI-запросы останутся доступны",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                  checked = state.promptDisabled,
                  onCheckedChange = onPromptDisabled,
                  modifier =
                      Modifier.semantics { contentDescription = "Не предлагать профиль перед AI" },
              )
            }
          }
          state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = "Ошибка: $it" },
            )
          }
          PillButton(
              text = "Сохранить",
              onClick = onSave,
              enabled = !state.isSaving && state.target != null,
              modifier =
                  Modifier.fillMaxWidth().semantics { contentDescription = "Сохранить профиль" },
          )
        }
      }
    }
  }
}

@Composable
private fun <T> ProfileChoiceCard(
    title: String,
    entries: Iterable<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T?) -> Unit,
) {
  GymCard(modifier = Modifier.fillMaxWidth()) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      entries.forEach { entry ->
        FilterChip(
            selected = selected == entry,
            onClick = { onSelect(if (selected == entry) null else entry) },
            label = { Text(label(entry)) },
            modifier = Modifier.semantics { contentDescription = "$title: ${label(entry)}" },
        )
      }
    }
  }
}

private fun goalLabel(value: TrainingGoal) =
    when (value) {
      TrainingGoal.STRENGTH -> "Сила"
      TrainingGoal.MUSCLE_GAIN -> "Мышцы"
      TrainingGoal.FAT_LOSS -> "Снижение веса"
      TrainingGoal.GENERAL_FITNESS -> "Общая форма"
      TrainingGoal.ENDURANCE -> "Выносливость"
      TrainingGoal.OTHER -> "Другое"
    }

private fun experienceLabel(value: ExperienceLevel) =
    when (value) {
      ExperienceLevel.BEGINNER -> "Начинающий"
      ExperienceLevel.INTERMEDIATE -> "Средний"
      ExperienceLevel.ADVANCED -> "Продвинутый"
    }

private fun sexLabel(value: ProfileSex) =
    when (value) {
      ProfileSex.FEMALE -> "Женский"
      ProfileSex.MALE -> "Мужской"
      ProfileSex.PREFER_NOT_TO_SAY -> "Не указывать"
    }
