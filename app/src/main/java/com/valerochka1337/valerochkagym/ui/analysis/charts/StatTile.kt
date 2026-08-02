package com.valerochka1337.valerochkagym.ui.analysis.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Плитка показателя: подпись, крупное значение, необязательное уточнение и спарклайн.
 *
 * Одно число — это не график: там, где раньше просился «столбик из одного столбца», стоит
 * плитка. Спарклайн даёт направление без осей и подписей.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    trend: List<Float> = emptyList(),
) {
    Column(modifier = modifier) {
        Text(
            // Подпись переносится, а не обрезается: «Средняя тренировка» на узком экране иначе
            // теряет последние буквы, а обрезанный текст хуже второй строки.
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (trend.size >= 2) {
            Spacer(Modifier.height(8.dp))
            Sparkline(values = trend, modifier = Modifier.fillMaxWidth().height(24.dp))
        }
    }
}

/** Мини-график без осей: только форма изменения, последняя точка — акцентом. */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
) {
    val colors = rememberChartColors()
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = stepX * index
            val y = size.height - ((value - min) / span) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = colors.markMuted.copy(alpha = 0.7f),
            style = Stroke(
                width = ChartSpec.LineWidth.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
        val lastY = size.height - ((values.last() - min) / span) * size.height
        drawCircle(
            color = colors.mark,
            radius = ChartSpec.MarkerRadius.toPx() * 0.7f,
            center = Offset(size.width, lastY),
        )
    }
}
