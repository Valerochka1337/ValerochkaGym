package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Определяет длительность отдыха для завершённого подхода. Персональный отдых упражнения хранится
 * не в тренировке, а в программе ([RoutineExerciseEntity.restSeconds], nullable). Поэтому: если у
 * тренировки есть [WorkoutFull.workout].routineId — берём restSeconds упражнения из программы;
 * иначе (или если он не задан) — [GymSettings.defaultRestSeconds] из настроек.
 */
@Singleton
class RestDurationResolver @Inject constructor(
    private val routineDao: RoutineDao,
    private val settingsRepository: SettingsRepository,
) {

    suspend operator fun invoke(workout: WorkoutFull, exerciseId: Long): Int {
        val routineId = workout.workout.routineId
        val fromRoutine = if (routineId != null) {
            routineDao.getRoutineWithExercises(routineId)
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
