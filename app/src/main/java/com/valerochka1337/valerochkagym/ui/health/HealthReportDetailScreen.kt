package com.valerochka1337.valerochkagym.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncConflictEntity
import com.valerochka1337.valerochkagym.data.health.HealthSyncPayloadCodec
import com.valerochka1337.valerochkagym.domain.health.HealthTrendSeries
import com.valerochka1337.valerochkagym.ui.analysis.ValueRow
import com.valerochka1337.valerochkagym.ui.analysis.formatDate
import com.valerochka1337.valerochkagym.ui.analysis.charts.LinePoint
import com.valerochka1337.valerochkagym.ui.analysis.charts.TrendLineChart
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.CircleIconButton
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import java.time.ZoneId

@Composable
fun HealthReportDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit = {},
    onOpenConflict: (HealthSyncConflictEntity) -> Unit = {},
    viewModel: HealthReportDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = gymHaptics()
    var deleteDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.events.collect { event -> message = when (event) { HealthReportDeleteEvent.Success -> "Исследование удалено"; is HealthReportDeleteEvent.PartialFailure -> event.message; is HealthReportDeleteEvent.Failure -> event.message }; if (event is HealthReportDeleteEvent.Success) { haptics.success(); onBack() } } }
    val zone = ZoneId.systemDefault()
    GlowBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            HealthScreenHeader(title = "Исследование", onBack = onBack) {
                if (state.report != null && !state.structuredDeleted) CircleIconButton(
                    icon = Icons.Rounded.Delete,
                    contentDescription = "Удалить исследование",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = { haptics.reject(); deleteDialog = true },
                )
            }
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            val report = state.report
            if (report == null) {
                Text("Исследование не найдено")
                return@Column
            }
            GymCard(Modifier.fillMaxWidth()) {
                if (!report.isTombstone) TextButton(onClick = { haptics.tap(); onEdit(report.syncId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Исправить") }
                Text(report.title, style = MaterialTheme.typography.headlineSmall)
                ValueRow("Статус", report.status)
                ValueRow("Дата", formatDate(report.reportedAt, zone))
                ValueRow("Источник", report.provenance)
            }
            if (report.status == "REVOKED" || report.isTombstone) {
                Text(
                    "Отозвано: результаты сохранены в истории, но не входят в текущую сводку и динамику.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.missingOriginal) {
                Text(
                    "Оригинал исследования недоступен на этом устройстве. Синхронизация не восстанавливает файлы.",
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (state.hasReadyOriginal) Text("Оригинал сохранён локально")
            if (state.conflicts.isNotEmpty()) GymCard(Modifier.fillMaxWidth()) {
                Text(
                    "Синхронизация обнаружила разные данные одной версии. Локальная запись не изменена: выберите версию для каждого конфликта.",
                    color = MaterialTheme.colorScheme.error,
                )
                state.conflicts.forEach { conflict ->
                    TextButton(
                        onClick = { haptics.tap(); onOpenConflict(conflict) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Открыть конфликт · версия ${conflict.version}") }
                }
            }
            state.deletionError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (state.pendingOriginalDeletionIds.isNotEmpty()) TextButton(
                onClick = { haptics.confirm(); viewModel.retryOriginalDeletion() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Повторить удаление оригинала") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }

            VersionHistory(state.history, zone)
            IncompatibilityWarnings(state.incompatibilities)
            ComparableTrends(state.comparableSeries, zone)
            Text("Результаты", style = MaterialTheme.typography.titleLarge)
            state.observations.forEach { ObservationCard(it, zone) }
        }
        }
    }
    if (deleteDialog) AlertDialog(
        onDismissRequest = { if (!state.deleting) deleteDialog = false },
        title = { Text("Удалить исследование?") },
        text = { Text("Удаление из Google Sheets не выполняется. Выберите, что делать с локальным оригиналом.") },
        dismissButton = { TextButton(onClick = { haptics.reject(); deleteDialog = false }, enabled = !state.deleting) { Text("Отмена") } },
        confirmButton = { Column { TextButton(onClick = { haptics.reject(); viewModel.deleteReport(false); deleteDialog = false }, enabled = !state.deleting) { Text("Удалить запись, оставить оригинал") }; TextButton(onClick = { haptics.reject(); viewModel.deleteReport(true); deleteDialog = false }, enabled = !state.deleting) { Text("Удалить запись и оригинал", color = MaterialTheme.colorScheme.error) } } },
    )
}

@Composable
private fun IncompatibilityWarnings(items: List<HealthTrendIncompatibility>) {
    if (items.isEmpty()) return
    GymCard(modifier = Modifier.fillMaxWidth()) {
        Text("Несопоставимые результаты", style = MaterialTheme.typography.titleLarge)
        items.forEach { item ->
            ValueRow(item.canonicalKey, item.reason)
        }
    }
}

@Composable
private fun VersionHistory(
    snapshots: List<com.valerochka1337.valerochkagym.data.db.entity.HealthReportSnapshotEntity>,
    zone: ZoneId,
) {
    if (snapshots.isEmpty()) return
    GymCard(modifier = Modifier.fillMaxWidth()) {
        Text("История версий", style = MaterialTheme.typography.titleLarge)
        snapshots.forEachIndexed { index, snapshot ->
            val aggregate = HealthSyncPayloadCodec.decodeReport(snapshot.canonicalPayload)
            if (aggregate == null) {
                Text("Версия ${snapshot.version}: данные истории недоступны", color = MaterialTheme.colorScheme.error)
            } else {
                val report = aggregate.report
                Text(
                    text = if (index == 0) "Текущая версия v${snapshot.version}" else "Версия v${snapshot.version}",
                    style = MaterialTheme.typography.titleMedium,
                )
                ValueRow("Статус", report.status)
                ValueRow("Дата", formatDate(report.reportedAt, zone))
                report.supersedesVersion?.let { ValueRow("Исправляет", "v$it") }
                if (report.isTombstone) Text("Эта версия отзывает исследование", color = MaterialTheme.colorScheme.error)
                aggregate.observations.forEach { observation ->
                    Text(
                        "${observation.rawName}: ${observation.rawValue}${observation.unit?.let { " $it" }.orEmpty()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComparableTrends(series: List<HealthTrendSeries>, zone: ZoneId) {
    series.filter { it.points.size > 1 }.forEach { trend ->
        GymCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Динамика: ${trend.canonicalKey}${trend.unit?.let { " · $it" }.orEmpty()}")
                TrendLineChart(
                    points = trend.points.map { LinePoint(it.observedAt, it.value.toFloat(), formatDate(it.observedAt, zone)) },
                    valueFormatter = { it.toString() },
                )
                trend.points.forEach { point ->
                    ValueRow(
                        label = formatDate(point.observedAt, zone),
                        value = buildString {
                            append(point.value)
                            trend.unit?.let { append(" ").append(it) }
                            point.referenceRange?.let { append(" · реф. ").append(it) }
                            trend.source?.let { append(" · ").append(it) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ObservationCard(observation: HealthObservationEntity, zone: ZoneId) {
    GymCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(observation.rawName, style = MaterialTheme.typography.titleMedium)
            Text("${observation.rawValue} ${observation.unit.orEmpty()}")
            ValueRow("Дата", formatDate(observation.observedAt, zone))
            observation.referenceRange?.let { ValueRow("Референс", it) }
            observation.material?.let { ValueRow("Материал", it) }
            observation.method?.let { ValueRow("Метод", it) }
            observation.source?.let { ValueRow("Источник", it) }
            if (observation.valueType != "NUMBER") Text("Отображается в хронологии, без графика")
        }
    }
}
