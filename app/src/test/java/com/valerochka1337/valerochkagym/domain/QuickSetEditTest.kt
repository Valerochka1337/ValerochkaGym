package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [parseQuickSetEdit] — разбор строки из инлайн-поля уведомления. Формат свободный
 * по замыслу: печатают одной рукой между подходами, так что тесты держат весь набор разделителей,
 * включая кириллическую «х» с русской раскладки.
 */
class QuickSetEditTest {

  // region STRENGTH

  @Test
  fun `strength parses weight and reps across every separator`() {
    val inputs = listOf("60x8", "60х8", "60*8", "60 8", "60×8", "60 кг x 8", "60X8")

    for (input in inputs) {
      val edit = parseQuickSetEdit(input, ExerciseType.STRENGTH)
      assertEquals(input, 60.0, edit?.weightKg!!, EPS)
      assertEquals(input, 8, edit.reps)
    }
  }

  @Test
  fun `strength accepts both decimal separators`() {
    assertEquals(62.5, parseQuickSetEdit("62,5x8", ExerciseType.STRENGTH)?.weightKg!!, EPS)
    assertEquals(62.5, parseQuickSetEdit("62.5x8", ExerciseType.STRENGTH)?.weightKg!!, EPS)
  }

  @Test
  fun `strength reads a lone whole number as reps`() {
    val edit = parseQuickSetEdit("8", ExerciseType.STRENGTH)

    assertEquals(8, edit?.reps)
    assertNull(edit?.weightKg)
  }

  @Test
  fun `strength reads a lone fractional number as weight`() {
    val edit = parseQuickSetEdit("62.5", ExerciseType.STRENGTH)

    assertEquals(62.5, edit?.weightKg!!, EPS)
    assertNull(edit.reps)
  }

  @Test
  fun `strength reads a dangling separator as weight only`() {
    val edit = parseQuickSetEdit("60x", ExerciseType.STRENGTH)

    assertEquals(60.0, edit?.weightKg!!, EPS)
    assertNull(edit.reps)
  }

  // endregion

  // region TIMED and CARDIO

  @Test
  fun `timed reads plain seconds`() {
    assertEquals(45, parseQuickSetEdit("45", ExerciseType.TIMED)?.durationSec)
  }

  @Test
  fun `timed reads a colon as minutes and seconds`() {
    assertEquals(90, parseQuickSetEdit("1:30", ExerciseType.TIMED)?.durationSec)
  }

  @Test
  fun `cardio reads speed and incline`() {
    val edit = parseQuickSetEdit("10x5", ExerciseType.CARDIO)

    assertEquals(10.0, edit?.speedKmh!!, EPS)
    assertEquals(5.0, edit.inclinePct!!, EPS)
  }

  @Test
  fun `cardio reads speed alone`() {
    val edit = parseQuickSetEdit("12,5", ExerciseType.CARDIO)

    assertEquals(12.5, edit?.speedKmh!!, EPS)
    assertNull(edit.inclinePct)
  }

  // endregion

  // region rejection and application

  @Test
  fun `input without a single number is rejected`() {
    for (input in listOf("", "   ", "ага", "x", "--")) {
      assertNull(input, parseQuickSetEdit(input, ExerciseType.STRENGTH))
    }
  }

  @Test
  fun `applyTo overwrites only the fields that were parsed`() {
    val set =
        WorkoutSetEntity(
            id = 1,
            workoutExerciseId = 1,
            setIndex = 0,
            weightKg = 60.0,
            reps = 10,
            durationSec = 30,
        )

    val updated = parseQuickSetEdit("8", ExerciseType.STRENGTH)!!.applyTo(set)

    assertEquals(8, updated.reps)
    // Вес и остальные поля не трогаем: ввели только повторы.
    assertEquals(60.0, updated.weightKg!!, EPS)
    assertEquals(30, updated.durationSec)
  }

  // endregion
}

private const val EPS = 1e-9
