package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.entity.withNextUpdatedAt
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.worker.NoOpRoutineUploadScheduler
import com.valerochka1337.valerochkagym.worker.RoutineUploadScheduler
import javax.inject.Inject

/**
 * Сравнение фактически выполненной тренировки с её программой и перезапись программы по факту
 * (для предложения «обновить программу?» после завершения тренировки по программе).
 */
class RoutineUpdateUseCase @Inject constructor(
    private val routineDao: RoutineDao,
    private val routineUploadScheduler: RoutineUploadScheduler = NoOpRoutineUploadScheduler,
) {

    /**
     * true, если состав/подходы фактически выполненной тренировки расходятся с программой.
     * false, если тренировка без программы или программа удалена.
     */
    suspend fun hasDiverged(workout: WorkoutFull): Boolean {
        val routineId = workout.workout.routineId ?: return false
        val routine = routineDao.getRoutineWithExercises(routineId) ?: return false

        val actual = performed(workout).map { (exerciseId, sets) -> exerciseId to sets }
        val planned = routine.exercises
            .sortedBy { it.routineExercise.position }
            .map { it.exercise.id to it.routineExercise.plannedSets }
        return actual != planned
    }

    /**
     * Перезаписывает состав и plannedSets программы фактически выполненными подходами:
     * только isCompleted, упражнения без выполненных подходов не попадают, restSeconds
     * сохраняются для оставшихся упражнений, новые — null.
     */
    suspend fun applyToRoutine(workout: WorkoutFull) {
        val routineId = workout.workout.routineId ?: return
        val routine = routineDao.getRoutineWithExercises(routineId) ?: return
        val restByExercise = routine.exercises.associate {
            it.exercise.id to it.routineExercise.restSeconds
        }
        val entities = performed(workout).mapIndexed { position, (exerciseId, sets) ->
            RoutineExerciseEntity(
                routineId = routineId,
                exerciseId = exerciseId,
                position = position,
                restSeconds = restByExercise[exerciseId],
                plannedSets = sets,
            )
        }
        val updatedRoutine = routine.routine.withNextUpdatedAt()
        routineDao.upsertRoutine(updatedRoutine)
        routineDao.replaceRoutineExercises(routineId, entities)
        routineUploadScheduler.schedule(updatedRoutine.syncId)
    }

    /**
     * Фактически выполненный состав: упражнения по позиции, у каждого — выполненные подходы
     * (по setIndex) как plannedSets. Упражнения без выполненных подходов исключены.
     */
    private fun performed(workout: WorkoutFull): List<Pair<Long, List<PlannedSet>>> =
        workout.exercises
            .sortedBy { it.workoutExercise.position }
            .mapNotNull { exercise: WorkoutExerciseWithSets ->
                val completed = exercise.sets
                    .filter { it.isCompleted }
                    .sortedBy { it.setIndex }
                if (completed.isEmpty()) null else exercise.exercise.id to completed.map { it.toPlannedSet() }
            }
}

private fun WorkoutSetEntity.toPlannedSet(): PlannedSet =
    PlannedSet(
        weightKg = weightKg,
        reps = reps,
        durationSec = durationSec,
        speedKmh = speedKmh,
        inclinePct = inclinePct,
    )
