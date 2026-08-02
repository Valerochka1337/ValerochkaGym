package com.valerochka1337.valerochkagym.ui.analysis.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.ui.theme.GymMotion
import kotlin.math.abs

/** Точка линейного графика: [xMillis] — реальное время, поэтому паузы видны как разрывы шага. */
@Immutable
data class LinePoint(
    val xMillis: Long,
    val y: Float,
    val xLabel: String,
)

/**
 * Линейный график по времени с необязательной линией тренда и выбором точки тапом.
 *
 * Правила отрисовки: линия 2dp, маркеры ≥ 8dp с кольцом цветом поверхности, сплошная волосяная
 * сетка. Подписи — только по осям, у последней точки и у выбранной: число над каждой точкой
 * превращает график в кашу. Полные значения по всем точкам читаются в таблице под графиком,
 * поэтому выбор точки ничего не «запирает».
 *
 * Базовая линия не нулевая (см. [NiceScale]), поэтому заливки под линией нет: она читалась бы
 * как «объём от нуля» и завышала бы разницу.
 *
 * Линия тренда — обычная линейная регрессия по всем точкам; рисуется приглушённым цветом,
 * чтобы не спорить с данными, и только при ≥ 3 точках (по двум точкам тренда не бывает).
 */
@Composable
fun TrendLineChart(
    points: List<LinePoint>,
    modifier: Modifier = Modifier,
    height: Dp = 176.dp,
    selectedIndex: Int? = null,
    onSelect: (Int?) -> Unit = {},
    valueFormatter: (Float) -> String = { formatAxisValue(it) },
    showTrend: Boolean = true,
) {
    val colors = rememberChartColors()
    val labelStyle = chartLabelStyle()
    val textMeasurer = rememberTextMeasurer()

    // Смена серии (период/упражнение) — линия «прочерчивается» слева направо: клип по ширине
    // области данных. Сетка и подписи осей стоят, анимируются только данные.
    val revealSpec = GymMotion.spatialDefault<Float>()
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(points) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, revealSpec)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(points) {
                if (points.isEmpty()) return@pointerInput
                detectTapGestures { offset ->
                    val geometry = LineGeometry(
                        width = size.width.toFloat(),
                        height = size.height.toFloat(),
                        points = points,
                        density = this,
                    )
                    onSelect(geometry.nearestIndex(offset.x))
                }
            },
    ) {
        if (points.isEmpty()) return@Canvas
        val geometry = LineGeometry(size.width, size.height, points, density = this)
        drawGrid(geometry, colors, labelStyle, textMeasurer, valueFormatter)
        drawXLabels(geometry, points, labelStyle, textMeasurer)
        clipRect(right = geometry.plot.left + geometry.plot.width * reveal.value) {
            drawSeries(geometry, points, colors)
            if (showTrend && points.size >= 3) drawTrend(geometry, points, colors)
            drawMarkers(geometry, points, colors, selectedIndex)
        }
        drawCallouts(geometry, points, colors, labelStyle, textMeasurer, valueFormatter, selectedIndex)
    }
}

/**
 * Пересчёт данных в пиксели: поля под подписи, шкала значений и позиции точек. Считается один
 * раз на отрисовку и повторно — на тапе, чтобы попадание считалось по той же геометрии.
 */
private class LineGeometry(
    width: Float,
    height: Float,
    private val points: List<LinePoint>,
    density: androidx.compose.ui.unit.Density,
) {
    // Тренд рисуется в своём диапазоне, а не от нуля: иначе прирост тонет в пустом низе графика.
    val scale: NiceScale = NiceScale.forRange(
        rawMin = points.minOfOrNull { it.y } ?: 0f,
        rawMax = points.maxOfOrNull { it.y } ?: 1f,
        zeroBased = false,
    )

    private val leftGutter = with(density) { 40.dp.toPx() }
    private val bottomGutter = with(density) { 18.dp.toPx() }
    private val topPadding = with(density) { 18.dp.toPx() }
    private val rightPadding = with(density) { 8.dp.toPx() }

    val plot = Rect(
        left = leftGutter,
        top = topPadding,
        right = (width - rightPadding).coerceAtLeast(leftGutter + 1f),
        bottom = (height - bottomGutter).coerceAtLeast(topPadding + 1f),
    )

    private val minX = points.minOf { it.xMillis }
    private val maxX = points.maxOf { it.xMillis }

    fun x(index: Int): Float {
        if (points.size == 1) return plot.center.x
        val span = (maxX - minX).toFloat()
        // Все точки в один день (span == 0) — раскладываем равномерно, иначе они схлопнутся.
        val t = if (span <= 0f) index.toFloat() / (points.size - 1) else (points[index].xMillis - minX) / span
        return plot.left + t * plot.width
    }

    fun y(value: Float): Float = plot.bottom - scale.fraction(value) * plot.height

    fun nearestIndex(tapX: Float): Int =
        points.indices.minBy { abs(x(it) - tapX) }
}

private fun DrawScope.drawGrid(
    geometry: LineGeometry,
    colors: ChartColors,
    labelStyle: TextStyle,
    textMeasurer: TextMeasurer,
    valueFormatter: (Float) -> String,
) {
    val strokeWidth = ChartSpec.GridWidth.toPx()
    geometry.scale.ticks.forEach { tick ->
        val y = geometry.y(tick)
        drawLine(
            color = colors.grid,
            start = Offset(geometry.plot.left, y),
            end = Offset(geometry.plot.right, y),
            strokeWidth = strokeWidth,
        )
        val layout = textMeasurer.measure(valueFormatter(tick), labelStyle)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                x = geometry.plot.left - layout.size.width - 6.dp.toPx(),
                y = y - layout.size.height / 2f,
            ),
        )
    }
}

/** Подписи по времени — только края: середина всё равно нечитаема на узком экране. */
private fun DrawScope.drawXLabels(
    geometry: LineGeometry,
    points: List<LinePoint>,
    labelStyle: TextStyle,
    textMeasurer: TextMeasurer,
) {
    val first = textMeasurer.measure(points.first().xLabel, labelStyle)
    drawText(first, topLeft = Offset(geometry.plot.left, geometry.plot.bottom + 4.dp.toPx()))
    if (points.size > 1) {
        val last = textMeasurer.measure(points.last().xLabel, labelStyle)
        drawText(
            textLayoutResult = last,
            topLeft = Offset(geometry.plot.right - last.size.width, geometry.plot.bottom + 4.dp.toPx()),
        )
    }
}

private fun DrawScope.drawSeries(
    geometry: LineGeometry,
    points: List<LinePoint>,
    colors: ChartColors,
) {
    if (points.size < 2) return
    val line = Path()
    points.forEachIndexed { index, point ->
        val x = geometry.x(index)
        val y = geometry.y(point.y)
        if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }
    // Заливки под линией нет намеренно: она читается как «объём от нуля», а базовая линия
    // здесь не нулевая.
    drawPath(
        path = line,
        color = colors.mark,
        style = Stroke(
            width = ChartSpec.LineWidth.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        ),
    )
}

/** Линия тренда: обычный МНК по (индекс времени, значение), обрезанная границами графика. */
private fun DrawScope.drawTrend(
    geometry: LineGeometry,
    points: List<LinePoint>,
    colors: ChartColors,
) {
    val xs = points.indices.map { geometry.x(it) }
    val ys = points.map { it.y }
    val n = points.size
    val meanX = xs.average().toFloat()
    val meanY = ys.average().toFloat()
    var numerator = 0f
    var denominator = 0f
    for (i in 0 until n) {
        val dx = xs[i] - meanX
        numerator += dx * (ys[i] - meanY)
        denominator += dx * dx
    }
    if (denominator <= 0f) return
    val slope = numerator / denominator
    val startValue = meanY + slope * (geometry.plot.left - meanX)
    val endValue = meanY + slope * (geometry.plot.right - meanX)
    drawLine(
        color = colors.markMuted.copy(alpha = 0.6f),
        start = Offset(geometry.plot.left, geometry.y(startValue.coerceIn(geometry.scale.min, geometry.scale.max))),
        end = Offset(geometry.plot.right, geometry.y(endValue.coerceIn(geometry.scale.min, geometry.scale.max))),
        strokeWidth = ChartSpec.GridWidth.toPx() * 1.5f,
    )
}

private fun DrawScope.drawMarkers(
    geometry: LineGeometry,
    points: List<LinePoint>,
    colors: ChartColors,
    selectedIndex: Int?,
) {
    val ring = ChartSpec.MarkerRing.toPx()
    points.forEachIndexed { index, point ->
        val isEmphasized = index == points.lastIndex || index == selectedIndex
        // Промежуточные точки рисуем мелкими: важны линия и края, а не каждая засечка.
        val radius = if (isEmphasized) ChartSpec.MarkerRadius.toPx() else ChartSpec.MarkerRadius.toPx() * 0.6f
        val center = Offset(geometry.x(index), geometry.y(point.y))
        drawCircle(color = colors.surface, radius = radius + ring, center = center)
        drawCircle(color = colors.mark, radius = radius, center = center)
    }
}

/** Прямые подписи: значение последней точки всегда, выбранной — вместе с вертикалью. */
private fun DrawScope.drawCallouts(
    geometry: LineGeometry,
    points: List<LinePoint>,
    colors: ChartColors,
    labelStyle: TextStyle,
    textMeasurer: TextMeasurer,
    valueFormatter: (Float) -> String,
    selectedIndex: Int?,
) {
    val index = selectedIndex?.coerceIn(points.indices) ?: points.lastIndex
    val point = points[index]
    val x = geometry.x(index)
    if (selectedIndex != null) {
        drawLine(
            color = colors.grid,
            start = Offset(x, geometry.plot.top),
            end = Offset(x, geometry.plot.bottom),
            strokeWidth = ChartSpec.GridWidth.toPx(),
        )
    }
    val text = "${valueFormatter(point.y)} · ${point.xLabel}"
    val layout = textMeasurer.measure(text, labelStyle.copy(color = colors.labelStrong))
    val left = (x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
    drawText(layout, topLeft = Offset(left, 0f))
}
