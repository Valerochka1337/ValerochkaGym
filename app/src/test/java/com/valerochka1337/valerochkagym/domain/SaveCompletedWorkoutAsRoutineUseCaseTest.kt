package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.worker.RoutineUploadScheduler
import com.valerochka1337.valerochkagym.worker.NoOpRoutineUploadScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveCompletedWorkoutAsRoutineUseCaseTest {

    @Test
    fun `blank name does not save a routine`() = runTest {
        val repository = FakeGymRepository()

        val result = SaveCompletedWorkoutAsRoutineUseCase(repository, NoOpRoutineUploadScheduler)(
            workout(), "  \n ", "operation-blank",
        )

        assertEquals(SaveCompletedWorkoutAsRoutineResult.BlankName, result)
        assertTrue(repository.drafts.isEmpty())
    }

    @Test
    fun `completed sections are mapped in source position with every execution field`() = runTest {
        val repository = FakeGymRepository()
        val source = workout()

        val result = SaveCompletedWorkoutAsRoutineUseCase(repository, NoOpRoutineUploadScheduler)(
            source, "  Верх тела  ", "operation-map",
        )

        assertTrue(result is SaveCompletedWorkoutAsRoutineResult.Saved)
        val draft = repository.drafts.single()
        assertEquals("Верх тела", draft.routine.name)
        assertEquals("operation-map", draft.routine.syncId)
        assertEquals("", draft.routine.note)
        assertEquals(emptySet<String>(), draft.gymIds)
        assertEquals(listOf(3L, 1L), draft.exercises.map { it.exerciseId })
        assertEquals(listOf(2, 7), draft.exercises.map { it.position })
        assertTrue(draft.exercises.all { it.id == 0L && it.routineId == 0L && it.restSeconds == null })
        assertEquals(
            listOf(
                PlannedSet(weightKg = 42.5, reps = 9, durationSec = 45, speedKmh = 7.2, inclinePct = 4.0),
                PlannedSet(weightKg = 50.0, reps = 7, durationSec = 50, speedKmh = 7.5, inclinePct = 5.0),
            ),
            draft.exercises.first().plannedSets,
        )
        assertEquals(listOf(PlannedSet(weightKg = 80.0, reps = 5)), draft.exercises.last().plannedSets)
    }

    @Test
    fun `incomplete sets and empty sections are excluded without mutating source`() = runTest {
        val repository = FakeGymRepository()
        val source = workout()
        val before = source.copy(
            exercises = source.exercises.map { it.copy(sets = it.sets.toList()) },
            gyms = source.gyms.toList(),
        )

        SaveCompletedWorkoutAsRoutineUseCase(repository, NoOpRoutineUploadScheduler)(
            source, "Программа", "operation-source",
        )

        assertEquals(listOf(3L, 1L), repository.drafts.single().exercises.map { it.exerciseId })
        assertEquals(before, source)
    }

    @Test
    fun `only a saved configuration schedules its fresh routine once`() = runTest {
        val scheduler = FakeRoutineUploadScheduler()
        val repository = FakeGymRepository()
        val useCase = SaveCompletedWorkoutAsRoutineUseCase(repository, scheduler)

        val first = useCase(workout(), "Повтор", "operation-first")
        val firstSyncId = (first as SaveCompletedWorkoutAsRoutineResult.Saved).routine.syncId
        val second = useCase(workout(), "Повтор", "operation-second")
        val secondSyncId = (second as SaveCompletedWorkoutAsRoutineResult.Saved).routine.syncId

        assertFalse(firstSyncId == secondSyncId)
        assertEquals(listOf(firstSyncId, secondSyncId), scheduler.scheduledSyncIds)
    }

    @Test
    fun `replaying an operation reuses its routine and schedules its same sync id`() = runTest {
        val scheduler = FakeRoutineUploadScheduler()
        val repository = FakeGymRepository()
        val useCase = SaveCompletedWorkoutAsRoutineUseCase(repository, scheduler)

        val first = useCase(workout(), "Повтор", "operation-replay") as SaveCompletedWorkoutAsRoutineResult.Saved
        val replay = useCase(workout(), "Повтор", "operation-replay") as SaveCompletedWorkoutAsRoutineResult.Saved

        assertEquals(first.routine, replay.routine)
        assertEquals(1, repository.durableRoutines.size)
        assertEquals(listOf("operation-replay", "operation-replay"), scheduler.scheduledSyncIds)
    }

    @Test
    fun `scheduler failure after a durable save still returns saved without another write`() = runTest {
        val repository = FakeGymRepository()
        val scheduler = ThrowingRoutineUploadScheduler()

        val result = SaveCompletedWorkoutAsRoutineUseCase(repository, scheduler)(
            workout(), "Программа", "operation-scheduler",
        )

        assertTrue(result is SaveCompletedWorkoutAsRoutineResult.Saved)
        assertEquals(1, repository.drafts.size)
        assertEquals(1, scheduler.scheduleCalls)
    }

    @Test
    fun `repository outcomes are returned and never scheduled`() = runTest {
        val conflictExercise = exercise(99, "Недоступное")
        listOf<SaveRoutineConfigurationResult>(
            SaveRoutineConfigurationResult.Conflict(listOf(conflictExercise)),
            SaveRoutineConfigurationResult.GymNotFound,
            SaveRoutineConfigurationResult.Failure,
        ).forEach { outcome ->
            val scheduler = FakeRoutineUploadScheduler()
            val repository = FakeGymRepository(outcome)

            val result = SaveCompletedWorkoutAsRoutineUseCase(repository, scheduler)(
                workout(), "Программа", "operation-${outcome::class.simpleName}",
            )

            when (outcome) {
                is SaveRoutineConfigurationResult.Conflict ->
                    assertEquals(SaveCompletedWorkoutAsRoutineResult.Conflict(listOf(conflictExercise)), result)
                SaveRoutineConfigurationResult.GymNotFound -> assertEquals(SaveCompletedWorkoutAsRoutineResult.GymNotFound, result)
                SaveRoutineConfigurationResult.Failure -> assertEquals(SaveCompletedWorkoutAsRoutineResult.Failure, result)
                is SaveRoutineConfigurationResult.Saved -> error("Covered separately")
            }
            assertTrue(scheduler.scheduledSyncIds.isEmpty())
        }
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is rethrown`() = runTest {
        val repository = FakeGymRepository(throwCancellation = true)

        SaveCompletedWorkoutAsRoutineUseCase(repository, NoOpRoutineUploadScheduler)(
            workout(), "Программа", "operation-cancel",
        )
    }

    private fun workout(): WorkoutFull = WorkoutFull(
        workout = WorkoutEntity(id = "workout", routineId = 44, name = "Источник", startedAt = 10, finishedAt = 20),
        exercises = listOf(
            section(
                sectionId = 7,
                exercise = exercise(1, "Жим"),
                position = 7,
                sets = listOf(
                    set(index = 2, weight = 80.0, reps = 5, completed = true),
                    set(index = 1, weight = 80.0, reps = 6, completed = false),
                ),
            ),
            section(
                sectionId = 8,
                exercise = exercise(2, "Пустое"),
                position = 4,
                sets = listOf(set(index = 0, weight = 10.0, reps = 1, completed = false)),
            ),
            section(
                sectionId = 9,
                exercise = exercise(3, "Дорожка"),
                position = 2,
                sets = listOf(
                    set(index = 4, weight = 50.0, reps = 7, duration = 50, speed = 7.5, incline = 5.0, completed = true),
                    set(index = 3, weight = 42.5, reps = 9, duration = 45, speed = 7.2, incline = 4.0, completed = true),
                ),
            ),
        ),
        gyms = listOf(GymEntity(syncId = "gym-a", name = "А"), GymEntity(syncId = "gym-b", name = "Б")),
    )

    private fun section(
        sectionId: Long,
        exercise: ExerciseEntity,
        position: Int,
        sets: List<WorkoutSetEntity>,
    ) = WorkoutExerciseWithSets(
        workoutExercise = WorkoutExerciseEntity(id = sectionId, workoutId = "workout", exerciseId = exercise.id, position = position),
        exercise = exercise,
        sets = sets,
    )

    private fun exercise(id: Long, name: String) = ExerciseEntity(
        id = id,
        name = name,
        muscleGroup = MuscleGroup.CHEST,
        type = ExerciseType.STRENGTH,
    )

    private fun set(
        index: Int,
        weight: Double,
        reps: Int,
        duration: Int? = null,
        speed: Double? = null,
        incline: Double? = null,
        completed: Boolean,
    ) = WorkoutSetEntity(
        workoutExerciseId = 9,
        setIndex = index,
        weightKg = weight,
        reps = reps,
        durationSec = duration,
        speedKmh = speed,
        inclinePct = incline,
        isCompleted = completed,
    )

    private class FakeGymRepository(
        private val outcome: SaveRoutineConfigurationResult? = null,
        private val throwCancellation: Boolean = false,
    ) : GymRepository by NoOpGymRepository {
        val drafts = mutableListOf<RoutineConfigurationDraft>()
        val durableRoutines = linkedMapOf<String, RoutineEntity>()

        override suspend fun saveRoutineConfiguration(draft: RoutineConfigurationDraft): SaveRoutineConfigurationResult {
            if (throwCancellation) throw CancellationException()
            drafts += draft
            outcome?.let { return it }
            val saved = durableRoutines.getOrPut(draft.routine.syncId) {
                draft.routine.copy(id = durableRoutines.size + 1L)
            }
            return SaveRoutineConfigurationResult.Saved(saved.id, saved)
        }
    }

    private class FakeRoutineUploadScheduler : RoutineUploadScheduler {
        val scheduledSyncIds = mutableListOf<String>()

        override fun schedule(syncId: String) {
            scheduledSyncIds += syncId
        }

        override fun scheduleDeletion(syncId: String, updatedAt: Long) = Unit
        override suspend fun scheduleAll(): Int = 0
    }

    private class ThrowingRoutineUploadScheduler : RoutineUploadScheduler {
        var scheduleCalls = 0

        override fun schedule(syncId: String) {
            scheduleCalls += 1
            throw IllegalStateException("WorkManager is unavailable")
        }

        override fun scheduleDeletion(syncId: String, updatedAt: Long) = Unit
        override suspend fun scheduleAll(): Int = 0
    }
}
