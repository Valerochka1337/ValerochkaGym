package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import javax.inject.Inject

/** Новый рекорд по весу для упражнения в рамках завершаемой тренировки. */
data class PrResult(
    val exerciseId: Long,
    val exerciseName: String,
    val weightKg: Double,
)

/** Статистика тренировки: суммарный тоннаж и новые рекорды. */
class WorkoutStatsUseCase
@Inject
constructor(
    private val workoutDao: WorkoutDao,
) {

  /** Σ weightKg × reps по выполненным подходам силовых упражнений. */
  fun volume(workout: WorkoutFull): Double =
      workout.exercises
          .filter { it.exercise.type == ExerciseType.STRENGTH }
          .flatMap { it.sets }
          .filter { it.isCompleted && it.weightKg != null && it.reps != null }
          .sumOf { it.weightKg!! * it.reps!! }

  /**
   * Упражнения, где максимальный выполненный вес в этой тренировке превосходит рекорд по всем
   * прошлым завершённым тренировкам (или прошлого нет, а вес > 0).
   */
  suspend fun newPrs(workout: WorkoutFull): List<PrResult> {
    val results = mutableListOf<PrResult>()
    for (exercise in workout.exercises) {
      val maxNow =
          exercise.sets.filter { it.isCompleted }.mapNotNull { it.weightKg }.maxOrNull() ?: continue
      val previous = workoutDao.maxCompletedWeight(exercise.exercise.id, workout.workout.id)
      val isPr = if (previous == null) maxNow > 0.0 else maxNow > previous
      if (isPr) {
        results += PrResult(exercise.exercise.id, exercise.exercise.name, maxNow)
      }
    }
    return results
  }
}
