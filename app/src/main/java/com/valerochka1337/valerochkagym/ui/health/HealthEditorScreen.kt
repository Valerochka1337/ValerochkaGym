package com.valerochka1337.valerochkagym.ui.health

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.domain.*
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics

@Composable
fun HealthEditorScreen(
    kind: HealthRecordKind,
    logicalId: String? = null,
    onBack: () -> Unit,
    viewModel: HealthViewModel = hiltViewModel(),
) {
  LaunchedEffect(kind, logicalId) {
    if (logicalId == null) viewModel.openCreate(kind) else viewModel.openEdit(logicalId)
  }
  val snapshot by viewModel.editor.collectAsStateWithLifecycle()
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val status by viewModel.editorState.collectAsStateWithLifecycle()
  val haptics = gymHaptics()
  LaunchedEffect(status.finished) { if (status.finished) onBack() }
  HealthEditorContent(
      snapshot,
      status,
      state,
      onBack = {
        viewModel.closeEditor()
        onBack()
      },
      onChange = viewModel::updateDraft,
      onCreateMetric = viewModel::createMetric,
      onAcknowledge = viewModel::acknowledgeStorageNotice,
      onConfirm = {
        haptics.confirm()
        viewModel.confirm()
      },
      onDelete = {
        haptics.confirm()
        viewModel.tombstone()
      },
      onRetry = {
        if (logicalId == null) viewModel.openCreate(kind) else viewModel.openEdit(logicalId)
      },
  )
}

@Composable
internal fun HealthEditorContent(
    snapshot: HealthEditorSnapshot?,
    status: HealthEditorState,
    state: HealthUiState,
    onBack: () -> Unit,
    onChange: (HealthEditorDraft) -> Unit,
    onCreateMetric: (String) -> Unit,
    onAcknowledge: () -> Unit,
    onConfirm: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
  var deleting by remember(snapshot?.target) { mutableStateOf(false) }
  HealthPage("Запись здоровья", onBack) {
    if (status.loading) Text("Загружаем редактор…")
    status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (snapshot == null) {
      if (!status.loading && !status.finished)
          PillButton("Повторить", onRetry, modifier = Modifier.fillMaxWidth())
      return@HealthPage
    }
    if (status.requiresAcknowledgement || state.consent.localStorageAcknowledgedVersion == 0) {
      Text(
          "Подтвердите локальное хранение записей здоровья на этом устройстве. Это не разрешает отправку записей на сервер или обработку AI."
      )
      PillButton("Подтвердить хранение", onAcknowledge, modifier = Modifier.fillMaxWidth())
    }
    val enabled = !status.saving
    when (val draft = snapshot.draft) {
      is HealthEditorDraft.Report -> {
        HealthField("Название отчёта", draft.title, enabled) { onChange(draft.copy(title = it)) }
        HealthField("Исходный текст", draft.sourceText.orEmpty(), enabled) {
          onChange(draft.copy(sourceText = it.emptyToNull()))
        }
        HealthField("Дата или дата-время", draft.observedAt, enabled) {
          onChange(draft.copy(observedAt = it))
        }
        HealthPrecision(draft.observedPrecision, enabled) {
          onChange(draft.copy(observedPrecision = it))
        }
      }
      is HealthEditorDraft.Restriction ->
          HealthField("Ограничение", draft.textOriginal, enabled) {
            onChange(draft.copy(textOriginal = it))
          }
      is HealthEditorDraft.Observation ->
          ObservationFields(
              draft,
              state.records,
              state.metrics,
              enabled,
              { onChange(it) },
              onCreateMetric,
          )
    }
    PillButton(
        if (status.saving) "Сохраняем…" else "Подтвердить запись",
        onConfirm,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    if (snapshot.target.logicalId != null) {
      PillButton(
          "Удалить запись",
          { deleting = true },
          enabled = enabled,
          modifier = Modifier.fillMaxWidth(),
      )
      if (deleting) {
        Text("Запись исчезнет из текущих показателей. Все её версии останутся в истории.")
        PillButton(
            "Подтвердить удаление",
            {
              deleting = false
              onDelete()
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        PillButton("Оставить запись", { deleting = false }, modifier = Modifier.fillMaxWidth())
      }
    }
  }
}

@Composable
private fun ObservationFields(
    draft: HealthEditorDraft.Observation,
    records: List<HealthCurrentRecord>,
    metrics: List<HealthMetricIdentity>,
    enabled: Boolean,
    change: (HealthEditorDraft.Observation) -> Unit,
    create: (String) -> Unit,
) {
  var newMetric by remember(draft.reportLogicalId) { mutableStateOf("") }
  Text("Отчёт", style = MaterialTheme.typography.titleMedium)
  val reports = records.filter { it.kind == HealthRecordKind.REPORT && !it.deleted }
  if (reports.isEmpty()) Text("Сначала добавьте отчёт, затем вернитесь к наблюдению.")
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    reports.forEach { record ->
      HealthChoice(
          healthRecordLabel(record.payload),
          draft.reportLogicalId == record.logicalId,
          enabled,
      ) {
        change(draft.copy(reportLogicalId = record.logicalId))
      }
    }
  }
  Text("Метрика", style = MaterialTheme.typography.titleMedium)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    metrics.forEach { metric ->
      HealthChoice(metric.nameOriginal, draft.metricIdentityId == metric.id, enabled) {
        change(draft.copy(metricIdentityId = metric.id, metricNameOriginal = metric.nameOriginal))
      }
    }
  }
  HealthField("Новая метрика", newMetric, enabled) { newMetric = it }
  PillButton(
      "Создать метрику",
      { create(newMetric) },
      enabled = enabled,
      modifier = Modifier.fillMaxWidth(),
  )
  HealthField("Исходное название показателя", draft.metricNameOriginal, enabled) {
    change(draft.copy(metricNameOriginal = it))
  }
  Text("Тип значения")
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    HealthValueKind.entries.forEach { kind ->
      HealthChoice(valueKindLabel(kind), draft.valueKind == kind, enabled) {
        change(draft.withValueKind(kind))
      }
    }
  }
  HealthField("Исходное значение", draft.valueOriginal, enabled) {
    change(draft.copy(valueOriginal = it))
  }
  if (draft.valueKind == HealthValueKind.NUMBER || draft.valueKind == HealthValueKind.COMPARATOR) {
    HealthField("Числовое значение", draft.numberValue.orEmpty(), enabled) {
      change(draft.copy(numberValue = it.emptyToNull()))
    }
  }
  if (draft.valueKind == HealthValueKind.COMPARATOR) {
    Text("Оператор сравнения")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      HealthOperator.entries.forEach { operator ->
        HealthChoice(operatorLabel(operator), draft.operator == operator, enabled) {
          change(draft.copy(operator = operator))
        }
      }
    }
  }
  if (draft.valueKind == HealthValueKind.RANGE) {
    HealthField("Нижняя граница", draft.rangeLow.orEmpty(), enabled) {
      change(draft.copy(rangeLow = it.emptyToNull()))
    }
    HealthField("Верхняя граница", draft.rangeHigh.orEmpty(), enabled) {
      change(draft.copy(rangeHigh = it.emptyToNull()))
    }
  }
  HealthField("Единица", draft.unitOriginal.orEmpty(), enabled) {
    change(draft.copy(unitOriginal = it.emptyToNull()))
  }
  HealthField("Метод", draft.methodOriginal.orEmpty(), enabled) {
    change(draft.copy(methodOriginal = it.emptyToNull()))
  }
  HealthField("Образец", draft.specimenOriginal.orEmpty(), enabled) {
    change(draft.copy(specimenOriginal = it.emptyToNull()))
  }
  HealthField("Источник", draft.sourceOriginal.orEmpty(), enabled) {
    change(draft.copy(sourceOriginal = it.emptyToNull()))
  }
  HealthField("Референс из отчёта", draft.referenceOriginal.orEmpty(), enabled) {
    change(draft.copy(referenceOriginal = it.emptyToNull()))
  }
  HealthField("Дата или дата-время", draft.observedAt, enabled) {
    change(draft.copy(observedAt = it))
  }
  HealthPrecision(draft.observedPrecision, enabled) { change(draft.copy(observedPrecision = it)) }
}

internal fun HealthEditorDraft.Observation.withValueKind(kind: HealthValueKind) =
    copy(
        valueKind = kind,
        numberValue =
            numberValue.takeIf {
              kind == HealthValueKind.NUMBER || kind == HealthValueKind.COMPARATOR
            },
        rangeLow = rangeLow.takeIf { kind == HealthValueKind.RANGE },
        rangeHigh = rangeHigh.takeIf { kind == HealthValueKind.RANGE },
        operator = if (kind == HealthValueKind.COMPARATOR) operator ?: HealthOperator.LT else null,
    )

private fun String.emptyToNull() = takeIf { it.isNotEmpty() }

@Composable
private fun HealthField(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
  OutlinedTextField(
      value,
      change,
      Modifier.fillMaxWidth(),
      enabled = enabled,
      label = { Text(label) },
  )
}

@Composable
private fun HealthPrecision(
    precision: HealthObservedPrecision,
    enabled: Boolean,
    change: (HealthObservedPrecision) -> Unit,
) {
  Text(
      "Для даты: ГГГГ-ММ-ДД. Для времени: ГГГГ-ММ-ДДTчч:мм:сс+03:00 (со своим смещением).",
      style = MaterialTheme.typography.bodySmall,
  )
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    HealthObservedPrecision.entries.forEach { item ->
      HealthChoice(
          if (item == HealthObservedPrecision.DATE) "Только дата" else "Дата и время",
          precision == item,
          enabled,
      ) {
        change(item)
      }
    }
  }
}

private fun valueKindLabel(kind: HealthValueKind) =
    when (kind) {
      HealthValueKind.NUMBER -> "Число"
      HealthValueKind.COMPARATOR -> "Сравнение"
      HealthValueKind.RANGE -> "Диапазон"
      HealthValueKind.CATEGORY -> "Категория"
      HealthValueKind.TEXT -> "Текст"
    }

private fun operatorLabel(operator: HealthOperator) =
    when (operator) {
      HealthOperator.LT -> "Меньше"
      HealthOperator.LE -> "Не больше"
      HealthOperator.GT -> "Больше"
      HealthOperator.GE -> "Не меньше"
      HealthOperator.EQ -> "Равно"
    }
