package com.valerochka1337.valerochkagym.ui.analysis.body

/**
 * Замкнутая гладкая фигура карты тела в нормированных координатах.
 *
 * Хранится опорными точками, а рисуется кубическими кривыми: через опорные точки проводится
 * замкнутый сплайн Катмулла–Рома, который переводится в кубические Безье один в один. Это даёт
 * округлый «анатомический» контур из десятка чисел вместо сотни вершин ломаной — и правится
 * он тоже по десятку чисел, а не по каждой вершине.
 *
 * Та же фигура нужна для попадания тапа, поэтому кривые разворачиваются в ломаную ([polygon]):
 * точный тест «точка внутри кривой» потребовал бы операций над `Path`, а ломаная из выборки
 * по кривой отличается от неё меньше чем на полпикселя при реальных размерах карты.
 */
class BodyShape private constructor(
    private val anchors: FloatArray,
    private val tension: Float,
) {

    /**
     * Кубические сегменты подряд: по восемь чисел (начало, две контрольные точки, конец).
     * Конец сегмента совпадает с началом следующего — так проще и рисовать, и разворачивать.
     */
    val cubics: FloatArray by lazy { spline() }

    /** Ломаная по кривой — для теста попадания тапа. */
    val polygon: FloatArray by lazy { flatten() }

    /**
     * Точка внутри фигуры? Луч вправо: нечётное число пересечений с ломаной — точка внутри.
     * Координаты — нормированные, как у самой фигуры.
     */
    fun contains(x: Float, y: Float): Boolean {
        var inside = false
        val count = polygon.size / 2
        var j = count - 1
        for (i in 0 until count) {
            val xi = polygon[i * 2]
            val yi = polygon[i * 2 + 1]
            val xj = polygon[j * 2]
            val yj = polygon[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Расстояние от точки до фигуры в единицах карты; внутри фигуры — ноль. Считается по вершинам
     * ломаной: они идут через доли единицы, а нужно оно только для «промахнулся на чуть-чуть».
     */
    fun distanceTo(x: Float, y: Float): Float {
        if (contains(x, y)) return 0f
        var best = Float.MAX_VALUE
        var i = 0
        while (i < polygon.size) {
            val dx = polygon[i] - x
            val dy = polygon[i + 1] - y
            val distance = dx * dx + dy * dy
            if (distance < best) best = distance
            i += 2
        }
        return kotlin.math.sqrt(best)
    }

    /** Зеркальная копия относительно вертикальной оси фигуры: правая мышца из левой. */
    fun mirrored(): BodyShape {
        val flipped = FloatArray(anchors.size)
        var i = 0
        while (i < anchors.size) {
            flipped[i] = BodyGeometry.WIDTH - anchors[i]
            flipped[i + 1] = anchors[i + 1]
            i += 2
        }
        return BodyShape(flipped, tension)
    }

    /** Замкнутый Катмулл–Ром → кубические Безье: контрольные точки берутся из соседних вершин. */
    private fun spline(): FloatArray {
        val count = anchors.size / 2
        val result = FloatArray(count * 8)
        val k = tension / 6f
        for (i in 0 until count) {
            val prev = (i - 1 + count) % count
            val next = (i + 1) % count
            val after = (i + 2) % count
            val x1 = anchors[i * 2]
            val y1 = anchors[i * 2 + 1]
            val x2 = anchors[next * 2]
            val y2 = anchors[next * 2 + 1]
            val out = i * 8
            result[out] = x1
            result[out + 1] = y1
            result[out + 2] = x1 + (x2 - anchors[prev * 2]) * k
            result[out + 3] = y1 + (y2 - anchors[prev * 2 + 1]) * k
            result[out + 4] = x2 - (anchors[after * 2] - x1) * k
            result[out + 5] = y2 - (anchors[after * 2 + 1] - y1) * k
            result[out + 6] = x2
            result[out + 7] = y2
        }
        return result
    }

    /** Выборка точек по кривой: начало сегмента плюс шаги до его конца. */
    private fun flatten(): FloatArray {
        val segments = cubics.size / 8
        val result = FloatArray(segments * FLATTEN_STEPS * 2)
        var out = 0
        for (segment in 0 until segments) {
            val base = segment * 8
            for (step in 0 until FLATTEN_STEPS) {
                val t = step.toFloat() / FLATTEN_STEPS
                val u = 1f - t
                val a = u * u * u
                val b = 3f * u * u * t
                val c = 3f * u * t * t
                val d = t * t * t
                result[out++] = a * cubics[base] + b * cubics[base + 2] +
                    c * cubics[base + 4] + d * cubics[base + 6]
                result[out++] = a * cubics[base + 1] + b * cubics[base + 3] +
                    c * cubics[base + 5] + d * cubics[base + 7]
            }
        }
        return result
    }

    companion object {

        /** На сколько отрезков разбивается кубический сегмент при разворачивании в ломаную. */
        private const val FLATTEN_STEPS = 8

        /**
         * Фигура по опорным точкам «x y x y …», перечисленным по контуру.
         *
         * [tension] задаёт, насколько сильно кривая выгибается между точками: 1 — обычный
         * Катмулл–Ром, меньше — ближе к ломаной. Ниже единицы стоит опускаться там, где соседние
         * области рискуют наехать друг на друга из-за выпуклости кривой.
         */
        fun smooth(points: String, tension: Float = 1f): BodyShape = BodyShape(parse(points), tension)

        /**
         * Фигура, симметричная относительно оси: задаётся половина контура сверху вниз, вторая
         * половина достраивается зеркально. Первая и последняя точки должны лежать на оси —
         * они общие для половин и не дублируются.
         */
        fun smoothSymmetric(halfPoints: String, tension: Float = 1f): BodyShape {
            val half = parse(halfPoints)
            val count = half.size / 2
            val full = FloatArray(half.size + (count - 2) * 2)
            half.copyInto(full)
            var out = half.size
            for (i in count - 2 downTo 1) {
                full[out++] = BodyGeometry.WIDTH - half[i * 2]
                full[out++] = half[i * 2 + 1]
            }
            return BodyShape(full, tension)
        }

        private fun parse(points: String): FloatArray {
            val anchors = points.split(' ', '\n')
                .filter { it.isNotBlank() }
                .map { it.trim().toFloat() }
                .toFloatArray()
            require(anchors.size >= 6 && anchors.size % 2 == 0) {
                "фигуре нужно минимум три точки парами координат, получено ${anchors.size} чисел"
            }
            return anchors
        }
    }
}
