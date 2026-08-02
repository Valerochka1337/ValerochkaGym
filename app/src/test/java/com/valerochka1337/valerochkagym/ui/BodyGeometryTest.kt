package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.ui.analysis.body.BodyGeometry
import com.valerochka1337.valerochkagym.ui.analysis.body.BodyShape
import com.valerochka1337.valerochkagym.ui.analysis.body.BodyView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки геометрии карты тела.
 *
 * Координаты мышц выставлены руками, и глазами по картинке ловятся не все ошибки: наехавшие
 * друг на друга области отдают тапу не ту мышцу, а вылезшая за силуэт область рисуется
 * «в воздухе» — на тёмном фоне это почти незаметно. Оба свойства проверяются перебором точек
 * по сетке: фигуры гладкие, поэтому сетки с шагом в пол-единицы хватает.
 */
class BodyGeometryTest {

    @Test
    fun `every muscle is on the map`() {
        val mapped = BodyView.entries
            .flatMap { BodyGeometry.regions(it) }
            .mapTo(mutableSetOf()) { it.muscle }

        assertEquals(Muscle.entries.toSet(), mapped)
    }

    @Test
    fun `muscle regions of one view never overlap`() {
        val overlaps = mutableListOf<String>()
        BodyView.entries.forEach { view ->
            val regions = BodyGeometry.regions(view)
            forEachGridPoint { x, y ->
                val hits = regions.filter { region -> region.shapes.any { it.contains(x, y) } }
                if (hits.size > 1) {
                    overlaps += "$view ($x, $y): ${hits.joinToString { it.muscle.name }}"
                }
            }
        }

        assertNoViolations("области наехали друг на друга", overlaps)
    }

    @Test
    fun `muscle regions stay inside the silhouette`() {
        val silhouette = BodyGeometry.silhouette
        val outside = mutableListOf<String>()
        BodyView.entries.forEach { view ->
            BodyGeometry.regions(view).forEach { region ->
                region.shapes.forEach { shape ->
                    val polygon = shape.polygon
                    var i = 0
                    while (i < polygon.size) {
                        val x = polygon[i]
                        val y = polygon[i + 1]
                        if (silhouette.none { it.contains(x, y) }) {
                            outside += "$view ${region.muscle} ($x, $y)"
                        }
                        i += 2
                    }
                }
            }
        }

        assertNoViolations("области вылезают за силуэт", outside)
    }

    @Test
    fun `paired muscles are symmetric`() {
        val asymmetries = mutableListOf<String>()
        BodyView.entries.forEach { view ->
            BodyGeometry.regions(view).forEach { region ->
                forEachGridPoint { x, y ->
                    val left = region.shapes.any { it.contains(x, y) }
                    val right = region.shapes.any { it.contains(BodyGeometry.WIDTH - x, y) }
                    if (left != right) asymmetries += "$view ${region.muscle} ($x, $y)"
                }
            }
        }

        assertNoViolations("области несимметричны", asymmetries)
    }

    @Test
    fun `a tap picks the muscle under it`() {
        assertEquals(Muscle.CHEST, BodyGeometry.muscleAt(BodyView.FRONT, 57f, 57f))
        assertEquals(Muscle.CHEST, BodyGeometry.muscleAt(BodyView.FRONT, 43f, 57f))
        assertEquals(Muscle.LATS, BodyGeometry.muscleAt(BodyView.BACK, 59f, 82f))
        assertEquals(Muscle.GLUTES, BodyGeometry.muscleAt(BodyView.BACK, 57f, 112f))
    }

    @Test
    fun `a tap outside every muscle clears the selection`() {
        assertNull("голова", BodyGeometry.muscleAt(BodyView.FRONT, 50f, 15f))
        assertNull("мимо фигуры", BodyGeometry.muscleAt(BodyView.FRONT, 10f, 110f))
        assertNull("колено", BodyGeometry.muscleAt(BodyView.FRONT, 43f, 161f))
    }

    @Test
    fun `a near miss still picks the muscle`() {
        // Приводящие на карте уже пальца: без допуска выбрать их было бы нельзя.
        val muscle = BodyGeometry.muscleAt(BodyView.FRONT, 50.2f, 130f)

        assertEquals(Muscle.ADDUCTORS, muscle)
    }

    @Test
    fun `a mirrored shape swaps sides and keeps heights`() {
        val shape = BodyShape.smooth("60 10 70 10 70 20 60 20")
        val mirrored = shape.mirrored()

        assertTrue(shape.contains(65f, 15f))
        assertFalse(mirrored.contains(65f, 15f))
        assertTrue(mirrored.contains(35f, 15f))
    }

    /** Все нарушения сразу: по одному за прогон правки координат заняли бы десяток кругов. */
    private fun assertNoViolations(what: String, violations: List<String>) {
        assertTrue(
            "$what (${violations.size}): ${violations.take(12).joinToString("; ")}",
            violations.isEmpty(),
        )
    }

    private fun forEachGridPoint(block: (x: Float, y: Float) -> Unit) {
        var x = 0.25f
        while (x < BodyGeometry.WIDTH) {
            var y = 0.25f
            while (y < BodyGeometry.HEIGHT) {
                block(x, y)
                y += 0.5f
            }
            x += 0.5f
        }
    }
}
