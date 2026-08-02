package com.valerochka1337.valerochkagym.ui.analysis.body

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.ui.theme.ChartPalette

/** Соотношение сторон фигуры — задаётся один раз, чтобы карта не растягивалась. */
private val BODY_ASPECT = BodyGeometry.WIDTH / BodyGeometry.HEIGHT

/**
 * Две фигуры рядом: вид спереди и вид сзади с общей раскраской и общим выбором мышцы.
 *
 * Обе стороны показываются сразу, без переключателя: карта отвечает на вопрос «где я недобираю»,
 * а он про всё тело. С переключателем половина ответа всегда спрятана за лишним нажатием, и
 * сравнить перед и зад глазами невозможно.
 */
@Composable
fun BodyMapPair(
    fillFor: (Muscle) -> Color,
    modifier: Modifier = Modifier,
    selectedMuscle: Muscle? = null,
    outlined: Set<Muscle> = emptySet(),
    onMuscleClick: ((Muscle?) -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BodyView.entries.forEach { view ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BodyMap(
                    view = view,
                    fillFor = fillFor,
                    selectedMuscle = selectedMuscle,
                    outlined = outlined,
                    onMuscleClick = onMuscleClick,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = view.title(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Интерактивная карта тела: силуэт с закрашенными областями мышц и выбором мышцы тапом.
 *
 * Один компонент на два сценария — тепловая карта нагрузки во вкладке «Анализы» и разметка
 * мышц при создании упражнения. Отличаются они только тем, какой цвет возвращает [fillFor],
 * поэтому раскраска вынесена в параметр, а геометрия и попадания живут здесь.
 *
 * Соседние области разделяются не обводкой, а зазором цветом подложки — рамка вокруг каждой
 * мышцы добавила бы «чернил», которые не несут данных. Обводка остаётся зарезервированной за
 * двумя состояниями: выбранная мышца ([selectedMuscle]) и предупреждение ([outlined]).
 *
 * Тап вне любой области (голова, кисти, колени) отдаёт `null` — это осознанный сброс выбора,
 * а не промах.
 */
@Composable
fun BodyMap(
    view: BodyView,
    fillFor: (Muscle) -> Color,
    modifier: Modifier = Modifier,
    selectedMuscle: Muscle? = null,
    outlined: Set<Muscle> = emptySet(),
    onMuscleClick: ((Muscle?) -> Unit)? = null,
) {
    val regions = remember(view) { BodyGeometry.regions(view) }
    val silhouette = BodyGeometry.silhouette
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val body = MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.outline
    val selectionColor = MaterialTheme.colorScheme.onSurface
    val overloadColor = ChartPalette.Overload

    Canvas(
        modifier = modifier
            .aspectRatio(BODY_ASPECT)
            .semantics { contentDescription = "Карта тела, ${view.title().lowercase()}" }
            .then(
                if (onMuscleClick == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(view) {
                        detectTapGestures { offset ->
                            val transform = BodyTransform(size.width.toFloat(), size.height.toFloat())
                            onMuscleClick(transform.muscleAt(offset.x, offset.y, view))
                        }
                    }
                },
            ),
    ) {
        val transform = BodyTransform(size.width, size.height)
        // Толщина щели между мышцами задаётся в единицах фигуры, а не в dp: иначе на узкой
        // карте она съедала бы мелкие пучки, а на широкой пропадала.
        val gap = transform.lengthOf(0.9f)

        // Части силуэта рисуются по очереди «заливка и контур»: корпус идёт последним и закрывает
        // верхний край руки, поэтому стык не виден, а просвет между рукой и боком — виден.
        silhouette.forEach { part ->
            val path = transform.path(part)
            drawPath(path, color = body)
            drawPath(path, color = outline.copy(alpha = 0.45f), style = Stroke(gap))
        }

        regions.forEach { region ->
            val color = fillFor(region.muscle)
            region.shapes.forEach { shape ->
                val path = transform.path(shape)
                drawPath(path, color = color)
                // Зазор цветом подложки: соседние мышцы соприкасаются краями и без него
                // сливались бы в одно пятно.
                drawPath(path, color = body, style = Stroke(gap, join = StrokeJoin.Round))
            }
        }

        regions.filter { it.muscle in outlined }.forEach { region ->
            region.shapes.forEach { shape ->
                drawPath(
                    transform.path(shape),
                    color = overloadColor,
                    style = Stroke(gap * 1.6f, join = StrokeJoin.Round),
                )
            }
        }

        regions.filter { it.muscle == selectedMuscle }.forEach { region ->
            region.shapes.forEach { shape ->
                val path = transform.path(shape)
                drawPath(path, color = surface, style = Stroke(gap * 2.6f, join = StrokeJoin.Round))
                drawPath(path, color = selectionColor, style = Stroke(gap * 1.6f, join = StrokeJoin.Round))
            }
        }
    }
}

private fun BodyView.title(): String = if (this == BodyView.FRONT) "Спереди" else "Сзади"

/**
 * Перевод нормированных координат фигуры в пиксели канвы: единый масштаб по обеим осям
 * (фигура не растягивается) и центрирование по остатку.
 */
private class BodyTransform(width: Float, height: Float) {
    private val scale = minOf(width / BodyGeometry.WIDTH, height / BodyGeometry.HEIGHT)
    private val dx = (width - BodyGeometry.WIDTH * scale) / 2f
    private val dy = (height - BodyGeometry.HEIGHT * scale) / 2f

    /** Длина в пикселях канвы для длины в единицах фигуры. */
    fun lengthOf(value: Float): Float = value * scale

    fun path(shape: BodyShape): Path = Path().apply {
        val cubics = shape.cubics
        if (cubics.isEmpty()) return@apply
        moveTo(dx + cubics[0] * scale, dy + cubics[1] * scale)
        var i = 0
        while (i < cubics.size) {
            cubicTo(
                dx + cubics[i + 2] * scale,
                dy + cubics[i + 3] * scale,
                dx + cubics[i + 4] * scale,
                dy + cubics[i + 5] * scale,
                dx + cubics[i + 6] * scale,
                dy + cubics[i + 7] * scale,
            )
            i += 8
        }
        close()
    }

    /** Мышца под точкой канвы или `null`, если попали в незакрашенную часть силуэта. */
    fun muscleAt(x: Float, y: Float, view: BodyView): Muscle? {
        if (scale <= 0f) return null
        return BodyGeometry.muscleAt(view, (x - dx) / scale, (y - dy) / scale)
    }
}
