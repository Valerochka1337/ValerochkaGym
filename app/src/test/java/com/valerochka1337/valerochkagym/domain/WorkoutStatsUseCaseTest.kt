package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
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
        insertWorkout("w", finishedAt = null)
        val we = insertWorkoutExercise("w", bench, position = 0)
        insertSet(we, setIndex = 0, weightKg = 50.0, reps = 10, isCompleted = true)
        insertSet(we, setIndex = 1, weightKg = 60.0, reps = 8, isCompleted = true)
        insertSet(we, setIndex = 2, weightKg = 100.0, reps = 5, isCompleted = false) // uncompleted -> ignored

        assertEquals(50.0 * 10 + 60.0 * 8, useCase.volume(workoutFull("w")), 0.0)
    }

    @Test
    fun `volume ignores timed and cardio exercises`() = runTest {
        val bench = addExercise("Жим", ExerciseType.STRENGTH)
        val plank = addExercise("Планка", ExerciseType.TIMED)
        val run = addExercise("Дорожка", ExerciseType.CARDIO)
        insertWorkout("w", finishedAt = null)
        val strengthWe = insertWorkoutExercise("w", bench, position = 0)
        insertSet(strengthWe, setIndex = 0, weightKg = 40.0, reps = 10, isCompleted = true)
        val timedWe = insertWorkoutExercise("w", plank, position = 1)
        insertSet(timedWe, setIndex = 0, weightKg = 999.0, reps = 999, isCompleted = true)
        val cardioWe = insertWorkoutExercise("w", run, position = 2)
        insertSet(cardioWe, setIndex = 0, weightKg = 999.0, reps = 999, isCompleted = true)

        assertEquals(40.0 * 10, useCase.volume(workoutFull("w")), 0.0)
    }

    @Test
    fun `volume tolerates completed strength sets with null weight`() = runTest {
        val bench = addExercise("Жим", ExerciseType.STRENGTH)
        insertWorkout("w", finishedAt = null)
        val we = insertWorkoutExercise("w", bench, position = 0)
        insertSet(we, setIndex = 0, weightKg = null, reps = 10, isCompleted = true) // no weight -> skipped
        insertSet(we, setIndex = 1, weightKg = 55.0, reps = 6, isCompleted = true)

        assertEquals(55.0 * 6, useCase.volume(workoutFull("w")), 0.0)
    }

    // endregion

    // region newPrs

    @Test
    fun `newPrs reports a pr when the current max beats history`() = runTest {
        val bench = addExercise("Жим", ExerciseType.STRENGTH)
        insertWorkout("history", finishedAt = 2_000)
        val historyWe = insertWorkoutExercise("history", bench, position = 0)
        insertSet(historyWe, setIndex = 0, weightKg = 100.0, reps = 5, isCompleted = true)
        insertWorkout("current", finishedAt = null)
        val currentWe = insertWorkoutExercise("current", bench, position = 0)
        insertSet(currentWe, setIndex = 0, weightKg = 110.0, reps = 3, isCompleted = true)

        val prs = useCase.newPrs(workoutFull("current"))

        assertEquals(1, prs.size)
        val pr = prs.single()
        assertEquals(bench, pr.exerciseId)
        assertEquals("Жим", pr.exerciseName)
        assertEquals(110.0, pr.weightKg, 0.0)
    }

    @Test
    fun `newPrs reports no pr when the current max equals history`() = runTest {
        val bench = addExercise("Жим", ExerciseType.STRENGTH)
        insertWorkout("history", finishedAt = 2_000)
        val historyWe = insertWorkoutExercise("history", bench, position = 0)
        insertSet(historyWe, setIndex = 0, weightKg = 100.0, reps = 5, isCompleted = true)
        insertWorkout("current", finishedAt = null)
        val currentWe = insertWorkoutExercise("current", bench, position = 0)
        insertSet(currentWe, setIndex = 0, weightKg = 100.0, reps = 5, isCompleted = true)

        assertTrue(useCase.newPrs(workoutFull("current")).isEmpty())
    }

    @Test
    fun `newPrs reports a pr when there is no history and the weight is positive`() = runTest {
        val bench = addExercise("Жим", ExerciseType.STRENGTH)
        insertWorkout("current", finishedAt = null)
        val currentWe = insertWorkoutExercise("current", bench, position = 0)
        insertSet(currentWe, setIndex = 0, weightKg = 80.0, reps = 8, isCompleted = true)

        val prs = useCase.newPrs(workoutFull("current"))

        assertEquals(1, prs.size)
        assertEquals(80.0, prs.single().weightKg, 0.0)
    }

    @Test
    fun `newPrs excludes the current workout even after it is finished`() = runTest {
        val bench = addExercise("Жим", ExerciseType.STRENGTH)
        insertWorkout("current", finishedAt = 5_000) // already finished, but must not count as its own history
        val currentWe = insertWorkoutExercise("current", bench, position = 0)
        insertSet(currentWe, setIndex = 0, weightKg = 90.0, reps = 5, isCompleted = true)

        val prs = useCase.newPrs(workoutFull("current"))

        assertEquals(1, prs.size)
        assertEquals(90.0, prs.single().weightKg, 0.0)
    }

    // endregion

    // region helpers

    private suspend fun addExercise(name: String, type: ExerciseType): Long =
        exerciseDao.insert(ExerciseEntity(name = name, muscleGroup = MuscleGroup.CHEST, type = type))

    // endregion
}
