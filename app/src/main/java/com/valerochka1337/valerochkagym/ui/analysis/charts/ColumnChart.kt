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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.ui.theme.GymMotion

/**
 * Столбец графика. [partial] помечает незакрытый период (текущая неделя): он рисуется приглушённым,
 * чтобы неполные данные не читались как провал.
 */
@Immutable
data class ColumnDatum(
    val label: String,
    val value: Float,
    val partial: Boolean = false,
)

/**
 * Столбчатый график по периодам (неделям). Один ряд данных — значит один цвет для всех столбцов и
 * никакой легенды: подпись карточки уже говорит, что отложено.
 *
 * Опорная линия [referenceValue] (например средняя за период) — сплошная волосяная линия поверх
 * столбцов: она отвечает на вопрос «эта неделя выше или ниже обычного», ради которого иначе
 * пришлось бы вводить вторую ось.
 */
@Composable
fun ColumnChart(
    data: List<ColumnDatum>,
    modifier: Modifier = Modifier,
    height: Dp = 168.dp,
    referenceValue: Float? = null,
    selectedIndex: Int? = null,
    onSelect: (Int?) -> Unit = {},
    valueFormatter: (Float) -> String = { formatAxisValue(it) },
    labelEveryColumn: Boolean = false,
) {
  val colors = rememberChartColors()
  val labelStyle = chartLabelStyle()
  val textMeasurer = rememberTextMeasurer()

  // Смена данных (период/метрика) — столбцы вырастают от базовой линии заново, а не скачком.
  // Шкала и сетка при этом стоят: анимируется только длина столбца.
  val revealSpec = GymMotion.spatialDefault<Float>()
  val reveal = remember { Animatable(0f) }
  LaunchedEffect(data) {
    reveal.snapTo(0f)
    reveal.animateTo(1f, revealSpec)
  }

  Canvas(
      modifier =
          modifier
              .fillMaxWidth()
              .height(height)
              .semantics {
                contentDescription = "Столбчатый график, ${data.size} значений"
                stateDescription =
                    selectedIndex?.let { index ->
                      data.getOrNull(index)?.let { item ->
                        "Выбрано ${item.label}, ${valueFormatter(item.value)}"
                      }
                    } ?: "Столбец не выбран"
                customActions =
                    data.mapIndexed { index, item ->
                      CustomAccessibilityAction(
                          label = "Выбрать ${item.label}: ${valueFormatter(item.value)}",
                      ) {
                        onSelect(index)
                        true
                      }
                    }
              }
              .pointerInput(data) {
                if (data.isEmpty()) return@pointerInput
                detectTapGestures { offset ->
                  val geometry =
                      ColumnGeometry(
                          width = size.width.toFloat(),
                          height = size.height.toFloat(),
                          data = data,
                          density = this,
                      )
                  onSelect(geometry.indexAt(offset.x))
                }
              },
  ) {
    if (data.isEmpty()) return@Canvas
    val geometry = ColumnGeometry(size.width, size.height, data, this)
    drawColumnGrid(geometry, colors, labelStyle, textMeasurer, valueFormatter)
    drawColumns(geometry, data, colors, selectedIndex, reveal.value)
    referenceValue?.let { drawReference(geometry, it, colors) }
    if (labelEveryColumn) {
      drawColumnLabels(geometry, data, labelStyle, textMeasurer)
    } else {
      drawEdgeLabels(geometry, data, labelStyle, textMeasurer)
    }
    drawSelectedValue(
        geometry,
        data,
        colors,
        labelStyle,
        textMeasurer,
        valueFormatter,
        selectedIndex,
    )
  }
}

private class ColumnGeometry(
    width: Float,
    height: Float,
    private val data: List<ColumnDatum>,
    density: androidx.compose.ui.unit.Density,
) {
  val scale: NiceScale = NiceScale.forRange(0f, data.maxOfOrNull { it.value } ?: 1f)

  private val leftGutter = with(density) { 40.dp.toPx() }
  private val bottomGutter = with(density) { 18.dp.toPx() }
  private val topPadding = with(density) { 18.dp.toPx() }

  val plot =
      Rect(
          left = leftGutter,
          top = topPadding,
          right = width,
          bottom = (height - bottomGutter).coerceAtLeast(topPadding + 1f),
      )

  val slotWidth: Float
    get() = plot.width / data.size

  /**
   * Толщина столбца: не толще [ChartSpec.MaxBarWidth] и не больше 60% слота — столбец никогда не
   * заполняет свою полосу целиком, воздух между столбцами и есть разделитель.
   */
  val barWidth: Float =
      minOf(
              with(density) { ChartSpec.MaxBarWidth.toPx() },
              (plot.width / data.size) * 0.6f,
          )
          .coerceAtLeast(2f)

  fun centerX(index: Int): Float = plot.left + slotWidth * (index + 0.5f)

  fun y(value: Float): Float = plot.bottom - scale.fraction(value) * plot.height

  fun indexAt(tapX: Float): Int =
      (((tapX - plot.left) / slotWidth).toInt()).coerceIn(0, data.lastIndex)
}

private fun DrawScope.drawColumnGrid(
    geometry: ColumnGeometry,
    colors: ChartColors,
    labelStyle: TextStyle,
    textMeasurer: TextMeasurer,
    valueFormatter: (Float) -> String,
) {
  geometry.scale.ticks.forEach { tick ->
    val y = geometry.y(tick)
    drawLine(
        color = colors.grid,
        start = Offset(geometry.plot.left, y),
        end = Offset(geometry.plot.right, y),
        strokeWidth = ChartSpec.GridWidth.toPx(),
    )
    val layout = textMeasurer.measure(valueFormatter(tick), labelStyle)
    drawText(
        textLayoutResult = layout,
        topLeft =
            Offset(
                geometry.plot.left - layout.size.width - 6.dp.toPx(),
                y - layout.size.height / 2f,
            ),
    )
  }
}

private fun DrawScope.drawColumns(
    geometry: ColumnGeometry,
    data: List<ColumnDatum>,
    colors: ChartColors,
    selectedIndex: Int?,
    reveal: Float,
) {
  val corner = CornerRadius(ChartSpec.BarCorner.toPx(), ChartSpec.BarCorner.toPx())
  data.forEachIndexed { index, datum ->
    if (datum.value <= 0f) return@forEachIndexed
    val top = geometry.y(datum.value * reveal)
    val left = geometry.centerX(index) - geometry.barWidth / 2f
    val rect = Rect(left, top, left + geometry.barWidth, geometry.plot.bottom)
    val path =
        Path().apply {
          addRoundRect(
              RoundRect(
                  rect = rect,
                  topLeft = corner,
                  topRight = corner,
                  bottomRight = CornerRadius.Zero,
                  bottomLeft = CornerRadius.Zero,
              ),
          )
        }
    val alpha =
        when {
          datum.partial -> 0.45f
          selectedIndex != null && selectedIndex != index -> 0.55f
          else -> 1f
        }
    drawPath(path, color = colors.mark.copy(alpha = alpha))
  }
}

private fun DrawScope.drawReference(
    geometry: ColumnGeometry,
    value: Float,
    colors: ChartColors,
) {
  val y = geometry.y(value)
  drawLine(
      color = colors.markMuted.copy(alpha = 0.7f),
      start = Offset(geometry.plot.left, y),
      end = Offset(geometry.plot.right, y),
      strokeWidth = ChartSpec.GridWidth.toPx() * 1.5f,
  )
}

/** Подпись под каждым столбцом — для коротких шкал вроде числа повторений. */
private fun DrawScope.drawColumnLabels(
    geometry: ColumnGeometry,
    data: List<ColumnDatum>,
    labelStyle: TextStyle,
    textMeasurer: TextMeasurer,
) {
  var lastRight = -Float.MAX_VALUE
  data.forEachIndexed { index, datum ->
    val layout = textMeasurer.measure(datum.label, labelStyle)
    val left = geometry.centerX(index) - layout.size.width / 2f
    // Подпись рисуется только если не наезжает на предыдущую: обрезанные цифры хуже пропуска.
    if (left < lastRight + 4.dp.toPx()) return@forEachIndexed
    drawText(layout, topLeft = Offset(left, geometry.plot.bottom + 4.dp.toPx()))
    lastRight = left + layout.size.width
  }
}

private fun DrawScope.drawEdgeLabels(
    geometry: ColumnGeometry,
    data: List<ColumnDatum>,
    labelStyle: TextStyle,
    textMeasurer: TextMeasurer,
) {
  val first = textMeasurer.measure(data.first().label, labelStyle)
  drawText(first, topLeft = Offset(geometry.plot.left, geometry.plot.bottom + 4.dp.toPx()))
  if (data.size > 1) {
    val last = textMeasurer.measure(data.last().label, labelStyle)
    drawText(
        textLayoutResult = last,
        topLeft = Offset(geometry.plot.right - last.size.width, geometry.plot.bottom + 4.dp.toPx()),
    )
  }
}

private fun DrawScope.drawSelectedValue(
    geometry: ColumnGeometry,
    data: List<ColumnDatum>,
    colors: ChartColors,
    labelStyle: TextStyle,
    textMeasurer: TextMeasurer,
    valueFormatter: (Float) -> String,
    selectedIndex: Int?,
) {
  val index = selectedIndex?.coerceIn(data.indices) ?: data.lastIndex
  val datum = data[index]
  val text = "${valueFormatter(datum.value)} · ${datum.label}"
  val layout = textMeasurer.measure(text, labelStyle.copy(color = colors.labelStrong))
  val left =
      (geometry.centerX(index) - layout.size.width / 2f).coerceIn(
          0f,
          (size.width - layout.size.width).coerceAtLeast(0f),
      )
  drawText(layout, topLeft = Offset(left, 0f))
}
