package com.valerochka1337.valerochkagym.data.db

import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Глобальная шкала и полнота карт мышц стандартных упражнений. */
class SeedExerciseMusclesTest {

  @Test
  fun `every catalogue exercise has an explicit muscle map`() {
    val expected =
        CanonicalExerciseRegistry.entries.associate { entry ->
          entry.exercise.name.lowercase() to entry.loads
        }

    assertEquals(expected, seedExerciseMuscles)
    assertTrue(
        legacySeedExercises.map { it.name.lowercase() }.all(seedExerciseMuscles::containsKey),
    )
  }

  @Test
  fun `every muscle map has unique canonical roles and required primaries`() {
    seedExerciseMuscles.forEach { (name, loads) ->
      assertTrue("пустая карта: $name", loads.isNotEmpty())
      assertEquals("дубликаты мышц: $name", loads.size, loads.map { it.muscle }.toSet().size)
      loads.forEach { load ->
        assertTrue(
            "$name → ${load.muscle}: ${load.contribution}",
            load.contribution in setOf(100, 50, 0),
        )
      }
      val exercise = seedExercises.single { it.name.lowercase() == name }
      if (exercise.type.name != "CARDIO")
          assertTrue("нет primary: $name", loads.any { it.contribution == 100 })
    }
  }

  @Test
  fun `treadmill keeps descriptive secondary roles`() {
    val treadmill = contribution("беговая дорожка", Muscle.QUADS)
    val squat = contribution("приседания со штангой", Muscle.QUADS)

    assertEquals(50, treadmill)
    assertEquals(100, squat)
    assertTrue(treadmill < squat)
  }

  @Test
  fun `cardio maps remain descriptive rather than percentage-scaled`() {
    val cardioNames = seedExercises.filter { it.type.name == "CARDIO" }.map { it.name.lowercase() }

    cardioNames.forEach { name ->
      assertTrue(
          "$name must use secondary role descriptions",
          seedExerciseMuscles.getValue(name).all { it.contribution == 50 },
      )
    }
  }

  @Suppress("SameParameterValue")
  private fun contribution(exercise: String, muscle: Muscle): Int =
      seedExerciseMuscles.getValue(exercise).single { it.muscle == muscle }.contribution
}
