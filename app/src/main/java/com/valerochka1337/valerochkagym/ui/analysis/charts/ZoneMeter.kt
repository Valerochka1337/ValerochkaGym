package com.valerochka1337.valerochkagym.ui.analysis.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.ui.theme.GymMotion

/** Диапазон шкалы измерителя: [from]..[to] в единицах значения, залитый [color]. */
@Immutable
data class MeterZone(
    val from: Float,
    val to: Float,
    val color: Color,
)

/**
 * Полоса-измеритель с зонами: показывает одно значение относительно опорных диапазонов (например
 * отношение острой нагрузки к хронической и его «безопасный коридор»).
 *
 * Дорожка — тон поверхности, зоны — заливки поверх неё, значение — вертикальная метка с кольцом
 * цветом поверхности, чтобы читаться на любой зоне. Подписи границ идут текстом под полосой: цвет
 * зоны сам по себе ничего не сообщает, пока не сказано, где границы.
 */
@Composable
fun ZoneMeter(
    value: Float,
    min: Float,
    max: Float,
    zones: List<MeterZone>,
    boundaryLabels: List<String>,
    modifier: Modifier = Modifier,
) {
  val colors = rememberChartColors()
  // Метка скользит к новому значению по пружине: зоны и подписи неподвижны.
  val animatedValue by
      animateFloatAsState(
          targetValue = value,
          animationSpec = GymMotion.spatialDefault(),
          label = "zone-meter-value",
      )
  Column(modifier = modifier.fillMaxWidth()) {
    Canvas(
        modifier = Modifier.fillMaxWidth().height(18.dp),
    ) {
      val span = (max - min).takeIf { it > 0f } ?: 1f
      val trackHeight = 10.dp.toPx()
      val top = (size.height - trackHeight) / 2f
      val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)

      drawRoundRect(
          color = colors.track,
          topLeft = Offset(0f, top),
          size = Size(size.width, trackHeight),
          cornerRadius = radius,
      )

      zones.forEach { zone ->
        val left = ((zone.from - min) / span).coerceIn(0f, 1f) * size.width
        val right = ((zone.to - min) / span).coerceIn(0f, 1f) * size.width
        if (right <= left) return@forEach
        drawRoundRect(
            color = zone.color,
            topLeft = Offset(left, top),
            size = Size(right - left, trackHeight),
            cornerRadius = radius,
        )
      }

      val markerX = ((animatedValue - min) / span).coerceIn(0f, 1f) * size.width
      val markerWidth = 4.dp.toPx()
      val ring = ChartSpec.MarkerRing.toPx()
      val markerLeft = (markerX - markerWidth / 2f).coerceIn(0f, size.width - markerWidth)
      drawRoundRect(
          color = colors.surface,
          topLeft = Offset(markerLeft - ring, top - ring),
          size = Size(markerWidth + ring * 2, trackHeight + ring * 2),
          cornerRadius = CornerRadius(markerWidth, markerWidth),
      )
      drawRoundRect(
          color = colors.labelStrong,
          topLeft = Offset(markerLeft, top - ring / 2f),
          size = Size(markerWidth, trackHeight + ring),
          cornerRadius = CornerRadius(markerWidth, markerWidth),
      )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      boundaryLabels.forEach { label ->
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
      }
    }
  }
}
