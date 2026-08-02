package com.valerochka1337.valerochkagym.ui.analysis.charts

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Общие правила отрисовки графиков (одни и те же во всех карточках вкладки «Анализы»):
 * тонкие метки, сплошная волосяная сетка на тон от поверхности, подписи — текстовыми токенами,
 * а не цветом данных.
 */
object ChartSpec {
    /** Толщина линии графика. */
    val LineWidth: Dp = 2.dp

    /** Волосяная линия сетки и осей — сплошная, никогда не пунктир. */
    val GridWidth: Dp = 1.dp

    /** Радиус точки-маркера (диаметр ≥ 8dp, как требует минимальный размер метки). */
    val MarkerRadius: Dp = 4.dp

    /** Кольцо цветом поверхности вокруг маркера, чтобы он читался поверх линии. */
    val MarkerRing: Dp = 2.dp

    /** Максимальная толщина столбца: столбец никогда не заполняет слот целиком. */
    val MaxBarWidth: Dp = 24.dp

    /** Скругление «конца данных» столбца; у базовой линии угол остаётся прямым. */
    val BarCorner: Dp = 4.dp
}

/** Цвета графика, взятые из темы один раз — чтобы в отрисовке не читать `MaterialTheme`. */
@Immutable
data class ChartColors(
    val mark: Color,
    val markMuted: Color,
    val grid: Color,
    val surface: Color,
    val track: Color,
    val labelStrong: Color,
)

@Composable
fun rememberChartColors(): ChartColors = ChartColors(
    mark = MaterialTheme.colorScheme.primary,
    markMuted = MaterialTheme.colorScheme.onSurfaceVariant,
    grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
    surface = MaterialTheme.colorScheme.surfaceContainerHigh,
    track = MaterialTheme.colorScheme.surfaceContainerHighest,
    labelStrong = MaterialTheme.colorScheme.onSurface,
)

/** Стиль подписей осей: мелкий, нейтральный, с моноширинными цифрами по колонкам значений. */
@Composable
fun chartLabelStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * Округлённая шкала значений: границы и шаг подобраны так, чтобы подписи получались «круглыми»
 * (0 / 50 / 100), а не 0 / 47.3 / 94.6.
 *
 * Ноль включается по требованию ([Companion.forRange] с `zeroBased`). Для столбцов он
 * обязателен — длина столбца иначе врёт о величине. Для линии тренда, наоборот, вреден: рост
 * силы на 20% от собственного веса штанги на шкале «от нуля» превращается в плоскую линию.
 * Поэтому тренд рисуется в своём диапазоне, а честность обеспечивают подписи оси.
 */
@Immutable
data class NiceScale(
    val min: Float,
    val max: Float,
    val step: Float,
) {
    val ticks: List<Float>
        get() {
            if (step <= 0f || !step.isFinite()) return listOf(min, max)
            // Считаем по индексу, а не накоплением `value += step`: если шаг мельче точности
            // Float на этом порядке величины, сложение не сдвигает значение и цикл никогда не
            // заканчивается — список растёт до OutOfMemory. Плюс индекс не копит ошибку.
            // Допуск в полшага защищает от потери верхней метки на той же ошибке.
            val count = ((max - min) / step + 0.5f).toInt().coerceIn(0, MAX_TICKS)
            return List(count + 1) { min + it * step }
        }

    /** Доля 0..1 значения [value] на шкале — готовая координата по вертикали. */
    fun fraction(value: Float): Float {
        val span = max - min
        return if (span <= 0f) 0f else ((value - min) / span).coerceIn(0f, 1f)
    }

    companion object {
        /**
         * Шкала, покрывающая [rawMin]..[rawMax] примерно с [targetTicks] делениями. При
         * [zeroBased] нижняя граница притягивается к нулю. Пустые/нулевые данные дают шкалу
         * 0..1, чтобы график не схлопнулся.
         */
        fun forRange(
            rawMin: Float,
            rawMax: Float,
            targetTicks: Int = 4,
            zeroBased: Boolean = true,
        ): NiceScale {
            if (!rawMin.isFinite() || !rawMax.isFinite()) return NiceScale(0f, 1f, 0.5f)
            val low = if (zeroBased) minOf(0f, rawMin) else rawMin
            val high = maxOf(rawMax, low)
            // Плоские данные — одна точка или несколько одинаковых значений. Строить шкалу
            // по «волосяному» диапазону нельзя: шаг выходит мельче точности Float на этом
            // порядке величины, и ось получается из одинаковых подписей. Разворачиваем шкалу
            // вокруг самого значения — линия ложится в середину графика.
            if (high - low <= abs(high) * FLAT_SPAN) {
                val pad = maxOf(abs(high) * FLAT_PAD, 1f)
                return nice(if (zeroBased) 0f else high - pad, high + pad, targetTicks)
            }
            return nice(low, high, targetTicks)
        }

        /** Границы, округлённые вниз и вверх до «человеческого» шага. */
        private fun nice(low: Float, high: Float, targetTicks: Int): NiceScale {
            val step = niceStep((high - low) / targetTicks.coerceAtLeast(1))
            return NiceScale(floor(low / step) * step, ceil(high / step) * step, step)
        }

        /** Ближайший «человеческий» шаг: 1, 2, 2.5 или 5 на соответствующем порядке величины. */
        private fun niceStep(raw: Float): Float {
            if (raw <= 0f) return 1f
            val magnitude = 10f.pow(floor(log10(raw.toDouble())).toFloat())
            val normalized = raw / magnitude
            val nice = when {
                normalized <= 1f -> 1f
                normalized <= 2f -> 2f
                normalized <= 2.5f -> 2.5f
                normalized <= 5f -> 5f
                else -> 10f
            }
            return nice * magnitude
        }

        /** Больше меток ни на одном графике не читается, а на вырожденных данных это страховка. */
        private const val MAX_TICKS = 64

        /** Разброс, ниже которого данные считаются одинаковыми — сотые доли процента значения. */
        private const val FLAT_SPAN = 1e-4f

        /** На сколько разворачивать шкалу вокруг одинаковых значений. */
        private const val FLAT_PAD = 0.05f
    }
}

/** Число для подписи: без хвостовых нулей, максимум один знак после запятой. */
fun formatAxisValue(value: Float): String {
    val rounded = (value * 10f).toLong() / 10f
    return if (abs(rounded - rounded.toLong()) < 1e-3f) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}
