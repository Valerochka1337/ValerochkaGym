package com.valerochka1337.valerochkagym.ui

import android.app.Application
import android.graphics.Rect
import android.graphics.Region
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.ui.analysis.body.BodyView
import com.valerochka1337.valerochkagym.ui.analysis.body.ParsedBody
import com.valerochka1337.valerochkagym.ui.analysis.body.musclePaths
import com.valerochka1337.valerochkagym.ui.analysis.body.offFigureMuscles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Проверки карты тела после перехода на контуры из react-native-body-highlighter.
 *
 * Геометрия теперь приходит SVG-строками и разбирается [ParsedBody] в реальные [Region], поэтому
 * тест работает с настоящим разбором (NATIVE-графика Robolectric), а не с копией его логики.
 * Ключевые свойства: попадание внутрь мышцы возвращает именно её (области не наехали друг на
 * друга), тап мимо фигуры — `null`, а раскладка мышц по видам покрывает всю модель, кроме тех,
 * что осознанно оставлены спискам.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class BodyMapHitTest {

    @Test
    fun `every muscle except the off-figure ones is on some view`() {
        val onFigure = BodyView.entries
            .flatMap { musclePaths(it).keys }
            .toSet()

        assertEquals(Muscle.entries.toSet() - offFigureMuscles.toSet(), onFigure)
        assertEquals(listOf(Muscle.SIDE_DELTS, Muscle.UPPER_BACK), offFigureMuscles)
    }

    @Test
    fun `an interior point of a muscle picks that same muscle`() {
        val collisions = mutableListOf<String>()
        BodyView.entries.forEach { view ->
            val parsed = ParsedBody.of(view)
            parsed.muscles.forEach { shape ->
                val point = interiorPoint(shape.region)
                assertNotNull("$view ${shape.muscle}: пустой регион", point)
                val (x, y) = point!!
                val hit = parsed.muscleAt(x, y)
                if (hit != shape.muscle) collisions += "$view ${shape.muscle} @($x,$y) → $hit"
            }
        }

        assertTrue("области наехали друг на друга: ${collisions.joinToString("; ")}", collisions.isEmpty())
    }

    @Test
    fun `a tap outside the figure clears the selection`() {
        BodyView.entries.forEach { view ->
            val parsed = ParsedBody.of(view)
            assertNull("$view угол", parsed.muscleAt(2, 2))
            assertNull("$view низ-слева", parsed.muscleAt(2, 1440))
        }
    }

    /** Первая точка внутри региона: центр габаритов у парных мышц попал бы в зазор между половинами. */
    private fun interiorPoint(region: Region): Pair<Int, Int>? {
        val bounds = Rect()
        if (!region.getBounds(bounds) || bounds.isEmpty) return null
        var y = bounds.top
        while (y < bounds.bottom) {
            var x = bounds.left
            while (x < bounds.right) {
                if (region.contains(x, y)) return x to y
                x += 2
            }
            y += 2
        }
        return null
    }
}
