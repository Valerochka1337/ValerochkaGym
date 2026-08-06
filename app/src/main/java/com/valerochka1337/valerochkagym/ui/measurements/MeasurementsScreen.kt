package com.valerochka1337.valerochkagym.ui.measurements

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementMetric
import com.valerochka1337.valerochkagym.domain.measurements.MeasurementChartGroup
import com.valerochka1337.valerochkagym.domain.measurements.MeasurementMetricComparison
import com.valerochka1337.valerochkagym.domain.measurements.MeasurementPeriod
import com.valerochka1337.valerochkagym.ui.analysis.AnalysisCard
import com.valerochka1337.valerochkagym.ui.analysis.ChipRow
import com.valerochka1337.valerochkagym.ui.analysis.ValueRow
import com.valerochka1337.valerochkagym.ui.analysis.charts.LinePoint
import com.valerochka1337.valerochkagym.ui.analysis.charts.TrendLineChart
import com.valerochka1337.valerochkagym.ui.components.CircleIconButton
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.components.UploadStatusBadge
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics

/**
 * Pushed-раздел «Замеры»: единый период, независимые одновалюстные тренды и история. Каждая
 * карточка графика переключает только метрику своей группы, поэтому кг, см, проценты и уровень
 * никогда не оказываются на общей оси.
 */
@Composable
fun MeasurementsScreen(
    onBack: () -> Unit,
    onCreateMeasurement: () -> Unit,
    onEditMeasurement: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MeasurementsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = gymHaptics()

    GlowBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            MeasurementsHeader(
                onBack = onBack,
                onAdd = {
                    haptics.tap()
                    onCreateMeasurement()
                },
            )

            if (state.loading) return@Column
            if (!state.hasMeasurements) {
                EmptyMeasurementsState(onAdd = {
                    haptics.tap()
                    onCreateMeasurement()
                })
                return@Column
            }

            val measurements = state.measurements.orEmpty()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    ChipRow(
                        options = MeasurementPeriod.entries.toList(),
                        selected = state.period,
                        label = MeasurementPeriod::displayName,
                        onSelect = {
                            haptics.tap()
                            viewModel.onPeriodSelected(it)
                        },
                    )
                }

                if (measurements.isEmpty()) {
                    item { EmptyPeriodState() }
                } else {
                    item { LatestSummaryCard(state.summary) }
                    item {
                        MetricTrendCard(
                            title = "Состав тела",
                            subtitle = "InBody сравнивайте на одном аппарате и в похожих условиях.",
                            icon = Icons.Rounded.MonitorWeight,
                            metrics = metricsFor(MeasurementChartGroup.COMPOSITION),
                            selectedMetric = state.compositionMetric,
                            measurements = measurements,
                            selectedMeasurementId = state.selectedMeasurementId,
                            zoneLabel = state.zone,
                            onMetricSelected = {
                                haptics.tap()
                                viewModel.onCompositionMetricSelected(it)
                            },
                            onMeasurementSelected = {
                                haptics.tap()
                                viewModel.onMeasurementSelected(it)
                            },
                        )
                    }
                    item {
                        MetricTrendCard(
                            title = "Обхваты",
                            subtitle = "Повторяйте один и тот же способ измерения; талия — самостоятельный ориентир.",
                            icon = Icons.Rounded.Straighten,
                            metrics = metricsFor(MeasurementChartGroup.CIRCUMFERENCES),
                            selectedMetric = state.circumferenceMetric,
                            measurements = measurements,
                            selectedMeasurementId = state.selectedMeasurementId,
                            zoneLabel = state.zone,
                            onMetricSelected = {
                                haptics.tap()
                                viewModel.onCircumferenceMetricSelected(it)
                            },
                            onMeasurementSelected = {
                                haptics.tap()
                                viewModel.onMeasurementSelected(it)
                            },
                        )
                    }
                    item {
                        MetricTrendCard(
                            title = "WHR / висцеральный жир",
                            subtitle = "Показатели InBody полезнее читать как тренд, а не как точный диагноз.",
                            icon = Icons.Rounded.ShowChart,
                            metrics = metricsFor(MeasurementChartGroup.RISK),
                            selectedMetric = state.riskMetric,
                            measurements = measurements,
                            selectedMeasurementId = state.selectedMeasurementId,
                            zoneLabel = state.zone,
                            onMetricSelected = {
                                haptics.tap()
                                viewModel.onRiskMetricSelected(it)
                            },
                            onMeasurementSelected = {
                                haptics.tap()
                                viewModel.onMeasurementSelected(it)
                            },
                        )
                    }
                    item { SelectedMeasurementCard(state.selectedMeasurement, state.zone) }
                    item {
                        MeasurementHistoryCard(
                            measurements = measurements,
                            zone = state.zone,
                            onEdit = { measurement ->
                                haptics.tap()
                                onEditMeasurement(measurement.id)
                            },
                            onRetry = { id ->
                                haptics.tap()
                                viewModel.retryUpload(id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeasurementsHeader(onBack: () -> Unit, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Назад",
            onClick = onBack,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Замеры",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        CircleIconButton(
            icon = Icons.Rounded.Add,
            contentDescription = "Добавить замер",
            onClick = onAdd,
        )
    }
}

@Composable
private fun EmptyMeasurementsState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.MonitorWeight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Добавьте первый замер",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Вес, состав тела и обхваты появятся здесь как честные тренды — без подмены пропусков нулями.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        PillButton(text = "Добавить замер", onClick = onAdd, leadingIcon = Icons.Rounded.Add)
    }
}

@Composable
private fun EmptyPeriodState() {
    AnalysisCard(
        title = "Нет замеров за этот период",
        subtitle = "Выберите более длинный период или добавьте новый замер.",
        icon = Icons.Rounded.MonitorWeight,
    ) {}
}

@Composable
private fun LatestSummaryCard(summary: List<MeasurementMetricComparison>) {
    AnalysisCard(
        title = "Последний замер",
        subtitle = "Изменение сравнивается с предыдущим заполненным значением той же метрики.",
        icon = Icons.Rounded.TableChart,
    ) {
        summary.forEachIndexed { index, item ->
            ValueRow(
                label = item.metric.title,
                value = buildString {
                    append(formatMeasurementValue(item.metric, item.value))
                    item.delta?.let { delta -> append(" · ${formatMeasurementDelta(item.metric, delta)}") }
                },
                accent = item.delta != null,
            )
            if (index != summary.lastIndex) Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun MetricTrendCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    metrics: List<BodyMeasurementMetric>,
    selectedMetric: BodyMeasurementMetric,
    measurements: List<BodyMeasurementEntity>,
    selectedMeasurementId: String?,
    zoneLabel: java.time.ZoneId,
    onMetricSelected: (BodyMeasurementMetric) -> Unit,
    onMeasurementSelected: (String) -> Unit,
) {
    val chartPoints = measurements
        .asSequence()
        .mapNotNull { measurement ->
            selectedMetric.value(measurement)?.let { value ->
                MeasurementChartPoint(
                    id = measurement.id,
                    point = LinePoint(
                        xMillis = measurement.measuredAt,
                        y = value.toFloat(),
                        xLabel = formatMeasurementChartDate(measurement.measuredAt, zoneLabel),
                    ),
                )
            }
        }
        .sortedBy { it.point.xMillis }
        .toList()

    AnalysisCard(title = title, subtitle = subtitle, icon = icon) {
        ChipRow(
            options = metrics,
            selected = selectedMetric,
            label = BodyMeasurementMetric::title,
            onSelect = onMetricSelected,
        )
        Spacer(Modifier.height(12.dp))
        if (chartPoints.isEmpty()) {
            Text(
                text = "Для «${selectedMetric.title}» пока нет значений.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val selectedIndex = chartPoints.indexOfFirst { it.id == selectedMeasurementId }.takeIf { it >= 0 }
            TrendLineChart(
                points = chartPoints.map(MeasurementChartPoint::point),
                selectedIndex = selectedIndex,
                onSelect = { index ->
                    index?.let { onMeasurementSelected(chartPoints[it].id) }
                },
                valueFormatter = { value -> formatMeasurementValue(selectedMetric, value.toDouble()) },
            )
        }
    }
}

private data class MeasurementChartPoint(val id: String, val point: LinePoint)

@Composable
private fun SelectedMeasurementCard(measurement: BodyMeasurementEntity?, zone: java.time.ZoneId) {
    if (measurement == null) return
    val values = BodyMeasurementMetric.entries.mapNotNull { metric ->
        metric.value(measurement)?.let { value -> metric to value }
    }
    AnalysisCard(
        title = "Точка на графике",
        subtitle = "${formatMeasurementDate(measurement.measuredAt, zone)} · все заполненные значения",
        icon = Icons.Rounded.TableChart,
    ) {
        values.forEachIndexed { index, (metric, value) ->
            ValueRow(label = metric.title, value = formatMeasurementValue(metric, value))
            if (index != values.lastIndex) Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun MeasurementHistoryCard(
    measurements: List<BodyMeasurementEntity>,
    zone: java.time.ZoneId,
    onEdit: (BodyMeasurementEntity) -> Unit,
    onRetry: (String) -> Unit,
) {
    AnalysisCard(
        title = "История",
        subtitle = "Правки и удаление не меняют уже добавленную строку в Google Sheets.",
        icon = Icons.Rounded.MonitorWeight,
    ) {
        measurements.forEachIndexed { index, measurement ->
            MeasurementHistoryRow(measurement, zone, onEdit, onRetry)
            if (index != measurements.lastIndex) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun MeasurementHistoryRow(
    measurement: BodyMeasurementEntity,
    zone: java.time.ZoneId,
    onEdit: (BodyMeasurementEntity) -> Unit,
    onRetry: (String) -> Unit,
) {
    val filledMetrics = BodyMeasurementMetric.entries.count { it.value(measurement) != null }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit(measurement) }
            .padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatMeasurementDate(measurement.measuredAt, zone),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Заполнено показателей: $filledMetrics",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            UploadStatusBadge(measurement.uploadStatus)
        }
        if (measurement.uploadStatus == UploadStatus.FAILED) {
            measurement.uploadError?.takeIf { it.isNotBlank() }?.let { error ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = { onRetry(measurement.id) }) {
                Text("Повторить выгрузку")
            }
        }
    }
}

private fun metricsFor(group: MeasurementChartGroup): List<BodyMeasurementMetric> =
    BodyMeasurementMetric.entries.filter { it.group == group }
