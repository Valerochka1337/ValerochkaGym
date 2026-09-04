package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.worker.RoutineUploadScheduler
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

sealed interface SaveCompletedWorkoutAsRoutineResult {
    data class Saved(val routine: RoutineEntity) : SaveCompletedWorkoutAsRoutineResult
    data object BlankName : SaveCompletedWorkoutAsRoutineResult
    data class Conflict(val exercises: List<ExerciseEntity>) : SaveCompletedWorkoutAsRoutineResult
    data object GymNotFound : SaveCompletedWorkoutAsRoutineResult
    data object Failure : SaveCompletedWorkoutAsRoutineResult
}

/**
 * Creates an independent routine snapshot from completed workout sets. The GymRepository owns
 * validation and the atomic persistence transaction; this use case owns only the mapping.
 */
class SaveCompletedWorkoutAsRoutineUseCase @Inject constructor(
    private val gymRepository: GymRepository,
    private val routineUploadScheduler: RoutineUploadScheduler,
) {
    suspend operator fun invoke(
        workout: WorkoutFull,
        enteredName: String,
    ): SaveCompletedWorkoutAsRoutineResult {
        val name = enteredName.trim()
        if (name.isBlank()) return SaveCompletedWorkoutAsRoutineResult.BlankName

        val routine = RoutineEntity(name = name, note = "")
        val draft = RoutineConfigurationDraft(
            routine = routine,
            exercises = workout.exercises
                .sortedBy { it.workoutExercise.position }
                .mapNotNull { section ->
                    val completedSets = section.sets
                        .filter { it.isCompleted }
                        .sortedBy { it.setIndex }
                    if (completedSets.isEmpty()) {
                        null
                    } else {
                        RoutineExerciseEntity(
                            id = 0,
                            routineId = 0,
                            exerciseId = section.exercise.id,
                            position = section.workoutExercise.position,
                            restSeconds = null,
                            plannedSets = completedSets.map(WorkoutSetEntity::toPlannedSet),
                        )
                    }
                },
            // A completed workout is historical evidence, not a current gym configuration.
            // New independent routines intentionally start without gym restrictions.
            gymIds = emptySet(),
        )

        return try {
            when (val result = gymRepository.saveRoutineConfiguration(draft)) {
                is SaveRoutineConfigurationResult.Saved -> {
                    try {
                        routineUploadScheduler.schedule(result.routine.syncId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // The routine transaction is already durable. The established upload-all
                        // recovery can enqueue it later; never turn this into a duplicate save.
                    }
                    SaveCompletedWorkoutAsRoutineResult.Saved(result.routine)
                }
                is SaveRoutineConfigurationResult.Conflict ->
                    SaveCompletedWorkoutAsRoutineResult.Conflict(result.exercises)
                SaveRoutineConfigurationResult.GymNotFound -> SaveCompletedWorkoutAsRoutineResult.GymNotFound
                SaveRoutineConfigurationResult.Failure -> SaveCompletedWorkoutAsRoutineResult.Failure
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            SaveCompletedWorkoutAsRoutineResult.Failure
        }
    }
}

private fun WorkoutSetEntity.toPlannedSet(): PlannedSet = PlannedSet(
    weightKg = weightKg,
    reps = reps,
    durationSec = durationSec,
    speedKmh = speedKmh,
    inclinePct = inclinePct,
)
