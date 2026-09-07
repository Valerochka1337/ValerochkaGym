package com.valerochka1337.valerochkagym.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncConflictEntity
import java.time.ZoneId

/** Read-only health overview; body composition stays the same Measurements projection. */
@Composable
internal fun HealthOverviewCard(
    state: HealthAnalysisState,
    onOpenMeasurements: () -> Unit,
    onOpenMeasurement: (String) -> Unit = {},
    onCreateReport: () -> Unit,
    onCreateRestriction: () -> Unit,
    onOpenReport: (String) -> Unit,
    onOpenRestriction: (String) -> Unit = {},
    onOpenArchive: () -> Unit = {},
    onOpenConflict: (HealthSyncConflictEntity) -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault(),
) {
    AnalysisCard("Здоровье", subtitle = "Первичные замеры, подтверждённые исследования и ограничения") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val measurement = state.latestMeasurement
            val report = state.latestReport
            val restrictions = state.activeRestrictions
            if (measurement == null && report == null && restrictions.isEmpty()) {
                Text(
                    "Добавьте замер, исследование или ограничение, чтобы увидеть данные здоровья.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (measurement == null) Text("Замеров пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                Text("Последний замер: ${measurement.weightKg?.let { "$it кг" } ?: "доступные показатели"}")
                measurement.conditionsSummary()?.let { Text("Условия: $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                state.previousMeasurementWithDifferentConditions()?.let {
                    Text("Условия отличаются от предыдущего замера — сравнение может быть ограничено.", color = MaterialTheme.colorScheme.tertiary)
                }
            }
            TextButton(
                onClick = { measurement?.let { onOpenMeasurement(it.id) } ?: onOpenMeasurements() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(if (measurement == null) "Открыть замеры" else "Открыть последний замер") }
            if (measurement != null) {
                TextButton(
                    onClick = onOpenMeasurements,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Все замеры") }
            }
            TextButton(onClick = onCreateReport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Добавить исследование") }
            TextButton(onClick = onCreateRestriction, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Добавить ограничение") }
            TextButton(onClick = onOpenArchive, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Архив здоровья") }
            Text("Последнее исследование: ${report?.title ?: "нет подтверждённых"}")
            if (report != null) {
                TextButton(
                    onClick = { onOpenReport(report.syncId) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Открыть исследование") }
            }
            if (state.reports.isNotEmpty()) {
                Text("Исследования в этом периоде", style = MaterialTheme.typography.titleMedium)
                state.reports.sortedByDescending { it.reportedAt }.forEach { historical ->
                    TextButton(onClick = { onOpenReport(historical.syncId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(
                            "${formatDate(historical.reportedAt, zone)} · ${historical.title} · " +
                                healthReportStatusLabel(historical.status),
                        )
                    }
                }
            } else {
                Text(
                    "В этом периоде подтверждённых исследований не найдено",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Актуальные ограничения: ${if (restrictions.isEmpty()) "нет" else restrictions.joinToString { "${it.description} · ${healthRestrictionStatusLabel(it.status)}" }}")
            if (state.restrictions.isNotEmpty()) {
                Text("История ограничений", style = MaterialTheme.typography.titleMedium)
                state.restrictions.sortedByDescending { it.confirmedAt }.forEach { restriction ->
                    TextButton(onClick = { onOpenRestriction(restriction.syncId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("${restriction.description} · ${healthRestrictionStatusLabel(restriction.status)}")
                    }
                }
            }
            if (state.conflicts.isNotEmpty()) {
                Text(
                    "Конфликты синхронизации: ${state.conflicts.size}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "Данные не изменены. Выберите версию для каждого конфликта.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.conflicts.forEach { conflict ->
                    TextButton(
                        onClick = { onOpenConflict(conflict) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("Открыть конфликт: ${conflictCategoryName(conflict.category)} · версия ${conflict.version}")
                    }
                }
            }
        }
    }
}

private fun healthReportStatusLabel(status: String): String = when (status) {
    "FINAL" -> "Подтверждено"
    "CORRECTED" -> "Исправлено"
    "PRELIMINARY" -> "Черновик"
    "REVOKED" -> "Отозвано"
    else -> status
}

private fun healthRestrictionStatusLabel(status: String): String = when (status) {
    "ACTIVE" -> "активно"
    "TEMPORARY" -> "временно"
    "LIFTED" -> "снято"
    else -> status
}

private fun HealthAnalysisState.previousMeasurementWithDifferentConditions() = measurements
    .sortedByDescending { it.measuredAt }
    .zipWithNext()
    .firstOrNull { (latest, previous) ->
        latest.conditionsSummary() != null && previous.conditionsSummary() != null &&
            latest.conditionsSummary() != previous.conditionsSummary()
    }
    ?.second

private fun com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity.conditionsSummary(): String? =
    buildList {
        if (afterMeal) add("после еды")
        if (afterWorkout) add("после тренировки")
        if (unusualHydration) add("необычная гидратация")
        conditionNote?.trim()?.takeIf(String::isNotBlank)?.let(::add)
    }.takeIf { it.isNotEmpty() }?.joinToString()

internal fun conflictCategoryName(category: String): String = when (category) {
    "MEASUREMENTS" -> "Замеры"
    "HEALTH_REPORTS_AND_OBSERVATIONS" -> "Исследования"
    "HEALTH_RESTRICTIONS" -> "Ограничения"
    else -> "Синхронизация"
}
