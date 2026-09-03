package com.valerochka1337.valerochkagym.ui.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionEntity
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity
import com.valerochka1337.valerochkagym.data.health.MeasurementArchiveGroup
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.GymFilterChip
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics

@Composable
fun HealthArchiveScreen(onBack: () -> Unit, viewModel: HealthArchiveViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = gymHaptics()
    val selectorsEnabled = !state.initializing && !state.exporting
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { selected ->
            try {
                context.contentResolver.openOutputStream(selected)?.let(viewModel::export)
                    ?: viewModel.onOutputUnavailable()
            } catch (_: Exception) {
                // Providers can reject a destination after the user accepted CreateDocument.
                viewModel.onOutputUnavailable()
            }
        }
    }
    var reports by remember { mutableStateOf(emptyList<HealthReportEntity>()) }
    var restrictions by remember { mutableStateOf(emptyList<HealthRestrictionEntity>()) }
    var measurements by remember { mutableStateOf(emptyList<BodyMeasurementEntity>()) }
    var documents by remember { mutableStateOf(emptyList<ArchiveDocumentRow>()) }
    var observations by remember { mutableStateOf<Map<String, List<HealthObservationEntity>>>(emptyMap()) }
    LaunchedEffect(Unit) {
        reports = viewModel.reports(); restrictions = viewModel.restrictions(); measurements = viewModel.measurements(); documents = viewModel.documents()
        observations = reports.associate { it.syncId to viewModel.observations(it.syncId) }
    }
    GlowBackground {
        Column(Modifier.fillMaxSize()) {
            HealthScreenHeader("Архив здоровья", onBack)
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GymCard(Modifier.fillMaxWidth()) {
                    Text("Восстановление из Sheets не включает оригиналы файлов.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Выберите первичные записи для локального ZIP-архива.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GymCard(Modifier.fillMaxWidth()) {
                    Text("Период", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("30 дней" to 30L, "90 дней" to 90L, "1 год" to 365L, "Всё время" to null).forEach { (label, days) ->
                            GymFilterChip(
                                selected = state.periodLabel == label,
                                onClick = { if (selectorsEnabled) { haptics.toggle(state.periodLabel != label); viewModel.selectPeriod(days) } },
                                label = label,
                                enabled = selectorsEnabled,
                            )
                        }
                    }
                    Text(state.periodLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GymCard(Modifier.fillMaxWidth()) {
                    Text("Замеры", style = MaterialTheme.typography.titleMedium)
                    measurements.forEach { measurement ->
                        val selected = measurement.id in state.measurementIds
                        FilterChip(
                            selected = selected,
                            onClick = { if (selectorsEnabled) { haptics.toggle(!selected); viewModel.toggleMeasurement(measurement.id) } },
                            enabled = selectorsEnabled,
                            label = { Text("${java.time.Instant.ofEpochMilli(measurement.measuredAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()} · ${measurement.weightKg?.let { "$it кг" } ?: "доступные показатели"}${measurement.conditionBadge()}") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        )
                    }
                    if (measurements.isEmpty()) Text("Нет сохранённых замеров", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GymCard(Modifier.fillMaxWidth()) {
                    Text("Исследования", style = MaterialTheme.typography.titleMedium)
                    reports.forEach { report ->
                        val selected = report.syncId in state.reportIds
                        FilterChip(
                            selected = selected,
                            onClick = { if (selectorsEnabled) { haptics.toggle(!selected); viewModel.toggleReport(report.syncId) } },
                            enabled = selectorsEnabled,
                            label = { Text(report.title) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        )
                        if (selected) observations[report.syncId].orEmpty().forEach { observation ->
                            val included = observation.syncId in state.observationIds
                            FilterChip(
                                selected = included,
                                onClick = { if (selectorsEnabled) { haptics.toggle(!included); viewModel.toggleObservation(observation.syncId) } },
                                enabled = selectorsEnabled,
                                label = { Text("Результат: ${observation.rawName} · ${observation.rawValue}") },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            )
                        }
                    }
                    if (reports.isEmpty()) Text("Нет подтверждённых исследований", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GymCard(Modifier.fillMaxWidth()) {
                    Text("Ограничения", style = MaterialTheme.typography.titleMedium)
                    restrictions.forEach { restriction ->
                        val selected = restriction.syncId in state.restrictionIds
                        FilterChip(
                            selected = selected,
                            onClick = { if (selectorsEnabled) { haptics.toggle(!selected); viewModel.toggleRestriction(restriction.syncId) } },
                            enabled = selectorsEnabled,
                            label = { Text(restriction.description) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        )
                    }
                    if (restrictions.isEmpty()) Text("Нет подтверждённых ограничений", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GymCard(Modifier.fillMaxWidth()) {
                    Text("Локальные оригиналы", style = MaterialTheme.typography.titleMedium)
                    documents.forEach { document ->
                        val selected = document.id in state.documentIds
                        val eligible = document.id in state.eligibleDocumentIds
                        val enabled = document.selectable && eligible && selectorsEnabled
                        FilterChip(
                            selected = selected,
                            onClick = { if (enabled) { haptics.toggle(!selected); viewModel.toggleDocument(document.id) } },
                            enabled = enabled,
                            label = { Text("${document.owner} · ${document.name}${if (!document.selectable) " · ещё готовится" else if (!eligible) " · выберите все показатели" else ""}") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        )
                    }
                    if (documents.isEmpty()) Text("Готовых локальных оригиналов нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Оригинал содержит все значения — выберите все показатели/результаты, чтобы включить его.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GymCard(Modifier.fillMaxWidth()) {
                    Text("Показатели замеров", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MeasurementArchiveGroup.entries.forEach { group ->
                            val selected = group in state.measurementGroups
                            GymFilterChip(selected, { if (selectorsEnabled) { haptics.toggle(!selected); viewModel.toggleMeasurementGroup(group) } }, archiveGroupLabel(group), enabled = selectorsEnabled)
                        }
                    }
                    Text("Можно исключить группы; невыбранные значения не попадут в архив.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GymCard(Modifier.fillMaxWidth()) {
                    state.preview?.let { preview -> Text("В архиве: ${preview.measurements} замеров, ${preview.reports} исследований, ${preview.restrictions} ограничений, файлов: ${preview.readyDocuments}. Отсутствуют: ${preview.missingOriginals.joinToString { it.label }.ifBlank { "нет" }}", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    state.success?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    Button(
                        onClick = { haptics.confirm(); launcher.launch("health-archive.zip") },
                        enabled = !state.exporting && !state.initializing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(if (state.exporting) "Создание…" else "Создать ZIP") }
                }
            }
        }
    }
}

private fun archiveGroupLabel(group: MeasurementArchiveGroup) = when (group) {
    MeasurementArchiveGroup.BODY_COMPOSITION -> "Состав тела"
    MeasurementArchiveGroup.SEGMENTAL -> "Сегментный анализ"
    MeasurementArchiveGroup.CIRCUMFERENCES -> "Обхваты"
    MeasurementArchiveGroup.CONDITIONS -> "Условия"
}

private fun BodyMeasurementEntity.conditionBadge(): String = buildList {
    if (afterMeal) add("после еды")
    if (afterWorkout) add("после тренировки")
    if (unusualHydration) add("гидратация")
    conditionNote?.trim()?.takeIf(String::isNotBlank)?.let(::add)
}.takeIf { it.isNotEmpty() }?.joinToString(prefix = " · ", separator = ", ").orEmpty()
