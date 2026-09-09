package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.worker.RoutineUploadScheduler
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

sealed interface RoutineUpdateResult {
  data class Saved(val replayedWithoutWrite: Boolean) : RoutineUpdateResult

  data object EmptyWorkout : RoutineUpdateResult

  data object ReadOnly : RoutineUpdateResult

  data object NotFound : RoutineUpdateResult

  data object Conflict : RoutineUpdateResult

  data object Failure : RoutineUpdateResult
}

data class RoutineReplacementSnapshot(
    val command: CompletedWorkoutRoutineCommand.Replace,
)

/**
 * Сравнение фактически выполненной тренировки с её программой и перезапись программы по факту (для
 * предложения «обновить программу?» после завершения тренировки по программе).
 */
class RoutineUpdateUseCase
@Inject
constructor(
    private val routineDao: RoutineDao,
    private val gymRepository: GymRepository,
    private val routineUploadScheduler: RoutineUploadScheduler,
) {

  suspend fun canReplace(workout: WorkoutFull): Boolean {
    val routineId = workout.workout.routineId ?: return false
    val routine = routineDao.getRoutineWithExercises(routineId)?.routine ?: return false
    return routine.origin == "PERSONAL" && !routine.archived
  }

  /**
   * true, если состав/подходы фактически выполненной тренировки расходятся с программой. false,
   * если тренировка без программы или программа удалена.
   */
  suspend fun hasDiverged(workout: WorkoutFull): Boolean {
    val routineId = workout.workout.routineId ?: return false
    val routine = routineDao.getRoutineWithExercises(routineId) ?: return false

    val actual = performed(workout).map { (exerciseId, sets) -> exerciseId to sets }
    val planned =
        routine.exercises
            .sortedBy { it.routineExercise.position }
            .map { it.exercise.id to it.routineExercise.plannedSets }
    return actual != planned
  }

  /**
   * Перезаписывает состав и plannedSets программы фактически выполненными подходами: только
   * isCompleted, упражнения без выполненных подходов не попадают, restSeconds сохраняются для
   * оставшихся упражнений, новые — null.
   */
  suspend fun applyToRoutine(
      workout: WorkoutFull,
      operationUuid: String = workout.workout.id,
  ): RoutineUpdateResult {
    val snapshot = prepareReplacement(workout, operationUuid) ?: return RoutineUpdateResult.NotFound
    return apply(snapshot)
  }

  /** Captures the source aggregate at the moment the user opens replace confirmation. */
  suspend fun prepareReplacement(
      workout: WorkoutFull,
      operationUuid: String,
  ): RoutineReplacementSnapshot? {
    val routineId = workout.workout.routineId ?: return null
    val routine = routineDao.getRoutineWithExercises(routineId) ?: return null
    if (routine.routine.origin != "PERSONAL" || routine.routine.archived) return null
    val restByExercise =
        routine.exercises.associate { it.exercise.id to it.routineExercise.restSeconds }
    val entities =
        performed(workout).mapIndexed { position, (exerciseId, sets) ->
          RoutineExerciseEntity(
              routineId = routineId,
              exerciseId = exerciseId,
              position = position,
              restSeconds = restByExercise[exerciseId],
              plannedSets = sets,
          )
        }
    if (entities.isEmpty()) return null
    return RoutineReplacementSnapshot(
        CompletedWorkoutRoutineCommand.Replace(
            sourceRoutineId = routine.routine.id,
            sourceRoutineSyncId = routine.routine.syncId,
            expectedUpdatedAt = routine.routine.updatedAt,
            expectedSourceFingerprint = routine.completedWorkoutFingerprint(),
            predictedTargetFingerprint = routine.completedWorkoutFingerprint(entities),
            replacementExercises = entities,
            operationUuid = operationUuid,
        )
    )
  }

  /** Rebuilds only the child rows after recreation; optimistic source/target values stay frozen. */
  suspend fun restoreReplacement(
      workout: WorkoutFull,
      sourceRoutineId: Long,
      sourceRoutineSyncId: String,
      expectedUpdatedAt: Long,
      expectedSourceFingerprint: String,
      predictedTargetFingerprint: String,
      operationUuid: String,
  ): RoutineReplacementSnapshot? {
    if (
        workout.workout.routineId != sourceRoutineId ||
            sourceRoutineSyncId.isBlank() ||
            expectedSourceFingerprint.isBlank() ||
            predictedTargetFingerprint.isBlank() ||
            operationUuid.isBlank()
    )
        return null
    val current = routineDao.getRoutineWithExercises(sourceRoutineId) ?: return null
    val restByExercise =
        current.exercises.associate { it.exercise.id to it.routineExercise.restSeconds }
    val entities =
        performed(workout).mapIndexed { position, (exerciseId, sets) ->
          RoutineExerciseEntity(
              routineId = sourceRoutineId,
              exerciseId = exerciseId,
              position = position,
              restSeconds = restByExercise[exerciseId],
              plannedSets = sets,
          )
        }
    if (entities.isEmpty()) return null
    return RoutineReplacementSnapshot(
        CompletedWorkoutRoutineCommand.Replace(
            sourceRoutineId = sourceRoutineId,
            sourceRoutineSyncId = sourceRoutineSyncId,
            expectedUpdatedAt = expectedUpdatedAt,
            expectedSourceFingerprint = expectedSourceFingerprint,
            predictedTargetFingerprint = predictedTargetFingerprint,
            replacementExercises = entities,
            operationUuid = operationUuid,
        )
    )
  }

  suspend fun apply(snapshot: RoutineReplacementSnapshot): RoutineUpdateResult {
    return try {
      when (val result = gymRepository.saveCompletedWorkoutRoutine(snapshot.command)) {
        is CompletedWorkoutRoutineResult.Saved -> {
          try {
            routineUploadScheduler.schedule(result.routine.syncId)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            // The transaction is durable; retry scheduling through the established upload-all path.
          }
          RoutineUpdateResult.Saved(result.replayedWithoutWrite)
        }
        is CompletedWorkoutRoutineResult.AvailabilityConflict -> RoutineUpdateResult.Conflict
        CompletedWorkoutRoutineResult.ReadOnly -> RoutineUpdateResult.ReadOnly
        CompletedWorkoutRoutineResult.NotFound -> RoutineUpdateResult.NotFound
        CompletedWorkoutRoutineResult.Conflict -> RoutineUpdateResult.Conflict
        CompletedWorkoutRoutineResult.Failure -> RoutineUpdateResult.Failure
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      RoutineUpdateResult.Failure
    }
  }

  /**
   * Фактически выполненный состав: упражнения по позиции, у каждого — выполненные подходы (по
   * setIndex) как plannedSets. Упражнения без выполненных подходов исключены.
   */
  private fun performed(workout: WorkoutFull): List<Pair<Long, List<PlannedSet>>> =
      workout.exercises
          .sortedBy { it.workoutExercise.position }
          .mapNotNull { exercise: WorkoutExerciseWithSets ->
            val completed = exercise.sets.filter { it.isCompleted }.sortedBy { it.setIndex }
            if (completed.isEmpty()) null
            else exercise.exercise.id to completed.map { it.toPlannedSet() }
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
