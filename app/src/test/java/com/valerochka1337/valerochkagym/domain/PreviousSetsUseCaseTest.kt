package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PreviousSetsUseCaseTest : RoomDaoTest() {

    private lateinit var workoutDao: WorkoutDao
    private lateinit var exerciseDao: ExerciseDao
    private lateinit var useCase: PreviousSetsUseCase

    @Before
    fun setUp() {
        workoutDao = db.workoutDao()
        exerciseDao = db.exerciseDao()
        useCase = PreviousSetsUseCase(workoutDao)
    }

    // region invoke

    @Test
    fun `invoke returns the completed sets of the last finished workout`() = runTest {
        val squat = addExercise("Присед")

        addWorkout("old", startedAt = 1_000, finishedAt = 2_000)
        val oldWe = addWorkoutExercise("old", squat)
        addSet(oldWe, setIndex = 0, weightKg = 90.0, reps = 5, isCompleted = true)

        addWorkout("new", startedAt = 3_000, finishedAt = 4_000)
        val newWe = addWorkoutExercise("new", squat)
        addSet(newWe, setIndex = 0, weightKg = 100.0, reps = 5, isCompleted = true)
        addSet(newWe, setIndex = 1, weightKg = 100.0, reps = 4, isCompleted = true)

        val result = useCase(squat)

        assertEquals(listOf(0, 1), result.map { it.setIndex })
        assertEquals(listOf(100.0, 100.0), result.map { it.weightKg })
        assertEquals(listOf(5, 4), result.map { it.reps })
    }

    // endregion

    // region formatSummary

    @Test
    fun `formatSummary formats strength sets without trailing zeros`() {
        val sets = listOf(
            strengthSet(weightKg = 30.0, reps = 10),
            strengthSet(weightKg = 32.5, reps = 8),
        )

        assertEquals("30×10, 32.5×8", useCase.formatSummary(sets, ExerciseType.STRENGTH))
    }

    @Test
    fun `formatSummary formats timed sets`() {
        val sets = listOf(timedSet(durationSec = 60))

        assertEquals("60 сек", useCase.formatSummary(sets, ExerciseType.TIMED))
    }

    @Test
    fun `formatSummary formats cardio sets`() {
        val sets = listOf(cardioSet(speedKmh = 10.0, inclinePct = 5.0, durationSec = 720))

        assertEquals("10 км/ч · 5% · 12 мин", useCase.formatSummary(sets, ExerciseType.CARDIO))
    }

    @Test
    fun `formatSummary returns an empty string for an empty list`() {
        assertEquals("", useCase.formatSummary(emptyList(), ExerciseType.STRENGTH))
    }

    // endregion

    // region helpers

    private suspend fun addExercise(name: String): Long =
        exerciseDao.insert(ExerciseEntity(name = name, muscleGroup = MuscleGroup.LEGS, type = ExerciseType.STRENGTH))

    private suspend fun addWorkout(id: String, startedAt: Long, finishedAt: Long?) {
        workoutDao.insertWorkout(
            WorkoutEntity(id = id, name = "Workout $id", startedAt = startedAt, finishedAt = finishedAt),
        )
    }

    private suspend fun addWorkoutExercise(workoutId: String, exerciseId: Long): Long =
        workoutDao.insertWorkoutExercise(
            WorkoutExerciseEntity(workoutId = workoutId, exerciseId = exerciseId, position = 0),
        )

    private suspend fun addSet(
        workoutExerciseId: Long,
        setIndex: Int,
        weightKg: Double?,
        reps: Int?,
        isCompleted: Boolean,
    ): Long =
        workoutDao.insertSet(
            WorkoutSetEntity(
                workoutExerciseId = workoutExerciseId,
                setIndex = setIndex,
                weightKg = weightKg,
                reps = reps,
                isCompleted = isCompleted,
            ),
        )

    private fun strengthSet(weightKg: Double, reps: Int): WorkoutSetEntity =
        WorkoutSetEntity(workoutExerciseId = 0, setIndex = 0, weightKg = weightKg, reps = reps)

    private fun timedSet(durationSec: Int): WorkoutSetEntity =
        WorkoutSetEntity(workoutExerciseId = 0, setIndex = 0, durationSec = durationSec)

    private fun cardioSet(speedKmh: Double, inclinePct: Double, durationSec: Int): WorkoutSetEntity =
        WorkoutSetEntity(
            workoutExerciseId = 0,
            setIndex = 0,
            speedKmh = speedKmh,
            inclinePct = inclinePct,
            durationSec = durationSec,
        )

    // endregion
}
