package com.valerochka1337.valerochkagym.ui.health

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.domain.*
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics

@Composable
fun HealthDetailScreen(
    logicalId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: HealthViewModel = hiltViewModel(),
) {
  LaunchedEffect(logicalId) { viewModel.openHistory(logicalId) }
  val history by viewModel.history.collectAsStateWithLifecycle()
  val loading by viewModel.historyLoading.collectAsStateWithLifecycle()
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val haptics = gymHaptics()
  HealthDetailContent(
      history,
      loading,
      state.error,
      onBack,
      onEdit = {
        haptics.tap()
        onEdit(logicalId)
      },
      onRetry = {
        viewModel.retry()
        viewModel.openHistory(logicalId)
      },
  )
}

@Composable
internal fun HealthDetailContent(
    history: HealthHistory?,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
) {
  HealthPage("История записи", onBack) {
    if (loading) Text("Загружаем историю…")
    else if (history == null) Text("Запись недоступна в текущем аккаунте")
    history?.let { value ->
      GymCard(Modifier.fillMaxWidth()) {
        if (value.current.deleted) Text("Запись удалена. История сохранена.")
        else HealthPayloadDetails(value.current.payload)
      }
      if (!value.current.deleted)
          PillButton("Исправить или удалить", onEdit, modifier = Modifier.fillMaxWidth())
      Text("Версии", style = MaterialTheme.typography.titleMedium)
      value.versions.forEachIndexed { index, version ->
        GymCard(Modifier.fillMaxWidth()) {
          Text(
              "Версия ${index + 1}: ${if (version.state == HealthVersionState.TOMBSTONE) "удаление" else "подтверждена"}"
          )
          Text("Внесено: ${java.time.Instant.ofEpochMilli(version.enteredAtEpochMs)}")
          HealthPayloadDetails(version.payload)
          Text(
              if (version.serverSequence == null) "Сохранено на устройстве"
              else "Подтверждено сервером"
          )
        }
      }
      if (value.headHistory.isNotEmpty()) {
        Text("Серверная история изменений", style = MaterialTheme.typography.titleMedium)
        value.headHistory.forEach { head ->
          val index = value.versions.indexOfFirst { it.versionId == head.currentVersionId }
          Text(
              "Изменение ${head.headRevision}: ${if (head.deleted) "удаление" else "выбрана версия ${index + 1}"}"
          )
        }
      }
    }
    error?.let {
      Text(it, color = MaterialTheme.colorScheme.error)
      PillButton("Повторить", onRetry, modifier = Modifier.fillMaxWidth())
    }
  }
}

@Composable
private fun HealthPayloadDetails(payload: HealthPayload?) {
  when (payload) {
    null -> Text("Удалённая версия")
    is HealthPayload.Restriction -> Text(payload.textOriginal)
    is HealthPayload.Report -> {
      Text(payload.title)
      payload.sourceText?.let { Text(it) }
      Text(payload.observedAt)
    }
    is HealthPayload.Observation -> {
      Text("${payload.metricNameOriginal}: ${payload.valueOriginal}")
      Text(payload.observedAt)
      payload.numberValue?.let { Text("Число: $it") }
      payload.operator?.let {
        Text(
            "Сравнение: ${when(it) { HealthOperator.LT -> "<"
 HealthOperator.LE -> "≤"
 HealthOperator.GT -> ">"
 HealthOperator.GE -> "≥"
 HealthOperator.EQ -> "=" }}"
        )
      }
      if (payload.rangeLow != null && payload.rangeHigh != null)
          Text("Диапазон: ${payload.rangeLow} — ${payload.rangeHigh}")
      listOf(
              "Единица" to payload.unitOriginal,
              "Метод" to payload.methodOriginal,
              "Образец" to payload.specimenOriginal,
              "Источник" to payload.sourceOriginal,
              "Референс из отчёта" to payload.referenceOriginal,
          )
          .forEach { (label, value) -> value?.let { Text("$label: $it") } }
    }
  }
}
