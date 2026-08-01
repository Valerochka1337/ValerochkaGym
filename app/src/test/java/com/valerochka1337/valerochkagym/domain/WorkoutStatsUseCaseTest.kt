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
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkoutStatsUseCaseTest : RoomDaoTest() {

    private lateinit var workoutDao: WorkoutDao
    private lateinit var exerciseDao: ExerciseDao
    private lateinit var useCase: WorkoutStatsUseCase

    @Before
    fun setUp() {
        workoutDao = db.workoutDao()
        exerciseDao = db.exerciseDao()
        useCase = WorkoutStatsUseCase(workoutDao)
    }

    // region volume

    @Test
    fun `volume sums weight times reps over completed strength sets only`() = runTest {
        val bench = addExercise("Жим", ExerciseType.STRENGTH)
        addWorkout("w", finishedAt = null)
        val we = addWorkoutExercise("w", bench, position = 0)
        addSet(we, setIndex = 0, weightKg = 50.0, reps = 10, isCompleted = true)
        addSet(we, setIndex = 1, weightKg = 60.0, reps = 8, isCompleted = true)
        addSet(we, setIndex = 2, weightKg = 100.0, reps = 5, isCompleted = false) // uncompleted -> ignored

        assertEquals(50.0 * 10 + 60.0 * 8, useCase.volume(full("w")), 0.0)
    }

    @Test
    fun `volume ignores timed and cardio exercises`() = runTest {
        val bench = addExercise("Жим", ExerciseType.STRENGTH)
        val plank = addExercise("Планка", ExerciseType.TIMED)
        val run = addExercise("Дорожка", ExerciseType.CARDIO)
        addWorkout("w", finishedAt = null)
        val strengthWe = addWorkoutExercise("w", bench, position = 0)
        addSet(strengthWe, setIndex = 0, weightKg = 40.0, reps = 10, isCompleted = true)
        val timedWe = addWorkoutExercise("w", plank, position = 1)
        addSet(timedWe, setIndex = 0, weightKg = 999.0, reps = 999, isCompleted = true)
        val cardioWe = addWorkoutExercise("w", run, position = 2)
        addSet(cardioWe, setIndex = 0, weightKg = 999.0, reps = 999, isCompleted = true)

        assertEquals(40.0 * 10, useCase.volume(full("w")), 0.0)
    }

    @Test
    fun `volume tolerates completed strength sets with null weight`() = runTest {
        val bench = addExercise("Жим", ExerciseType.STRENGTH)
        addWorkout("w", finishedAt = null)
        val we = addWorkoutExercise("w", bench, position = 0)
        addSet(we, setIndex = 0, weightKg = null, reps = 10, isCompleted = true) // no weight -> skipped
        addSet(we, setIndex = 1, weightKg = 55.0, reps = 6, isCompleted = true)

        assertEquals(55.0 * 6, useCase.volume(full("w")), 0.0)
    }

    // endregion

    // region newPrs

    @Test
    fun `newPrs reports a pr when the current max beats history`() = runTest {
        val bench = addExercise("Жим", ExerciseType.STRENGTH)
        addWorkout("history", finishedAt = 2_000)
        val historyWe = addWorkoutExercise("history", bench, position = 0)
        addSet(historyWe, setIndex = 0, weightKg = 100.0, reps = 5, isCompleted = true)
        addWorkout("current", finishedAt = null)
        val currentWe = addWorkoutExercise("current", bench, position = 0)
        addSet(currentWe, setIndex = 0, weightKg = 110.0, reps = 3, isCompleted = true)

        val prs = useCase.newPrs(full("current"))

        assertEquals(1, prs.size)
        val pr = prs.single()
        assertEquals(bench, pr.exerciseId)
        assertEquals("Жим", pr.exerciseName)
        assertEquals(110.0, pr.weightKg, 0.0)
    }

    @Test
    fun `newPrs reports no pr when the current max equals history`() = runTest {
        val bench = addExercise("Жим", ExerciseType.STRENGTH)
        addWorkout("history", finishedAt = 2_000)
        val historyWe = addWorkoutExercise("history", bench, position = 0)
        addSet(historyWe, setIndex = 0, weightKg = 100.0, reps = 5, isCompleted = true)
        addWorkout("current", finishedAt = null)
        val currentWe = addWorkoutExercise("current", bench, position = 0)
        addSet(currentWe, setIndex = 0, weightKg = 100.0, reps = 5, isCompleted = true)

        assertTrue(useCase.newPrs(full("current")).isEmpty())
    }

    @Test
    fun `newPrs reports a pr when there is no history and the weight is positive`() = runTest {
        val bench = addExercise("Жим", ExerciseType.STRENGTH)
        addWorkout("current", finishedAt = null)
        val currentWe = addWorkoutExercise("current", bench, position = 0)
        addSet(currentWe, setIndex = 0, weightKg = 80.0, reps = 8, isCompleted = true)

        val prs = useCase.newPrs(full("current"))

        assertEquals(1, prs.size)
        assertEquals(80.0, prs.single().weightKg, 0.0)
    }

    @Test
    fun `newPrs excludes the current workout even after it is finished`() = runTest {
        val bench = addExercise("Жим", ExerciseType.STRENGTH)
        addWorkout("current", finishedAt = 5_000) // already finished, but must not count as its own history
        val currentWe = addWorkoutExercise("current", bench, position = 0)
        addSet(currentWe, setIndex = 0, weightKg = 90.0, reps = 5, isCompleted = true)

        val prs = useCase.newPrs(full("current"))

        assertEquals(1, prs.size)
        assertEquals(90.0, prs.single().weightKg, 0.0)
    }

    // endregion

    // region helpers

    private suspend fun addExercise(name: String, type: ExerciseType): Long =
        exerciseDao.insert(ExerciseEntity(name = name, muscleGroup = MuscleGroup.CHEST, type = type))

    private suspend fun addWorkout(id: String, finishedAt: Long?) {
        workoutDao.insertWorkout(
            WorkoutEntity(id = id, name = "Workout $id", startedAt = 1_000, finishedAt = finishedAt),
        )
    }

    private suspend fun addWorkoutExercise(workoutId: String, exerciseId: Long, position: Int): Long =
        workoutDao.insertWorkoutExercise(
            WorkoutExerciseEntity(workoutId = workoutId, exerciseId = exerciseId, position = position),
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

    private suspend fun full(workoutId: String): WorkoutFull = workoutDao.getWorkoutFull(workoutId)!!

    // endregion
}
