package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class EnumDisplayTest {

  @Test
  fun `muscleGroupFrom is the inverse of displayName for every value`() {
    MuscleGroup.entries.forEach { group ->
      assertEquals(group, muscleGroupFrom(group.displayName()))
    }
  }

  @Test
  fun `exerciseTypeFrom is the inverse of displayName for every value`() {
    ExerciseType.entries.forEach { type ->
      assertEquals(type, exerciseTypeFrom(type.displayName()))
    }
  }

  @Test
  fun `muscleGroupFrom trims and ignores case`() {
    assertEquals(MuscleGroup.CHEST, muscleGroupFrom("  грудь "))
  }

  @Test
  fun `unknown muscle group falls back to FULL_BODY`() {
    assertEquals(MuscleGroup.FULL_BODY, muscleGroupFrom("абракадабра"))
  }

  @Test
  fun `unknown exercise type falls back to STRENGTH`() {
    assertEquals(ExerciseType.STRENGTH, exerciseTypeFrom("что-то"))
  }
}
