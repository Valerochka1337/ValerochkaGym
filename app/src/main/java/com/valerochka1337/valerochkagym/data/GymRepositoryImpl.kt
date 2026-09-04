package com.valerochka1337.valerochkagym.data

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.GymDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ConfigurationTombstoneEntity
import com.valerochka1337.valerochkagym.data.db.entity.ConfigurationTombstoneKind
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
import com.valerochka1337.valerochkagym.data.db.entity.GymExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.entity.withNextUpdatedAt
import com.valerochka1337.valerochkagym.domain.DeleteGymResult
import com.valerochka1337.valerochkagym.domain.GymConfiguration
import com.valerochka1337.valerochkagym.domain.GymConfigurationConflict
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.GymRoutineReference
import com.valerochka1337.valerochkagym.domain.NewExerciseConfiguration
import com.valerochka1337.valerochkagym.domain.RoutineConfigurationDraft
import com.valerochka1337.valerochkagym.domain.RoutineDeletion
import com.valerochka1337.valerochkagym.domain.SaveGymResult
import com.valerochka1337.valerochkagym.domain.SaveRoutineConfigurationResult
import com.valerochka1337.valerochkagym.worker.ConfigurationUploadScheduler
import com.valerochka1337.valerochkagym.worker.NoOpConfigurationUploadScheduler
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)
class GymRepositoryImpl @Inject constructor(
    private val database: GymDatabase,
    private val gymDao: GymDao,
    private val exerciseDao: ExerciseDao,
    private val exerciseMuscleDao: ExerciseMuscleDao,
    private val routineDao: RoutineDao,
    private val workoutDao: WorkoutDao,
    private val configurationUploadScheduler: ConfigurationUploadScheduler =
        NoOpConfigurationUploadScheduler,
) : GymRepository {

    override fun observeGyms(): Flow<List<GymConfiguration>> =
        gymDao.observeGyms().flatMapLatest { gyms ->
            if (gyms.isEmpty()) return@flatMapLatest flowOf(emptyList())
            combine(
                exerciseDao.getAll(),
                gymDao.observeGymExerciseIds(gyms.map(GymEntity::id)),
            ) { exercises, links ->
                val exercisesById = exercises.associateBy(ExerciseEntity::id)
                val exerciseIdsByGym = links.groupBy({ it.gymId }, { it.exerciseId })
                gyms.map { gym ->
                    GymConfiguration(
                        id = gym.syncId,
                        name = gym.name,
                        exercises = exerciseIdsByGym[gym.id].orEmpty().mapNotNull(exercisesById::get),
                    )
                }
            }
        }

    override fun observeExerciseCatalog(): Flow<List<ExerciseEntity>> = exerciseDao.getAll()

    override fun observeAvailableExercises(gymIds: Set<String>): Flow<List<ExerciseEntity>> {
        if (gymIds.isEmpty()) return exerciseDao.getAll()
        return gymDao.observeGyms().flatMapLatest { gyms ->
            val selected = gyms.filter { it.syncId in gymIds }
            if (selected.size != gymIds.size) return@flatMapLatest flowOf(emptyList())
            combine(
                exerciseDao.getAll(),
                gymDao.observeGymExerciseIds(selected.map(GymEntity::id)),
            ) { exercises, links ->
                val availability = links.groupingBy { it.exerciseId }.eachCount()
                exercises.filter { availability[it.id] == selected.size }
            }
        }
    }

    override suspend fun getGym(id: String): GymConfiguration? {
        val gym = gymDao.getGymBySyncId(id) ?: return null
        val full = gymDao.getGymWithExercises(gym.id) ?: return null
        return GymConfiguration(gym.syncId, gym.name, full.exercises.sortedBy { it.name.lowercase() })
    }

    override suspend fun saveGym(
        id: String?,
        name: String,
        exerciseIds: Set<Long>,
    ): SaveGymResult = runMutation {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) return@runMutation SaveGymResult.Failure
        val result = database.withTransaction {
            val gyms = gymDao.getGyms()
            if (gyms.any { it.syncId != id && it.name.equals(normalizedName, ignoreCase = true) }) {
                return@withTransaction SaveGymResult.NameAlreadyExists
            }
            val existing = id?.let { gymDao.getGymBySyncId(it) }
            if (id != null && existing == null) return@withTransaction SaveGymResult.NotFound

            if (existing != null) {
                val conflicts = gymEditConflicts(existing.id, exerciseIds)
                if (conflicts.routines.isNotEmpty() || conflicts.exercises.isNotEmpty()) {
                    return@withTransaction SaveGymResult.Conflict(conflicts)
                }
            }
            val saved = if (existing == null) {
                GymEntity(syncId = id ?: UUID.randomUUID().toString(), name = normalizedName)
            } else {
                existing.copy(name = normalizedName).withNextUpdatedAt()
            }
            val localId = if (existing == null) gymDao.insertGym(saved) else existing.id.also {
                gymDao.updateGym(saved)
            }
            gymDao.replaceGymExercises(localId, exerciseIds.toList())
            SaveGymResult.Saved(saved.syncId)
        }
        if (result is SaveGymResult.Saved) {
            configurationUploadScheduler.scheduleGym(result.gymId)
        }
        result
    }

    override suspend fun deleteGym(id: String): DeleteGymResult = runDelete {
        val outcome = database.withTransaction {
            val gym = gymDao.getGymBySyncId(id)
                ?: return@withTransaction GymDeletionOutcome(DeleteGymResult.NotFound)
            val blockers = linkedReferences(gym.id)
            if (blockers.isNotEmpty()) {
                return@withTransaction GymDeletionOutcome(DeleteGymResult.InUse(blockers))
            }
            if (gymDao.deleteGym(gym.id) == 0) {
                GymDeletionOutcome(DeleteGymResult.NotFound)
            } else {
                val deletedAt = gym.withNextUpdatedAt().updatedAt
                database.configurationTombstoneDao().upsert(
                    ConfigurationTombstoneEntity(
                        kind = ConfigurationTombstoneKind.GYM,
                        syncId = gym.syncId,
                        updatedAt = deletedAt,
                    ),
                )
                GymDeletionOutcome(
                    result = DeleteGymResult.Deleted,
                    syncId = gym.syncId,
                    updatedAt = deletedAt,
                )
            }
        }
        if (outcome.result == DeleteGymResult.Deleted) {
            configurationUploadScheduler.scheduleGymDeletion(
                requireNotNull(outcome.syncId),
                requireNotNull(outcome.updatedAt),
            )
        }
        outcome.result
    }

    override suspend fun unavailableExercises(
        gymIds: Set<String>,
        exerciseIds: Set<Long>,
    ): List<ExerciseEntity> {
        if (gymIds.isEmpty() || exerciseIds.isEmpty()) return emptyList()
        val selected = resolveGyms(gymIds) ?: return exerciseDao.getAllOnce().filter { it.id in exerciseIds }
        val availableIds = gymDao.getAvailableExercises(selected.map(GymEntity::id), selected.size)
            .mapTo(hashSetOf(), ExerciseEntity::id)
        return exerciseDao.getAllOnce().filter { it.id in exerciseIds && it.id !in availableIds }
    }

    override suspend fun createExerciseAndAssign(
        configuration: NewExerciseConfiguration,
        gymIds: Set<String>,
    ): ExerciseEntity? = try {
        val saved = database.withTransaction {
            val gyms = resolveGyms(gymIds) ?: return@withTransaction null
            val exerciseId = exerciseDao.insert(configuration.exercise.copy(id = 0))
            val exercise = configuration.exercise.copy(id = exerciseId)
            exerciseMuscleDao.replaceForExercise(
                exerciseId,
                configuration.muscles.map { it.copy(exerciseId = exerciseId) },
            )
            gyms.forEach { gym ->
                gymDao.insertGymExercises(listOf(GymExerciseEntity(gym.id, exerciseId)))
                gymDao.updateGym(gym.withNextUpdatedAt())
            }
            exercise
        }
        saved?.let { exercise ->
            configurationUploadScheduler.scheduleExercise(exercise.syncId)
            gymIds.forEach(configurationUploadScheduler::scheduleGym)
        }
        saved
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    override suspend fun assignExerciseToGyms(exerciseId: Long, gymIds: Set<String>): Boolean = try {
        val assigned = database.withTransaction {
            if (exerciseDao.getById(exerciseId) == null) return@withTransaction false
            val gyms = resolveGyms(gymIds) ?: return@withTransaction false
            gyms.forEach { gym ->
                val updatedIds = (gymDao.getGymExerciseIds(gym.id) + exerciseId).distinct()
                gymDao.replaceGymExercises(gym.id, updatedIds)
                gymDao.updateGym(gym.withNextUpdatedAt())
            }
            true
        }
        if (assigned) gymIds.forEach(configurationUploadScheduler::scheduleGym)
        assigned
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    override suspend fun createExerciseAssignAndAddToWorkout(
        configuration: NewExerciseConfiguration,
        gymIds: Set<String>,
        workoutId: String,
    ): ExerciseEntity? = try {
        val saved = database.withTransaction {
            val workout = workoutDao.getWorkoutFull(workoutId)?.workout
                ?.takeIf { it.finishedAt == null }
                ?: return@withTransaction null
            val gyms = resolveGyms(gymIds) ?: return@withTransaction null
            val snapshotGymIds = gymDao.getGymsForWorkout(workout.id).mapTo(hashSetOf()) { it.syncId }
            if (snapshotGymIds != gymIds) return@withTransaction null

            val exerciseId = exerciseDao.insert(configuration.exercise.copy(id = 0))
            val exercise = configuration.exercise.copy(id = exerciseId)
            exerciseMuscleDao.replaceForExercise(
                exerciseId,
                configuration.muscles.map { it.copy(exerciseId = exerciseId) },
            )
            gyms.forEach { gym ->
                gymDao.insertGymExercises(listOf(GymExerciseEntity(gym.id, exerciseId)))
                gymDao.updateGym(gym.withNextUpdatedAt())
            }
            val position = (workoutDao.getWorkoutExercises(workoutId).maxOfOrNull { it.position } ?: -1) + 1
            val workoutExerciseId = workoutDao.insertWorkoutExercise(
                WorkoutExerciseEntity(workoutId = workoutId, exerciseId = exerciseId, position = position),
            )
            workoutDao.insertSet(
                WorkoutSetEntity(workoutExerciseId = workoutExerciseId, setIndex = 0),
            )
            exercise
        }
        saved?.let { exercise ->
            configurationUploadScheduler.scheduleExercise(exercise.syncId)
            gymIds.forEach(configurationUploadScheduler::scheduleGym)
        }
        saved
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    override suspend fun updateExerciseAndAssign(
        configuration: NewExerciseConfiguration,
        gymIds: Set<String>,
    ): ExerciseEntity? = try {
        val updated = database.withTransaction {
            val existing = exerciseDao.getById(configuration.exercise.id)
                ?: return@withTransaction null
            val gyms = resolveGyms(gymIds) ?: return@withTransaction null
            val saved = configuration.exercise.copy(
                id = existing.id,
                syncId = existing.syncId,
                updatedAt = existing.updatedAt,
            ).withNextUpdatedAt()
            exerciseDao.update(saved)
            exerciseMuscleDao.replaceForExercise(
                saved.id,
                configuration.muscles.map { it.copy(exerciseId = saved.id) },
            )
            gyms.forEach { gym ->
                gymDao.replaceGymExercises(
                    gym.id,
                    (gymDao.getGymExerciseIds(gym.id) + saved.id).distinct(),
                )
                gymDao.updateGym(gym.withNextUpdatedAt())
            }
            saved
        }
        updated?.let {
            configurationUploadScheduler.scheduleExercise(it.syncId)
            gymIds.forEach(configurationUploadScheduler::scheduleGym)
        }
        updated
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    override suspend fun updateExerciseAssignAndAddToWorkout(
        configuration: NewExerciseConfiguration,
        gymIds: Set<String>,
        workoutId: String,
    ): ExerciseEntity? = try {
        val updated = database.withTransaction {
            val existing = exerciseDao.getById(configuration.exercise.id)
                ?: return@withTransaction null
            val workout = workoutDao.getWorkoutFull(workoutId)?.workout
                ?.takeIf { it.finishedAt == null }
                ?: return@withTransaction null
            val gyms = resolveGyms(gymIds) ?: return@withTransaction null
            val snapshotGymIds = gymDao.getGymsForWorkout(workout.id).mapTo(hashSetOf()) { it.syncId }
            if (snapshotGymIds != gymIds) return@withTransaction null

            val saved = configuration.exercise.copy(
                id = existing.id,
                syncId = existing.syncId,
                updatedAt = existing.updatedAt,
            ).withNextUpdatedAt()
            exerciseDao.update(saved)
            exerciseMuscleDao.replaceForExercise(
                saved.id,
                configuration.muscles.map { it.copy(exerciseId = saved.id) },
            )
            gyms.forEach { gym ->
                gymDao.replaceGymExercises(
                    gym.id,
                    (gymDao.getGymExerciseIds(gym.id) + saved.id).distinct(),
                )
                gymDao.updateGym(gym.withNextUpdatedAt())
            }
            val position = (workoutDao.getWorkoutExercises(workoutId).maxOfOrNull { it.position } ?: -1) + 1
            val workoutExerciseId = workoutDao.insertWorkoutExercise(
                WorkoutExerciseEntity(workoutId = workoutId, exerciseId = saved.id, position = position),
            )
            val previous = workoutDao.lastCompletedSetsForExercise(saved.id).firstOrNull()
            workoutDao.insertSet(
                WorkoutSetEntity(
                    workoutExerciseId = workoutExerciseId,
                    setIndex = 0,
                    weightKg = previous?.weightKg,
                    reps = previous?.reps,
                    durationSec = previous?.durationSec,
                    speedKmh = previous?.speedKmh,
                    inclinePct = previous?.inclinePct,
                ),
            )
            saved
        }
        updated?.let {
            configurationUploadScheduler.scheduleExercise(it.syncId)
            gymIds.forEach(configurationUploadScheduler::scheduleGym)
        }
        updated
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    override suspend fun saveRoutineConfiguration(
        draft: RoutineConfigurationDraft,
    ): SaveRoutineConfigurationResult = try {
        database.withTransaction {
            if (draft.routine.id == 0L) {
                routineDao.getRoutineBySyncId(draft.routine.syncId)?.let { existing ->
                    return@withTransaction SaveRoutineConfigurationResult.Saved(
                        routineId = existing.id,
                        routine = existing,
                    )
                }
            }
            val gyms = resolveGyms(draft.gymIds)
                ?: return@withTransaction SaveRoutineConfigurationResult.GymNotFound
            if (gyms.isNotEmpty()) {
                val requestedIds = draft.exercises.mapTo(linkedSetOf()) { it.exerciseId }
                val availableIds = gymDao.getAvailableExercises(gyms.map(GymEntity::id), gyms.size)
                    .mapTo(hashSetOf(), ExerciseEntity::id)
                val conflicts = exerciseDao.getAllOnce()
                    .filter { it.id in requestedIds && it.id !in availableIds }
                if (conflicts.isNotEmpty()) {
                    return@withTransaction SaveRoutineConfigurationResult.Conflict(conflicts)
                }
            }
            val insertedId = routineDao.upsertRoutine(draft.routine)
            val routineId = draft.routine.id.takeIf { it != 0L } ?: insertedId
            routineDao.replaceRoutineExercises(
                routineId,
                draft.exercises.map { it.copy(routineId = routineId) },
            )
            gymDao.replaceRoutineGyms(routineId, gyms.map(GymEntity::id))
            SaveRoutineConfigurationResult.Saved(
                routineId = routineId,
                routine = draft.routine.copy(id = routineId),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        SaveRoutineConfigurationResult.Failure
    }

    override suspend fun duplicateRoutine(sourceRoutineId: Long): RoutineEntity? =
        try {
            database.withTransaction {
                val source = routineDao.getRoutineWithExercises(sourceRoutineId)
                    ?: return@withTransaction null
                val copy = RoutineEntity(
                    name = "${source.routine.name} (копия)",
                    note = source.routine.note,
                )
                val newId = routineDao.upsertRoutine(copy)
                routineDao.replaceRoutineExercises(
                    newId,
                    source.exercises
                        .sortedBy { it.routineExercise.position }
                        .mapIndexed { index, item ->
                            item.routineExercise.copy(
                                id = 0,
                                routineId = newId,
                                position = index,
                            )
                        },
                )
                gymDao.replaceRoutineGyms(newId, source.gyms.map { it.id })
                copy.copy(id = newId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    override suspend fun deleteRoutine(routineId: Long): RoutineDeletion? = try {
        database.withTransaction {
            val routine = routineDao.getRoutineWithExercises(routineId)?.routine
                ?: return@withTransaction null
            val deletedAt = routine.withNextUpdatedAt().updatedAt
            database.configurationTombstoneDao().upsert(
                ConfigurationTombstoneEntity(
                    kind = ConfigurationTombstoneKind.ROUTINE,
                    syncId = routine.syncId,
                    updatedAt = deletedAt,
                ),
            )
            routineDao.deleteRoutine(routineId)
            RoutineDeletion(routine.syncId, deletedAt)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun resolveGyms(ids: Set<String>): List<GymEntity>? {
        if (ids.isEmpty()) return emptyList()
        val gyms = gymDao.getGyms().filter { it.syncId in ids }
        return gyms.takeIf { it.size == ids.size }
    }

    private suspend fun gymEditConflicts(
        gymId: Long,
        proposedExerciseIds: Set<Long>,
    ): GymConfigurationConflict {
        val routines = gymDao.getLinkedRoutines(gymId)
        val conflictingExerciseIds = linkedSetOf<Long>()
        val references = linkedSetOf<GymRoutineReference>()
        routines.forEach { routine ->
            val unavailableIds = routineDao.getRoutineWithExercises(routine.id)?.exercises.orEmpty()
                .map { it.exercise.id }
                .filter { it !in proposedExerciseIds }
            if (unavailableIds.isNotEmpty()) {
                conflictingExerciseIds += unavailableIds
                references += GymRoutineReference(routine.id, routine.name)
            }
        }
        val activeWorkouts = gymDao.getLinkedActiveWorkouts(gymId)
        activeWorkouts.forEach { workout ->
            val unavailableIds = workoutDao.getWorkoutFull(workout.id)?.exercises.orEmpty()
                .map { it.exercise.id }
                .filter { it !in proposedExerciseIds }
            if (unavailableIds.isNotEmpty()) {
                conflictingExerciseIds += unavailableIds
                references += GymRoutineReference(-1, "Активная тренировка «${workout.name}»")
            }
        }
        val exercises = exerciseDao.getAllOnce().filter { it.id in conflictingExerciseIds }
        return GymConfigurationConflict(references.toList(), exercises)
    }

    private suspend fun linkedReferences(gymId: Long): List<GymRoutineReference> =
        gymDao.getLinkedRoutines(gymId).map { GymRoutineReference(it.id, it.name) } +
            gymDao.getLinkedActiveWorkouts(gymId).map {
                GymRoutineReference(-1, "Активная тренировка «${it.name}»")
            }

    private suspend inline fun runMutation(block: suspend () -> SaveGymResult): SaveGymResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        SaveGymResult.Failure
    }

    private suspend inline fun runDelete(block: suspend () -> DeleteGymResult): DeleteGymResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DeleteGymResult.Failure
    }
}

private data class GymDeletionOutcome(
    val result: DeleteGymResult,
    val syncId: String? = null,
    val updatedAt: Long? = null,
)
