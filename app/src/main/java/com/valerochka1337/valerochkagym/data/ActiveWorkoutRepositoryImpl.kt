package com.valerochka1337.valerochkagym.data

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.GymDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseVariantDao
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutUnavailableException
import com.valerochka1337.valerochkagym.domain.RoutineGymConflictException
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
    private val gymDao: GymDao = database.gymDao(),
    private val variantDao: ExerciseVariantDao = database.exerciseVariantDao(),
) : ActiveWorkoutRepository {

    override suspend fun startFromRoutine(routineId: Long): String = database.withTransaction {
        workoutDao.getActiveWorkoutId()?.let { return@withTransaction it }

        val routine = routineDao.getRoutineWithExercises(routineId)
            ?: return@withTransaction startEmpty()
        val gymIds = routine.gyms.map { it.id }
        if (gymIds.isNotEmpty()) {
            val availableIds = gymDao.getAvailableExercises(gymIds, gymIds.size)
                .mapTo(hashSetOf()) { it.id }
            val unavailable = routine.exercises
                .map { it.exercise }
                .filter { it.id !in availableIds }
                .distinctBy { it.id }
            if (unavailable.isNotEmpty()) {
                throw RoutineGymConflictException(unavailable.map { it.name })
            }
        }
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
        gymDao.replaceWorkoutGyms(workoutId, gymIds)
        routine.exercises
            .sortedBy { it.routineExercise.position }
            .forEachIndexed { position, item ->
                val workoutExerciseId = workoutDao.insertWorkoutExercise(
                    WorkoutExerciseEntity(
                        workoutId = workoutId,
                        exerciseId = item.exercise.id,
                        variantSyncId = item.routineExercise.variantSyncId,
                        variantNameSnapshot = item.routineExercise.variantSyncId?.let { id ->
                            requireNotNull(variantDao.getOwned(item.exercise.id, id)) { "Variant is not owned by exercise" }.name
                        },
                        position = position,
                    ),
                )
                val previous = workoutDao.lastCompletedSetsForKey(item.exercise.id, item.routineExercise.variantSyncId)
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

    override suspend fun getSet(setId: Long): WorkoutSetEntity? = workoutDao.getSet(setId)

    override suspend fun updateSet(set: WorkoutSetEntity) = workoutDao.updateSet(set)

    override suspend fun toggleSetCompleted(setId: Long, completed: Boolean) =
        workoutDao.setSetCompleted(setId, completed, completedAt = if (completed) now() else null)

    override suspend fun addSet(workoutExerciseId: Long) = database.withTransaction {
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
        Unit
    }

    override suspend fun deleteSet(setId: Long) = workoutDao.deleteSet(setId)

    override suspend fun addExercise(workoutId: String, exerciseId: Long): Long = addExerciseWithVariant(workoutId, exerciseId, null)

    override suspend fun addExerciseWithVariant(workoutId: String, exerciseId: Long, variantSyncId: String?): Long = database.withTransaction {
        val workout = workoutDao.getWorkoutFull(workoutId)?.workout
            ?.takeIf { it.finishedAt == null }
            ?: throw ActiveWorkoutUnavailableException()
        val gyms = gymDao.getGymsForWorkout(workoutId)
        if (gyms.isNotEmpty()) {
            val available = gymDao.getAvailableExercises(gyms.map { it.id }, gyms.size)
                .any { it.id == exerciseId }
            if (!available) {
                val name = database.exerciseDao().getById(exerciseId)?.name ?: "Упражнение"
                throw RoutineGymConflictException(listOf(name))
            }
        }
        val existing = workoutDao.getWorkoutExercises(workoutId)
        val position = (existing.maxOfOrNull { it.position } ?: -1) + 1
        val snapshot = variantSyncId?.let { id ->
            requireNotNull(variantDao.getOwned(exerciseId, id)) { "Variant is not owned by exercise" }.name
        }
        val workoutExerciseId = workoutDao.insertWorkoutExercise(
            WorkoutExerciseEntity(
                workoutId = workoutId,
                exerciseId = exerciseId,
                variantSyncId = variantSyncId,
                variantNameSnapshot = snapshot,
                position = position,
            ),
        )
        val previous = workoutDao.lastCompletedSetsForKey(exerciseId, variantSyncId).firstOrNull()
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
        workoutExerciseId
    }

    override suspend fun changeExerciseVariant(workoutExerciseId: Long, variantSyncId: String?): Boolean = database.withTransaction {
        val row = workoutDao.getWorkoutExercise(workoutExerciseId) ?: return@withTransaction false
        val workout = workoutDao.getWorkoutFull(row.workoutId)?.workout ?: return@withTransaction false
        if (workout.finishedAt != null || workoutDao.completedSetCount(workoutExerciseId) != 0) return@withTransaction false
        val snapshot = variantSyncId?.let { id -> variantDao.getOwned(row.exerciseId, id)?.name } ?: ""
        if (variantSyncId != null && snapshot.isEmpty()) return@withTransaction false
        workoutDao.updateWorkoutExercise(row.copy(variantSyncId = variantSyncId, variantNameSnapshot = snapshot.ifEmpty { null }))
        val previous = workoutDao.lastCompletedSetsForKey(row.exerciseId, variantSyncId)
        val existing = workoutDao.getSetsForWorkoutExercise(workoutExerciseId)
        workoutDao.insertSets(emptyList())
        existing.forEachIndexed { index, set ->
            val source = previous.getOrNull(index)
            workoutDao.updateSet(
                set.copy(
                    weightKg = source?.weightKg,
                    reps = source?.reps,
                    durationSec = source?.durationSec,
                    speedKmh = source?.speedKmh,
                    inclinePct = source?.inclinePct,
                ),
            )
        }
        true
    }

    override suspend fun deleteExercise(workoutExerciseId: Long) =
        workoutDao.deleteWorkoutExercise(workoutExerciseId)

    override suspend fun reorderExercises(
        workoutId: String,
        orderedWorkoutExerciseIds: List<Long>,
    ) = database.withTransaction {
        val existing = workoutDao.getWorkoutExercises(workoutId)
        val existingIds = existing.map { it.id }
        require(
            orderedWorkoutExerciseIds.size == existingIds.size &&
                orderedWorkoutExerciseIds.toSet().size == orderedWorkoutExerciseIds.size &&
                orderedWorkoutExerciseIds.toSet() == existingIds.toSet(),
        ) { "The supplied exercise ids must be the complete unique set for workout $workoutId" }

        val byId = existing.associateBy { it.id }
        workoutDao.updateWorkoutExercises(
            orderedWorkoutExerciseIds.mapIndexed { position, id ->
                requireNotNull(byId[id]).copy(position = position)
            },
        )
    }

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
