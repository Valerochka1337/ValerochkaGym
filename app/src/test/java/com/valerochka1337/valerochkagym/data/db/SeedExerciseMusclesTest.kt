package com.valerochka1337.valerochkagym.data.db

import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Глобальная шкала и полнота карт мышц стандартных упражнений. */
class SeedExerciseMusclesTest {

    @Test
    fun `every catalogue exercise has an explicit muscle map`() {
        val exerciseNames = seedExercises.map { it.name.lowercase() }.toSet()

        assertEquals(exerciseNames, seedExerciseMuscles.keys)
    }

    @Test
    fun `every muscle map has unique values in five point steps`() {
        seedExerciseMuscles.forEach { (name, loads) ->
            assertTrue("пустая карта: $name", loads.isNotEmpty())
            assertEquals("дубликаты мышц: $name", loads.size, loads.map { it.muscle }.toSet().size)
            loads.forEach { load ->
                assertTrue("$name → ${load.muscle}: ${load.contribution}", load.contribution in 5..100)
                assertEquals("$name → ${load.muscle}", 0, load.contribution % 5)
            }
        }
    }

    @Test
    fun `treadmill quadriceps load is lower than barbell squat load`() {
        val treadmill = contribution("беговая дорожка", Muscle.QUADS)
        val squat = contribution("приседания со штангой", Muscle.QUADS)

        assertEquals(20, treadmill)
        assertEquals(100, squat)
        assertTrue(treadmill < squat)
    }

    @Test
    fun `all cardio targets stay below loaded strength targets`() {
        val cardioNames = seedExercises.filter { it.type.name == "CARDIO" }.map { it.name.lowercase() }

        cardioNames.forEach { name ->
            assertTrue("$name завышено", seedExerciseMuscles.getValue(name).maxOf { it.contribution } <= 35)
        }
    }

    private fun contribution(exercise: String, muscle: Muscle): Int =
        seedExerciseMuscles.getValue(exercise).single { it.muscle == muscle }.contribution
}
