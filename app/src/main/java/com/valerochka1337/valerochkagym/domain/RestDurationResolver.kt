package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Определяет длительность отдыха для завершённого подхода. Персональный отдых упражнения хранится
 * не в тренировке, а в программе
 * ([com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity.restSeconds], nullable) .
 * Поэтому: если у тренировки есть [WorkoutFull.workout].routineId — берём restSeconds упражнения из
 * программы; иначе (или если он не задан) —
 * [com.valerochka1337.valerochkagym.data.settings.GymSettings.defaultRestSeconds] из настроек.
 */
@Singleton
class RestDurationResolver
@Inject
constructor(
    private val routineDao: RoutineDao,
    private val settingsRepository: SettingsRepository,
) {

  suspend operator fun invoke(workout: WorkoutFull, exerciseId: Long): Int {
    val routineId = workout.workout.routineId
    val fromRoutine =
        if (routineId != null) {
          routineDao
              .getRoutineWithExercises(routineId)
              ?.exercises
              ?.firstOrNull { it.routineExercise.exerciseId == exerciseId }
              ?.routineExercise
              ?.restSeconds
        } else {
          null
        }
    return fromRoutine ?: settingsRepository.settings.first().defaultRestSeconds
  }
}
