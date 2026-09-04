package com.valerochka1337.valerochkagym.data.db

import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Фоллбэк-карты вовлечения по крупной группе: полнота и корректность долей. */
class MuscleDefaultsTest {

  @Test
  fun `every muscle group has a non-empty fallback map`() {
    MuscleGroup.entries.forEach { group ->
      assertTrue("пустая карта у $group", group.defaultMuscleLoads().isNotEmpty())
    }
  }

  @Test
  fun `cardio fallback remains descriptive role data`() {
    assertEquals(
        setOf(50),
        MuscleGroup.CARDIO.defaultMuscleLoads().map { it.contribution }.toSet(),
    )
  }

  @Test
  fun `contributions use canonical role values and strength fallbacks have a primary`() {
    MuscleGroup.entries.forEach { group ->
      val loads = group.defaultMuscleLoads()
      assertTrue(
          "$group has a non-role contribution",
          loads.all { it.contribution in setOf(100, 50, 0) },
      )
      if (group != MuscleGroup.CARDIO) {
        assertTrue("$group has no primary role", loads.any { it.contribution == 100 })
      }
    }
  }

  @Test
  fun `no muscle repeats within one fallback map`() {
    MuscleGroup.entries.forEach { group ->
      val loads = group.defaultMuscleLoads()
      assertEquals(
          "дубликаты мышц у $group",
          loads.size,
          loads.map { it.muscle }.toSet().size,
      )
    }
  }
}
