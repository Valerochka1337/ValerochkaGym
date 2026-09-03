package com.valerochka1337.valerochkagym.ui.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.domain.health.HealthRawValueKind
import com.valerochka1337.valerochkagym.ui.analysis.formatDate
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthEditorScreen(onBack: () -> Unit, viewModel: HealthEditorViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = gymHaptics()
    val zone = ZoneId.systemDefault()
    var reportDatePicker by remember { mutableStateOf(false) }
    var observationDateIndex by remember { mutableIntStateOf(-1) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { haptics.tap(); viewModel.selectDocument(it) }
    }
    LaunchedEffect(Unit) { viewModel.finished.collect { haptics.success(); onBack() } }

    state.disclosure?.let { disclosure -> AlertDialog(
        onDismissRequest = { haptics.reject(); viewModel.cancelDisclosure() },
        title = { Text(if (disclosure.loopbackWarning) "Подтвердите локальный HTTP" else "Отправить медицинский документ?") },
        text = { Text("Получатель: ${disclosure.host}\nМодель: ${disclosure.model}\nБудут переданы выбранный документ и поля исследования. Документ не сохраняется в Google Sheets.") },
        confirmButton = { TextButton(onClick = { haptics.confirm(); viewModel.confirmDisclosure() }) { Text(if (disclosure.loopbackWarning) "Подтвердить локальную отправку" else "Отправить") } },
        dismissButton = { TextButton(onClick = { haptics.reject(); viewModel.cancelDisclosure() }) { Text("Отмена") } },
    ) }
    if (reportDatePicker) HealthDatePicker(
        initial = state.reportedAt,
        title = "Дата исследования",
        onDismiss = { reportDatePicker = false },
        onConfirm = { value -> haptics.confirm(); viewModel.reportedAt(value); reportDatePicker = false },
    )
    if (observationDateIndex >= 0) state.observations.getOrNull(observationDateIndex)?.let { row ->
        HealthDatePicker(
            initial = row.observedAt,
            title = "Дата результата",
            onDismiss = { observationDateIndex = -1 },
            onConfirm = { value ->
                haptics.confirm()
                viewModel.updateObservation(observationDateIndex, row.copy(observedAt = value))
                observationDateIndex = -1
            },
        )
    }

    GlowBackground {
        Column(Modifier.fillMaxSize()) {
            HealthScreenHeader(
                title = if (state.correctsSyncId == null) "Новое исследование" else "Исправить исследование",
                onBack = onBack,
            )
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GymCard(Modifier.fillMaxWidth()) {
                    OutlinedTextField(state.title, viewModel::title, Modifier.fillMaxWidth(), label = { Text("Название") })
                    OutlinedTextField(state.provenance, viewModel::provenance, Modifier.fillMaxWidth(), label = { Text("Источник или лаборатория") })
                    OutlinedTextField(state.note, viewModel::note, Modifier.fillMaxWidth(), label = { Text("Заметка") })
                    TextButton(onClick = { haptics.tap(); reportDatePicker = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Дата исследования: ${formatDate(state.reportedAt, zone)}") }
                }
                GymCard(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { haptics.tap(); documentPicker.launch(arrayOf("image/*", "application/pdf")) },
                        enabled = !state.reading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(if (state.reading) "Подготавливаем документ…" else "Выбрать PDF или фото для распознавания") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.retainOriginal,
                            onCheckedChange = { checked -> haptics.toggle(checked); viewModel.retainOriginal(checked) },
                            enabled = state.pendingUri != null,
                        )
                        Text("Сохранить оригинал только на устройстве")
                    }
                    Text("Оригинал не передаётся в Google Sheets и не сохраняется без подтверждения результата.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.observations.forEachIndexed { index, row ->
                    GymCard(Modifier.fillMaxWidth()) {
                        Text("Результат ${index + 1}", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(row.rawName, { viewModel.updateObservation(index, row.copy(rawName = it)) }, Modifier.fillMaxWidth(), label = { Text("Показатель") })
                        Text("Тип значения", style = MaterialTheme.typography.labelLarge)
                        HealthValueKindSelector(row.valueKind) { kind -> haptics.toggle(true); viewModel.updateObservation(index, row.copy(valueKind = kind)) }
                        OutlinedTextField(row.rawValue, { viewModel.updateObservation(index, row.copy(rawValue = it)) }, Modifier.fillMaxWidth(), label = { Text("Значение") })
                        OutlinedTextField(row.unit, { viewModel.updateObservation(index, row.copy(unit = it)) }, Modifier.fillMaxWidth(), label = { Text("Единица") })
                        OutlinedTextField(row.referenceRange, { viewModel.updateObservation(index, row.copy(referenceRange = it)) }, Modifier.fillMaxWidth(), label = { Text("Референс") })
                        OutlinedTextField(row.method, { viewModel.updateObservation(index, row.copy(method = it)) }, Modifier.fillMaxWidth(), label = { Text("Метод") })
                        OutlinedTextField(row.material, { viewModel.updateObservation(index, row.copy(material = it)) }, Modifier.fillMaxWidth(), label = { Text("Материал") })
                        OutlinedTextField(row.source, { viewModel.updateObservation(index, row.copy(source = it)) }, Modifier.fillMaxWidth(), label = { Text("Источник") })
                        TextButton(onClick = { haptics.tap(); observationDateIndex = index }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Дата результата: ${formatDate(row.observedAt, zone)}") }
                        row.sourcePage?.let { Text("Страница источника: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { haptics.reject(); viewModel.removeObservation(index) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Убрать результат", color = MaterialTheme.colorScheme.error) }
                    }
                }
                TextButton(onClick = { haptics.tap(); viewModel.addObservation() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Добавить результат") }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                PillButton(
                    text = if (state.saving) "Сохраняем…" else "Подтвердить и сохранить",
                    onClick = { haptics.confirm(); viewModel.save() },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun HealthValueKindSelector(selected: HealthRawValueKind, onSelected: (HealthRawValueKind) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(HealthRawValueKind.entries) { kind ->
            FilterChip(selected = kind == selected, onClick = { onSelected(kind) }, label = { Text(kind.label()) })
        }
    }
}

private fun HealthRawValueKind.label(): String = when (this) {
    HealthRawValueKind.NUMBER -> "Число"
    HealthRawValueKind.NUMBER_WITH_OPERATOR -> "С оператором"
    HealthRawValueKind.RANGE -> "Диапазон"
    HealthRawValueKind.CATEGORY -> "Категория"
    HealthRawValueKind.CODE -> "Код"
    HealthRawValueKind.TEXT -> "Текст"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthDatePicker(initial: Long, title: String, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { picker.selectedDateMillis?.let(onConfirm) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            DatePicker(picker)
        }
    }
}
