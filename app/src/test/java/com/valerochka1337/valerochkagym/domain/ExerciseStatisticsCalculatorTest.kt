package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.relation.AnalyticsSetRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseStatisticsCalculatorTest {

  private val calculator = ExerciseStatisticsCalculator()

  @Test
  fun `strength statistics show the last workout trend and records`() {
    val rows =
        listOf(
            row(workoutId = "old", at = 1_000, weight = 80.0, reps = 8),
            row(workoutId = "old", at = 1_100, weight = 85.0, reps = 5),
            row(workoutId = "last", at = 2_000, weight = 90.0, reps = 5),
            row(workoutId = "last", at = 2_100, weight = 80.0, reps = 10),
        )

    val result = calculator.calculate(ExerciseType.STRENGTH, rows)

    assertEquals(2, result.points.size)
    assertEquals("90×5, 80×10", result.lastSummary)
    assertEquals(2_000L, result.lastPerformedAt)
    assertEquals("≈ 106.7 кг", result.records.first { it.title == "Оценочный максимум" }.value)
    assertEquals("90 кг", result.records.first { it.title == "Максимальный вес" }.value)
    assertEquals("1250 кг", result.records.first { it.title == "Лучший объём тренировки" }.value)
  }

  @Test
  fun `timed statistics use total workout duration and duration records`() {
    val rows =
        listOf(
            row(workoutId = "old", at = 1_000, type = ExerciseType.TIMED, duration = 60),
            row(workoutId = "last", at = 2_000, type = ExerciseType.TIMED, duration = 90),
            row(workoutId = "last", at = 2_100, type = ExerciseType.TIMED, duration = 30),
        )

    val result = calculator.calculate(ExerciseType.TIMED, rows)

    assertEquals(listOf(1.0, 2.0), result.points.map { it.value })
    assertEquals("1 мин 30 сек, 30 сек", result.lastSummary)
    assertEquals("1 мин 30 сек", result.records.first { it.title == "Самый долгий подход" }.value)
    assertEquals("2 мин", result.records.first { it.title == "Максимум за тренировку" }.value)
  }

  @Test
  fun `cardio statistics calculate distance and cardio records`() {
    val rows =
        listOf(
            row(
                workoutId = "last",
                at = 2_000,
                type = ExerciseType.CARDIO,
                duration = 1_800,
                speed = 10.0,
                incline = 5.0,
            ),
        )

    val result = calculator.calculate(ExerciseType.CARDIO, rows)

    assertEquals(5.0, result.points.single().value, 0.0001)
    assertEquals("10 км/ч · 5% · 30 мин", result.lastSummary)
    assertEquals("5 км", result.records.first { it.title == "Самая длинная дистанция" }.value)
    assertEquals("10 км/ч", result.records.first { it.title == "Максимальная скорость" }.value)
    assertEquals("5%", result.records.first { it.title == "Максимальный наклон" }.value)
  }

  @Test
  fun `empty history produces an empty statistics state`() {
    val result = calculator.calculate(ExerciseType.STRENGTH, emptyList())

    assertTrue(result.points.isEmpty())
    assertTrue(result.records.isEmpty())
    assertEquals(null, result.lastPerformedAt)
  }

  private fun row(
      workoutId: String,
      at: Long,
      type: ExerciseType = ExerciseType.STRENGTH,
      weight: Double? = null,
      reps: Int? = null,
      duration: Int? = null,
      speed: Double? = null,
      incline: Double? = null,
  ) =
      AnalyticsSetRow(
          workoutId = workoutId,
          exerciseId = 7,
          exerciseName = "Упражнение",
          exerciseType = type,
          weightKg = weight,
          reps = reps,
          durationSec = duration,
          speedKmh = speed,
          inclinePct = incline,
          completedAt = at,
      )
}
