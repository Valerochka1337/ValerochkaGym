package com.valerochka1337.valerochkagym.ui.analysis

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.domain.analysis.META_ANALYTIC_RANGE
import com.valerochka1337.valerochkagym.domain.analysis.MuscleLoadSummary
import com.valerochka1337.valerochkagym.domain.analysis.VolumeZone
import com.valerochka1337.valerochkagym.domain.analysis.landmarks
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.analysis.body.BodyMapFlip
import com.valerochka1337.valerochkagym.ui.analysis.charts.ChartSpec
import com.valerochka1337.valerochkagym.ui.analysis.charts.rememberChartColors
import com.valerochka1337.valerochkagym.ui.theme.ChartPalette
import com.valerochka1337.valerochkagym.ui.theme.GymMotion

/**
 * Тепловая карта тела: интерактивный человек, закрашенный по недельному объёму каждой мышцы.
 *
 * Шкала ступенчатая и подписана легендой, а числа выбранной мышцы выводятся текстом под картой —
 * цвет здесь нигде не остаётся единственным носителем смысла. Перебор объёма отмечается
 * обводкой, а не ещё одним оттенком: заливка должна оставаться монотонной «мало → норма».
 */
@Composable
internal fun MuscleHeatmapCard(
    state: AnalysisUiState,
    onMuscleClicked: (Muscle?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Производные от отчёта, а не от выбора: пересобирать их на каждый тап по карте незачем.
    val loads = remember(state.report) { state.report.muscleLoads.associateBy { it.muscle } }
    val overloaded = remember(state.report) {
        state.report.muscleLoads
            .filter { it.zone == VolumeZone.EXCESSIVE }
            .mapTo(mutableSetOf()) { it.muscle }
    }

    AnalysisCard(
        title = "Карта нагрузки",
        subtitle = "Эффективных подходов на мышцу в неделю, в среднем за ${state.period.displayName().lowercase()}",
        icon = Icons.Rounded.Accessibility,
        modifier = modifier,
    ) {
        BodyMapFlip(
            fillFor = { muscle -> ChartPalette.zoneColor(loads[muscle]?.zone ?: VolumeZone.NONE) },
            selectedMuscle = state.selectedMuscle,
            outlined = overloaded,
            onMuscleClick = onMuscleClicked,
        )

        Spacer(Modifier.height(12.dp))
        HeatLegend()
        Spacer(Modifier.height(12.dp))
        SelectedMuscleDetails(
            state.selectedMuscleLoad,
            modifier = Modifier.animateContentSize(GymMotion.spatialDefault()),
        )
    }
}

@Composable
private fun HeatLegend() {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ChartPalette.legendZones.forEach { zone ->
            LegendSwatch(color = ChartPalette.zoneColor(zone), label = zone.displayName())
        }
        LegendSwatch(color = ChartPalette.Overload, label = "перебор", outlined = true)
    }
}

/** Числа выбранной мышцы: без них карта — только «красиво», но не ответ на вопрос. */
@Composable
private fun SelectedMuscleDetails(
    load: MuscleLoadSummary?,
    modifier: Modifier = Modifier,
) {
    if (load == null) {
        Text(
            text = "Нажмите на мышцу, чтобы увидеть подробности",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val landmarks = load.muscle.landmarks()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = load.muscle.displayName(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatusPill(
                text = load.zone.displayName(),
                color = if (load.zone == VolumeZone.EXCESSIVE) {
                    ChartPalette.Overload
                } else {
                    ChartPalette.zoneColor(load.zone)
                },
            )
        }
        ValueRow(
            label = "Подходов в неделю",
            value = formatDecimal(load.weeklySets),
            accent = true,
        )
        ValueRow(
            label = "Ориентир (MEV · коридор · MRV)",
            value = "${formatDecimal(landmarks.mev, 0)} · " +
                "${formatDecimal(landmarks.mavLow, 0)}–${formatDecimal(landmarks.mavHigh, 0)} · " +
                formatDecimal(landmarks.mrv, 0),
        )
        ValueRow(label = "Раз в неделю", value = formatDecimal(load.sessionsPerWeek))
        ValueRow(
            label = "Дней с последней работы",
            value = load.daysSinceLast?.toString() ?: "—",
        )
        if (load.topExercises.isNotEmpty()) {
            // Названия упражнений в строку «подпись — значение» не помещаются: три названия
            // длиннее всей карточки, поэтому они идут отдельным блоком с переносом.
            ValueBlock(label = "Больше всего дают", value = load.topExercises.joinToString(", "))
        }
    }
}

/**
 * Объём по мышцам в виде bullet-графика: фактическое значение поверх опорных диапазонов.
 *
 * Отсортировано по дефициту относительно рабочего коридора, поэтому первые строки — это готовый
 * список «что добавить в план». Такая форма единственная, где рядом видны и значение, и цель,
 * и допустимый максимум.
 */
@Composable
internal fun MuscleVolumeCard(
    state: AnalysisUiState,
    onMuscleClicked: (Muscle?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = state.report.muscleLoads.sortedBy { load ->
        // Дефицит в долях коридора: так мелкие и крупные мышцы сравниваются честно.
        val target = load.muscle.landmarks().mavLow.takeIf { it > 0.0 } ?: 1.0
        load.weeklySets / target
    }

    AnalysisCard(
        title = "Объём по мышцам",
        subtitle = "Подходов в неделю против ориентиров. Мета-анализы дают " +
            "${formatDecimal(META_ANALYTIC_RANGE.start, 0)}–${formatDecimal(META_ANALYTIC_RANGE.endInclusive, 0)} " +
            "подходов в неделю как рабочий диапазон для большинства мышц",
        icon = Icons.Rounded.BarChart,
        modifier = modifier,
    ) {
        rows.forEach { load ->
            MuscleBulletRow(
                load = load,
                selected = load.muscle == state.selectedMuscle,
                onClick = { onMuscleClicked(load.muscle) },
            )
        }
    }
}

@Composable
private fun MuscleBulletRow(
    load: MuscleLoadSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val landmarks = load.muscle.landmarks()
    val colors = rememberChartColors()
    val fill = ChartPalette.zoneColor(load.zone)
    val maxValue = maxOf(landmarks.mrv, load.weeklySets) * 1.05

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = load.muscle.displayName(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(MUSCLE_LABEL_WIDTH),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(28.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
                val trackHeight = 10.dp.toPx()
                val top = (size.height - trackHeight) / 2f
                val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                fun x(value: Double) = (value / maxValue).coerceIn(0.0, 1.0).toFloat() * size.width

                drawRoundRect(
                    color = colors.track,
                    topLeft = Offset(0f, top),
                    size = Size(size.width, trackHeight),
                    cornerRadius = radius,
                )
                // Рабочий коридор — подложка, на фоне которой читается длина метки.
                drawRect(
                    color = colors.mark.copy(alpha = 0.14f),
                    topLeft = Offset(x(landmarks.mavLow), top),
                    size = Size(x(landmarks.mavHigh) - x(landmarks.mavLow), trackHeight),
                )
                listOf(landmarks.mev, landmarks.mrv).forEach { landmark ->
                    if (landmark <= 0.0) return@forEach
                    drawLine(
                        color = colors.grid,
                        start = Offset(x(landmark), top - 3.dp.toPx()),
                        end = Offset(x(landmark), top + trackHeight + 3.dp.toPx()),
                        strokeWidth = ChartSpec.GridWidth.toPx(),
                    )
                }
                if (load.weeklySets > 0.0) {
                    val barHeight = 14.dp.toPx()
                    val barTop = (size.height - barHeight) / 2f
                    drawRoundRect(
                        color = fill,
                        topLeft = Offset(0f, barTop),
                        size = Size(x(load.weeklySets).coerceAtLeast(3.dp.toPx()), barHeight),
                        cornerRadius = CornerRadius(ChartSpec.BarCorner.toPx(), ChartSpec.BarCorner.toPx()),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatDecimal(load.weeklySets),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(34.dp),
        )
    }
}

/**
 * Частота и пауза: сколько дней прошло с последней работы по каждой мышце.
 *
 * Отсортировано по паузе вниз, поэтому первые строки — это список «что тренировать сегодня».
 * Опорные линии на 2, 4 и 7 днях: два раза в неделю на мышцу превосходит один раз по
 * гипертрофии, а пауза больше недели означает, что мышца выпала из плана.
 */
@Composable
internal fun MuscleFrequencyCard(
    state: AnalysisUiState,
    modifier: Modifier = Modifier,
) {
    val rows = state.report.muscleLoads
        .filter { it.sessionsPerWeek > 0.0 || it.daysSinceLast != null }
        .sortedByDescending { it.daysSinceLast ?: Int.MAX_VALUE }
    if (rows.isEmpty()) return

    AnalysisCard(
        title = "Частота и пауза",
        subtitle = "Дней с последней работы по мышце. Ориентир — два раза в неделю, то есть пауза 2–4 дня",
        icon = Icons.Rounded.Schedule,
        modifier = modifier,
    ) {
        rows.forEach { load ->
            FrequencyRow(load)
        }
    }
}

@Composable
private fun FrequencyRow(load: MuscleLoadSummary) {
    val colors = rememberChartColors()
    val maxDays = 14f
    val days = load.daysSinceLast?.toFloat()

    Row(
        modifier = Modifier.fillMaxWidth().height(30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = load.muscle.displayName(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(MUSCLE_LABEL_WIDTH),
        )
        Canvas(modifier = Modifier.weight(1f).height(22.dp)) {
            val centerY = size.height / 2f
            fun x(value: Float) = (value / maxDays).coerceIn(0f, 1f) * size.width

            drawLine(
                color = colors.track,
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 6.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            listOf(2f, 4f, 7f).forEach { landmark ->
                drawLine(
                    color = colors.grid,
                    start = Offset(x(landmark), centerY - 7.dp.toPx()),
                    end = Offset(x(landmark), centerY + 7.dp.toPx()),
                    strokeWidth = ChartSpec.GridWidth.toPx(),
                )
            }
            if (days != null) {
                val dotX = x(days)
                drawLine(
                    color = colors.mark.copy(alpha = 0.5f),
                    start = Offset(0f, centerY),
                    end = Offset(dotX, centerY),
                    strokeWidth = 6.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawCircle(color = colors.surface, radius = ChartSpec.MarkerRadius.toPx() + ChartSpec.MarkerRing.toPx(), center = Offset(dotX, centerY))
                drawCircle(
                    color = when {
                        days > 7f -> ChartPalette.TooLittle
                        days > 4f -> ChartPalette.Maintenance
                        else -> ChartPalette.Optimal
                    },
                    radius = ChartSpec.MarkerRadius.toPx(),
                    center = Offset(dotX, centerY),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = days?.let { "${it.toInt()} д" } ?: "—",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(34.dp),
        )
    }
}

/**
 * Ширина колонки названий мышц в строках-графиках. Подобрана под самое длинное название
 * («Разгибатели спины»); что не влезло, укорачивается многоточием, а не обрезается по букве.
 */
private val MUSCLE_LABEL_WIDTH = 130.dp
