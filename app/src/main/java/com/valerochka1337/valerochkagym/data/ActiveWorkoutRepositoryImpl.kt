package com.valerochka1337.valerochkagym.data

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

/** Имя тренировки без программы. */
private const val EMPTY_WORKOUT_NAME = "Тренировка"

class ActiveWorkoutRepositoryImpl @Inject constructor(
    private val database: GymDatabase,
    private val workoutDao: WorkoutDao,
    private val routineDao: RoutineDao,
) : ActiveWorkoutRepository {

    override suspend fun startFromRoutine(routineId: Long): String = database.withTransaction {
        workoutDao.getActiveWorkoutId()?.let { return@withTransaction it }

        val routine = routineDao.getRoutineWithExercises(routineId)
            ?: return@withTransaction startEmpty()
        val workoutId = newId()
        workoutDao.insertWorkout(
            WorkoutEntity(
                id = workoutId,
                routineId = routineId,
                name = routine.routine.name,
                startedAt = now(),
                finishedAt = null,
            ),
        )
        routine.exercises
            .sortedBy { it.routineExercise.position }
            .forEachIndexed { position, item ->
                val workoutExerciseId = workoutDao.insertWorkoutExercise(
                    WorkoutExerciseEntity(
                        workoutId = workoutId,
                        exerciseId = item.exercise.id,
                        position = position,
                    ),
                )
                val previous = workoutDao.lastCompletedSetsForExercise(item.exercise.id)
                val sets = item.routineExercise.plannedSets.mapIndexed { index, planned ->
                    val source = previous.getOrNull(index)
                    if (source != null) {
                        source.copy(id = 0, workoutExerciseId = workoutExerciseId, setIndex = index, isCompleted = false)
                    } else {
                        planned.toSet(workoutExerciseId, index)
                    }
                }
                if (sets.isNotEmpty()) workoutDao.insertSets(sets)
            }
        workoutId
    }

    override suspend fun startEmpty(): String = database.withTransaction {
        workoutDao.getActiveWorkoutId()?.let { return@withTransaction it }

        val workoutId = newId()
        workoutDao.insertWorkout(
            WorkoutEntity(
                id = workoutId,
                routineId = null,
                name = EMPTY_WORKOUT_NAME,
                startedAt = now(),
                finishedAt = null,
            ),
        )
        workoutId
    }

    override fun observeActive(): Flow<WorkoutFull?> =
        workoutDao.observeActiveWorkout().map { full -> full?.let(::sortedWorkoutFull) }

    override suspend fun updateSet(set: WorkoutSetEntity) = workoutDao.updateSet(set)

    override suspend fun toggleSetCompleted(setId: Long, completed: Boolean) =
        workoutDao.setSetCompleted(setId, completed)

    override suspend fun addSet(workoutExerciseId: Long) {
        val existing = workoutDao.getSetsForWorkoutExercise(workoutExerciseId)
        val nextIndex = (existing.maxOfOrNull { it.setIndex } ?: -1) + 1
        val last = existing.lastOrNull()
        workoutDao.insertSet(
            WorkoutSetEntity(
                workoutExerciseId = workoutExerciseId,
                setIndex = nextIndex,
                weightKg = last?.weightKg,
                reps = last?.reps,
                durationSec = last?.durationSec,
                speedKmh = last?.speedKmh,
                inclinePct = last?.inclinePct,
                isCompleted = false,
            ),
        )
    }

    override suspend fun deleteSet(setId: Long) = workoutDao.deleteSet(setId)

    override suspend fun addExercise(workoutId: String, exerciseId: Long): Long {
        val existing = workoutDao.getWorkoutExercises(workoutId)
        val position = (existing.maxOfOrNull { it.position } ?: -1) + 1
        val workoutExerciseId = workoutDao.insertWorkoutExercise(
            WorkoutExerciseEntity(workoutId = workoutId, exerciseId = exerciseId, position = position),
        )
        val previous = workoutDao.lastCompletedSetsForExercise(exerciseId).firstOrNull()
        workoutDao.insertSet(
            WorkoutSetEntity(
                workoutExerciseId = workoutExerciseId,
                setIndex = 0,
                weightKg = previous?.weightKg,
                reps = previous?.reps,
                durationSec = previous?.durationSec,
                speedKmh = previous?.speedKmh,
                inclinePct = previous?.inclinePct,
                isCompleted = false,
            ),
        )
        return workoutExerciseId
    }

    override suspend fun deleteExercise(workoutExerciseId: Long) =
        workoutDao.deleteWorkoutExercise(workoutExerciseId)

    override suspend fun finish(workoutId: String) = database.withTransaction {
        val full = workoutDao.getWorkoutFull(workoutId) ?: return@withTransaction
        // Идемпотентность: повторный тап «Завершить» не должен перезаписывать метку времени.
        if (full.workout.finishedAt != null) return@withTransaction
        for (exercise in full.exercises) {
            val remaining = exercise.sets.filter { set ->
                val empty = !set.isCompleted && set.isBlank()
                if (empty) workoutDao.deleteSet(set.id)
                !empty
            }
            if (remaining.isEmpty()) workoutDao.deleteWorkoutExercise(exercise.workoutExercise.id)
        }
        workoutDao.setFinishedAt(workoutId, now())
    }

    override suspend fun discard(workoutId: String) = workoutDao.deleteWorkout(workoutId)

    private fun now(): Long = System.currentTimeMillis()

    private fun newId(): String = UUID.randomUUID().toString()
}

private fun WorkoutSetEntity.isBlank(): Boolean =
    weightKg == null && reps == null && durationSec == null && speedKmh == null && inclinePct == null

private fun PlannedSet.toSet(workoutExerciseId: Long, setIndex: Int): WorkoutSetEntity =
    WorkoutSetEntity(
        workoutExerciseId = workoutExerciseId,
        setIndex = setIndex,
        weightKg = weightKg,
        reps = reps,
        durationSec = durationSec,
        speedKmh = speedKmh,
        inclinePct = inclinePct,
        isCompleted = false,
    )

/** Доменная сортировка дерева тренировки: упражнения по position, подходы по setIndex. */
internal fun sortedWorkoutFull(full: WorkoutFull): WorkoutFull =
    full.copy(
        exercises = full.exercises
            .sortedBy { it.workoutExercise.position }
            .map { exercise: WorkoutExerciseWithSets ->
                exercise.copy(sets = exercise.sets.sortedBy { it.setIndex })
            },
    )
