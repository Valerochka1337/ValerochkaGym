package com.valerochka1337.valerochkagym.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.StackedBarChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.domain.analysis.BalanceRatio
import com.valerochka1337.valerochkagym.domain.analysis.WorkloadRatio
import com.valerochka1337.valerochkagym.ui.analysis.charts.ChartSpec
import com.valerochka1337.valerochkagym.ui.analysis.charts.ColumnChart
import com.valerochka1337.valerochkagym.ui.analysis.charts.ColumnDatum
import com.valerochka1337.valerochkagym.ui.analysis.charts.MeterZone
import com.valerochka1337.valerochkagym.ui.analysis.charts.ZoneMeter
import com.valerochka1337.valerochkagym.ui.analysis.charts.rememberChartColors
import com.valerochka1337.valerochkagym.ui.theme.ChartPalette
import kotlin.math.log2

/**
 * Недельный объём: рабочие подходы или тоннаж по неделям.
 *
 * Два измерения одной работы переключаются чипами, а не рисуются двумя осями на одном графике:
 * подходы и килограммы несопоставимы, и совмещённые шкалы придумали бы связь, которой нет.
 * Опорная линия — среднее за окно, она отвечает на вопрос «эта неделя выше или ниже обычного».
 */
@Composable
internal fun WeeklyVolumeCard(
    state: AnalysisUiState,
    onMetricSelected: (WeeklyMetric) -> Unit,
    onWeekSelected: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val points = state.report.weeklyPoints
    if (points.isEmpty()) return
    val sets = state.weeklyMetric == WeeklyMetric.SETS
    val data = points.map { point ->
        ColumnDatum(
            label = point.label,
            value = (if (sets) point.hardSets else point.tonnageKg).toFloat(),
            partial = point.partial,
        )
    }
    // Средняя считается по закрытым неделям: текущая неполная занижала бы ориентир.
    val closed = data.filterNot { it.partial }
    val average = closed.takeIf { it.isNotEmpty() }?.map { it.value }?.average()?.toFloat()

    AnalysisCard(
        title = "Недельный объём",
        subtitle = "Линия — среднее за закрытые недели. Последняя неделя ещё не завершена и показана бледнее",
        icon = Icons.Rounded.StackedBarChart,
        modifier = modifier,
    ) {
        ChipRow(
            options = WeeklyMetric.entries,
            selected = state.weeklyMetric,
            label = { if (it == WeeklyMetric.SETS) "Подходы" else "Тоннаж" },
            onSelect = onMetricSelected,
        )
        Spacer(Modifier.height(12.dp))
        ColumnChart(
            data = data,
            referenceValue = average,
            selectedIndex = state.selectedWeekIndex,
            onSelect = onWeekSelected,
            valueFormatter = { value ->
                if (sets) formatDecimal(value.toDouble(), 0) else formatTonnage(value.toDouble())
            },
        )
        Spacer(Modifier.height(8.dp))
        val index = state.selectedWeekIndex?.coerceIn(points.indices) ?: points.lastIndex
        val point = points[index]
        ValueRow(label = "Неделя с ${point.label}", value = "${point.sessions} трен.", accent = true)
        ValueRow(label = "Рабочих подходов", value = formatDecimal(point.hardSets, 0))
        ValueRow(label = "Тоннаж", value = formatTonnage(point.tonnageKg))
        if (average != null) {
            ValueRow(
                label = "Среднее за период",
                value = if (sets) formatDecimal(average.toDouble(), 0) else formatTonnage(average.toDouble()),
            )
        }
    }
}

/**
 * Отношение острой нагрузки к хронической (7 дней против среднего за 28).
 *
 * Показывается полосой с зонами, а не «спидометром»: круговая шкала хуже читается и создаёт
 * ложное впечатление точности. Само отношение — эвристика из спортивной науки: коридор
 * 0.8–1.3 считается управляемым ростом, выше 1.5 — резким скачком с повышенным риском травмы.
 * До 28 дней истории показатель не считается вовсе.
 */
@Composable
internal fun WorkloadCard(
    workload: WorkloadRatio,
    modifier: Modifier = Modifier,
) {
    AnalysisCard(
        title = "Скачок нагрузки",
        subtitle = "Подходы за 7 дней к средней неделе за 28 дней. Коридор 0.8–1.3 — рост под контролем",
        icon = Icons.Rounded.MonitorHeart,
        modifier = modifier,
    ) {
        val ratio = workload.ratio
        if (!workload.hasEnoughData || ratio == null) {
            Text(
                text = "Нужно 28 дней истории: пока «обычная неделя» не с чем сравнивать",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AnalysisCard
        }

        val status = when {
            ratio > 1.5 -> "резкий скачок" to ChartPalette.TooLittle
            ratio > 1.3 -> "выше коридора" to ChartPalette.Maintenance
            ratio < 0.8 -> "недогруз" to ChartPalette.Maintenance
            else -> "в коридоре" to ChartPalette.Optimal
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatDecimal(ratio, 2),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatusPill(text = status.first, color = status.second)
        }
        Spacer(Modifier.height(10.dp))
        ZoneMeter(
            value = ratio.toFloat(),
            min = 0f,
            max = 2f,
            zones = listOf(
                MeterZone(0f, 0.8f, ChartPalette.Maintenance.copy(alpha = 0.35f)),
                MeterZone(0.8f, 1.3f, ChartPalette.Optimal.copy(alpha = 0.45f)),
                MeterZone(1.3f, 1.5f, ChartPalette.Maintenance.copy(alpha = 0.45f)),
                MeterZone(1.5f, 2f, ChartPalette.TooLittle.copy(alpha = 0.45f)),
            ),
            boundaryLabels = listOf("0", "0.8", "1.3", "1.5", "2+"),
        )
        Spacer(Modifier.height(10.dp))
        ValueRow(label = "Подходов за 7 дней", value = formatDecimal(workload.acuteSets, 0))
        ValueRow(label = "Обычная неделя", value = formatDecimal(workload.chronicWeeklySets))
    }
}

/**
 * Баланс объёма между антагонистами.
 *
 * Шкала логарифмическая и симметричная относительно 1:1 — на линейной «1.5 к одному» и «один к
 * 1.5» выглядели бы по-разному, хотя это одинаковый по силе перекос в разные стороны. Зелёная
 * полоса — целевой коридор; для «верх/низ» правильного значения не существует, поэтому там
 * отмечаются только крайности.
 */
@Composable
internal fun BalanceCard(
    balances: List<BalanceRatio>,
    modifier: Modifier = Modifier,
) {
    val visible = balances.filter { it.ratio != null || it.leftSets > 0.0 }
    if (visible.isEmpty()) return

    AnalysisCard(
        title = "Баланс объёма",
        subtitle = "Соотношение эффективных подходов. Центр — 1:1, зелёная полоса — целевой коридор",
        icon = Icons.Rounded.Balance,
        modifier = modifier,
    ) {
        visible.forEach { balance ->
            BalanceRow(balance)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun BalanceRow(balance: BalanceRatio) {
    val colors = rememberChartColors()
    val (leftLabel, rightLabel) = balance.id.sideLabels()
    // ±1 по шкале log2 = перекос вдвое: дальше растягивать нечего, всё за этим — «сильный перекос».
    val limit = 1f
    val position = balance.ratio?.let { log2(it.toFloat()).coerceIn(-limit, limit) } ?: -limit
    val targetLow = log2(balance.targetLow.toFloat()).coerceIn(-limit, limit)
    val targetHigh = log2(balance.targetHigh.toFloat()).coerceIn(-limit, limit)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = balance.id.title(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = balance.ratio?.let { formatRatio(it) } ?: "—",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (balance.inTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
        }
        Spacer(Modifier.height(6.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(22.dp)) {
            val trackHeight = 10.dp.toPx()
            val top = (size.height - trackHeight) / 2f
            val center = size.width / 2f
            fun x(value: Float) = center + value / (limit * 2f) * size.width

            drawRoundRect(
                color = colors.track,
                topLeft = Offset(0f, top),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f),
            )
            drawRect(
                color = colors.mark.copy(alpha = 0.2f),
                topLeft = Offset(x(targetLow), top),
                size = Size((x(targetHigh) - x(targetLow)).coerceAtLeast(2.dp.toPx()), trackHeight),
            )
            drawLine(
                color = colors.grid,
                start = Offset(center, top - 3.dp.toPx()),
                end = Offset(center, top + trackHeight + 3.dp.toPx()),
                strokeWidth = ChartSpec.GridWidth.toPx(),
            )
            val barLeft = minOf(center, x(position))
            val barWidth = kotlin.math.abs(x(position) - center).coerceAtLeast(3.dp.toPx())
            drawRoundRect(
                color = if (balance.inTarget) ChartPalette.Optimal else ChartPalette.Maintenance,
                topLeft = Offset(barLeft, top - 2.dp.toPx()),
                size = Size(barWidth, trackHeight + 4.dp.toPx()),
                cornerRadius = CornerRadius(ChartSpec.BarCorner.toPx(), ChartSpec.BarCorner.toPx()),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "← $leftLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$rightLabel →",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
