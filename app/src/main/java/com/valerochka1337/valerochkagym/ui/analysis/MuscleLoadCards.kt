package com.valerochka1337.valerochkagym.ui.analysis

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import com.valerochka1337.valerochkagym.domain.analysis.HypertrophyVolumeGuide
import com.valerochka1337.valerochkagym.domain.analysis.MuscleLoadSummary
import com.valerochka1337.valerochkagym.domain.analysis.VolumeZone
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.analysis.body.BodyMapFlip
import com.valerochka1337.valerochkagym.ui.analysis.body.MuscleSelector
import com.valerochka1337.valerochkagym.ui.analysis.charts.ChartSpec
import com.valerochka1337.valerochkagym.ui.analysis.charts.rememberChartColors
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.theme.ChartPalette
import com.valerochka1337.valerochkagym.ui.theme.GymMotion

/**
 * Тепловая карта тела: интерактивный человек, закрашенный по недельному объёму каждой мышцы.
 *
 * Шкала ступенчатая и подписана легендой, а числа выбранной мышцы выводятся текстом под картой —
 * цвет здесь нигде не остаётся единственным носителем смысла. Тёмно-зелёный — ориентир для
 * роста, а не верхний лимит или оценка восстановления.
 */
@Composable
internal fun MuscleHeatmapCard(
    state: AnalysisUiState,
    onMuscleClicked: (Muscle?) -> Unit,
    onSelectorSelected: (Muscle) -> Unit = { onMuscleClicked(it) },
    modifier: Modifier = Modifier,
) {
    // Производные от отчёта, а не от выбора: пересобирать их на каждый тап по карте незачем.
    val loads = remember(state.report) { state.report.muscleLoads.associateBy { it.muscle } }

    AnalysisCard(
        title = "Карта нагрузки",
        subtitle = "Эффективные подходы в неделю: прямой вклад — 1, косвенный — 0,5",
        icon = Icons.Rounded.Accessibility,
        modifier = modifier,
    ) {
        BodyMapFlip(
            fillFor = { muscle -> ChartPalette.zoneColor(loads[muscle]?.zone ?: VolumeZone.LOW) },
            selectedMuscle = state.selectedMuscle,
            onMuscleClick = onMuscleClicked,
        )

        MuscleSelector(
            selected = state.selectedMuscle,
            roleText = { muscle -> "${"%.1f".format(loads[muscle]?.weeklySets ?: 0.0)} эффективных подходов" },
            onSelected = onSelectorSelected,
        )

        Spacer(Modifier.height(12.dp))
        HeatLegend()
        Spacer(Modifier.height(14.dp))
        EffectiveSetsExplanation()
        Spacer(Modifier.height(14.dp))
        SelectedMuscleDetails(
            state.selectedMuscleLoad,
            modifier = Modifier.animateContentSize(GymMotion.spatialDefault()),
        )
    }
}

/** Правила, по которым рабочий подход превращается в вклад в конкретную мышцу. */
@Composable
private fun EffectiveSetsExplanation() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Как считаем",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Это не оценка качества подхода: кардио и разминка легче 60% лучшего веса " +
                "упражнения не попадают в расчёт.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "На одну мышцу: основная — 1 подход, вторичная — 0,5, стабилизатор — 0.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = ChartPalette.zoneColor(load.zone),
            )
        }
        ValueRow(
            label = "Подходов в неделю",
            value = formatDecimal(load.weeklySets),
            accent = true,
        )
        ValueRow(
            label = "Границы шкалы",
            value = "2 · 5 · 10",
        )
        ValueBlock(
            label = "Как читать",
            value = "0–2 — малый; >2–<5 — базовый; 5–<10 — рабочий; ≥10 — ориентир для роста.",
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
 * Объём по мышцам в виде bullet-графика: фактическое значение поверх общей шкалы 2 / 5 / 10.
 *
 * Строки отсортированы от меньшего объёма к большему: это снимок распределения, а не рецепт
 * «добрать до потолка». Больший объём в среднем помогает гипертрофии с убывающей отдачей, но
 * единого оптимума или максимального восстанавливаемого объёма не существует.
 */
@Composable
internal fun MuscleVolumeCard(
    state: AnalysisUiState,
    onMuscleClicked: (Muscle?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = state.report.muscleLoads.sortedBy { it.weeklySets }

    AnalysisCard(
        title = "Объём по мышцам",
        subtitle = "Эффективные подходы в неделю — ориентир для гипертрофии, не диагноз тренировки",
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
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Больше объём в среднем помогает росту, но отдача снижается; единого " +
                "универсального оптимума или максимального восстанавливаемого объёма нет. " +
                "Основа: мета-анализ 2026 (PMID 41343037).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Приложение не знает близость подходов к отказу, технику, сон и восстановление. " +
                "При стабильном прогрессе меньший объём не делает программу неправильной.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MuscleBulletRow(
    load: MuscleLoadSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = rememberChartColors()
    val haptics = gymHaptics()
    val fill = ChartPalette.zoneColor(load.zone)
    val maxValue = maxOf(HypertrophyVolumeGuide.WORKING_MAX, load.weeklySets) * 1.05

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
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
                .heightIn(min = 48.dp)
                .clickable {
                    haptics.tap()
                    onClick()
                },
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
                // Фон показывает четыре честные ступени; метки 2 / 5 / 10 остаются читаемыми
                // и при значениях выше ориентира — это не верхний лимит.
                listOf(
                    Triple(0.0, HypertrophyVolumeGuide.LOW_MAX, ChartPalette.LowVolume),
                    Triple(
                        HypertrophyVolumeGuide.LOW_MAX,
                        HypertrophyVolumeGuide.BASE_MAX,
                        ChartPalette.BaseVolume,
                    ),
                    Triple(
                        HypertrophyVolumeGuide.BASE_MAX,
                        HypertrophyVolumeGuide.WORKING_MAX,
                        ChartPalette.WorkingVolume,
                    ),
                    Triple(HypertrophyVolumeGuide.WORKING_MAX, maxValue, ChartPalette.GrowthGuideVolume),
                ).forEach { (start, end, color) ->
                    if (end > start) {
                        drawRect(
                            color = color.copy(alpha = 0.18f),
                            topLeft = Offset(x(start), top),
                            size = Size(x(end) - x(start), trackHeight),
                        )
                    }
                }
                listOf(
                    HypertrophyVolumeGuide.LOW_MAX,
                    HypertrophyVolumeGuide.BASE_MAX,
                    HypertrophyVolumeGuide.WORKING_MAX,
                ).forEach { landmark ->
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
 * Отсортировано по паузе вниз, поэтому первые строки — это мягкое напоминание, что давно не
 * было работы. При равном объёме частота сама по себе не даёт значимого преимущества для
 * гипертрофии; раз в неделю может быть достаточно при умеренном объёме.
 *
 * Основание: https://pubmed.ncbi.nlm.nih.gov/30558493/ и position stand IUSCA:
 * https://journal.iusca.org/index.php/Journal/article/download/81/140/5323
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
        subtitle = "До недели — обычный ритм; дальше — мягкое напоминание. Раз в неделю может " +
            "быть достаточно при умеренном объёме: при равном объёме частота сама по себе не определяет рост",
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
            listOf(7f).forEach { landmark ->
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
                        days > 7f -> ChartPalette.Maintenance
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
