package com.valerochka1337.valerochkagym.ui

import android.app.Application
import android.graphics.Rect
import android.graphics.Region
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.ui.analysis.body.BodyView
import com.valerochka1337.valerochkagym.ui.analysis.body.ParsedBody
import com.valerochka1337.valerochkagym.ui.analysis.body.muscleSectors
import com.valerochka1337.valerochkagym.ui.analysis.body.offFigureMuscles
import com.valerochka1337.valerochkagym.ui.analysis.body.MuscleSelectorState
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
    fun `cyclic selector wraps and projects its three visible muscles`() {
        assertEquals(Muscle.NECK, MuscleSelectorState.next(Muscle.UPPER_CHEST, -1))
        assertEquals(Muscle.UPPER_CHEST, MuscleSelectorState.next(Muscle.NECK, 1))
        assertEquals(
            listOf(Muscle.NECK, Muscle.UPPER_CHEST, Muscle.LOWER_CHEST),
            MuscleSelectorState.visible(Muscle.UPPER_CHEST),
        )
    }

    @Test
    fun `every persisted muscle belongs to a visible sector`() {
        val onFigure = BodyView.entries
            .flatMap { view -> muscleSectors(view).flatMap { it.members } }
            .toSet()

        assertEquals(Muscle.entries.toSet(), onFigure)
        assertTrue(offFigureMuscles.isEmpty())
        assertTrue(Muscle.LOWER_CHEST in muscleSectors(BodyView.FRONT).first { it.slug == "chest" }.members)
        assertTrue(Muscle.UPPER_BACK in muscleSectors(BodyView.BACK).first { it.slug == "upper-back" }.members)
    }

    @Test
    fun `an interior point of a muscle picks that same muscle`() {
        val collisions = mutableListOf<String>()
        BodyView.entries.forEach { view ->
            val parsed = ParsedBody.of(view)
            parsed.sectors.forEach { shape ->
                val point = interiorPoint(shape.region)
                assertNotNull("$view ${shape.sector.slug}: пустой регион", point)
                val (x, y) = point!!
                val hit = parsed.muscleAt(x, y)
                if (hit != shape.sector.defaultMuscle) collisions += "$view ${shape.sector.slug} @($x,$y) → $hit"
                shape.sector.members.forEach { member ->
                    assertEquals(member, parsed.muscleAt(x, y, member))
                }
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
