package com.valerochka1337.valerochkagym.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class WorkoutDaoTest {

    private lateinit var db: GymDatabase
    private lateinit var workoutDao: WorkoutDao
    private lateinit var exerciseDao: ExerciseDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        workoutDao = db.workoutDao()
        exerciseDao = db.exerciseDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // region lastCompletedSetsForExercise

    @Test
    fun `lastCompletedSetsForExercise returns completed sets of the latest finished workout ordered by index`() =
        runTest {
            val exerciseId = addExercise()

            addWorkout("old", startedAt = 1_000, finishedAt = 2_000)
            val oldWe = addWorkoutExercise("old", exerciseId)
            addSet(oldWe, setIndex = 0, weightKg = 40.0, isCompleted = true)

            addWorkout("new", startedAt = 3_000, finishedAt = 4_000)
            val newWe = addWorkoutExercise("new", exerciseId)
            // Inserted out of index order to prove the query sorts by setIndex.
            addSet(newWe, setIndex = 2, weightKg = 60.0, isCompleted = true)
            addSet(newWe, setIndex = 0, weightKg = 50.0, isCompleted = true)
            addSet(newWe, setIndex = 1, weightKg = 55.0, isCompleted = true)

            val result = workoutDao.lastCompletedSetsForExercise(exerciseId)

            assertEquals(listOf(0, 1, 2), result.map { it.setIndex })
            assertEquals(listOf(50.0, 55.0, 60.0), result.map { it.weightKg })
            assertTrue(result.all { it.workoutExerciseId == newWe })
        }

    @Test
    fun `lastCompletedSetsForExercise ignores unfinished workouts`() = runTest {
        val exerciseId = addExercise()

        addWorkout("finished", startedAt = 1_000, finishedAt = 2_000)
        val finishedWe = addWorkoutExercise("finished", exerciseId)
        addSet(finishedWe, setIndex = 0, weightKg = 42.0, isCompleted = true)

        // Started later but never finished, so it must not shadow the finished one.
        addWorkout("active", startedAt = 5_000, finishedAt = null)
        val activeWe = addWorkoutExercise("active", exerciseId)
        addSet(activeWe, setIndex = 0, weightKg = 99.0, isCompleted = true)

        val result = workoutDao.lastCompletedSetsForExercise(exerciseId)

        assertEquals(1, result.size)
        assertEquals(42.0, result.single().weightKg!!, 0.0)
    }

    @Test
    fun `lastCompletedSetsForExercise falls back to the previous workout that has completed sets`() =
        runTest {
            val exerciseId = addExercise()

            addWorkout("previous", startedAt = 1_000, finishedAt = 2_000)
            val previousWe = addWorkoutExercise("previous", exerciseId)
            addSet(previousWe, setIndex = 0, weightKg = 30.0, isCompleted = true)
            addSet(previousWe, setIndex = 1, weightKg = 35.0, isCompleted = true)

            // Latest finished workout contains the exercise but nothing was completed.
            addWorkout("latest", startedAt = 3_000, finishedAt = 4_000)
            val latestWe = addWorkoutExercise("latest", exerciseId)
            addSet(latestWe, setIndex = 0, weightKg = 70.0, isCompleted = false)

            val result = workoutDao.lastCompletedSetsForExercise(exerciseId)

            assertEquals(listOf(30.0, 35.0), result.map { it.weightKg })
            assertTrue(result.all { it.workoutExerciseId == previousWe })
        }

    @Test
    fun `lastCompletedSetsForExercise returns empty when there is no history`() = runTest {
        val exerciseId = addExercise()

        assertTrue(workoutDao.lastCompletedSetsForExercise(exerciseId).isEmpty())
    }

    // endregion

    // region maxCompletedWeight

    @Test
    fun `maxCompletedWeight takes the max over finished workouts and completed sets`() = runTest {
        val exerciseId = addExercise()

        addWorkout("w1", startedAt = 1_000, finishedAt = 2_000)
        val we1 = addWorkoutExercise("w1", exerciseId)
        addSet(we1, setIndex = 0, weightKg = 50.0, isCompleted = true)
        addSet(we1, setIndex = 1, weightKg = 80.0, isCompleted = true)
        addSet(we1, setIndex = 2, weightKg = 500.0, isCompleted = false) // not completed -> ignored

        addWorkout("w2", startedAt = 3_000, finishedAt = 4_000)
        val we2 = addWorkoutExercise("w2", exerciseId)
        addSet(we2, setIndex = 0, weightKg = 100.0, isCompleted = true)

        addWorkout("active", startedAt = 5_000, finishedAt = null)
        val activeWe = addWorkoutExercise("active", exerciseId)
        addSet(activeWe, setIndex = 0, weightKg = 200.0, isCompleted = true) // not finished -> ignored

        assertEquals(100.0, workoutDao.maxCompletedWeight(exerciseId, "no-such-workout")!!, 0.0)
    }

    @Test
    fun `maxCompletedWeight excludes the given workout`() = runTest {
        val exerciseId = addExercise()

        addWorkout("w1", startedAt = 1_000, finishedAt = 2_000)
        val we1 = addWorkoutExercise("w1", exerciseId)
        addSet(we1, setIndex = 0, weightKg = 80.0, isCompleted = true)

        addWorkout("w2", startedAt = 3_000, finishedAt = 4_000)
        val we2 = addWorkoutExercise("w2", exerciseId)
        addSet(we2, setIndex = 0, weightKg = 100.0, isCompleted = true)

        assertEquals(80.0, workoutDao.maxCompletedWeight(exerciseId, "w2")!!, 0.0)
    }

    @Test
    fun `maxCompletedWeight is null when there is no data`() = runTest {
        val exerciseId = addExercise()

        assertNull(workoutDao.maxCompletedWeight(exerciseId, "no-such-workout"))
    }

    @Test
    fun `maxCompletedWeight is null when completed sets carry no weight`() = runTest {
        val exerciseId = addExercise()

        addWorkout("w1", startedAt = 1_000, finishedAt = 2_000)
        val we1 = addWorkoutExercise("w1", exerciseId)
        addSet(we1, setIndex = 0, weightKg = null, isCompleted = true)

        assertNull(workoutDao.maxCompletedWeight(exerciseId, "no-such-workout"))
    }

    // endregion

    // region active workout

    @Test
    fun `getActiveWorkoutId is null when nothing is active`() = runTest {
        addWorkout("finished", startedAt = 1_000, finishedAt = 2_000)

        assertNull(workoutDao.getActiveWorkoutId())
    }

    @Test
    fun `active workout is the one without finishedAt`() = runTest {
        addWorkout("finished", startedAt = 1_000, finishedAt = 2_000)
        addWorkout("active", startedAt = 3_000, finishedAt = null)

        assertEquals("active", workoutDao.getActiveWorkoutId())
        assertEquals("active", workoutDao.observeActiveWorkout().first()?.workout?.id)
    }

    @Test
    fun `with several active workouts the most recent by startedAt wins`() = runTest {
        addWorkout("active-old", startedAt = 3_000, finishedAt = null)
        addWorkout("active-new", startedAt = 9_000, finishedAt = null)

        assertEquals("active-new", workoutDao.getActiveWorkoutId())
        assertEquals("active-new", workoutDao.observeActiveWorkout().first()?.workout?.id)
    }

    @Test
    fun `observeActiveWorkout emits null when nothing is active`() = runTest {
        assertNull(workoutDao.observeActiveWorkout().first())
    }

    // endregion

    // region cascades

    @Test
    fun `deleting a workout cascades to its exercises and sets`() = runTest {
        val exerciseId = addExercise()
        addWorkout("w", startedAt = 1_000, finishedAt = 2_000)
        val we = addWorkoutExercise("w", exerciseId)
        addSet(we, setIndex = 0, weightKg = 50.0, isCompleted = true)
        addSet(we, setIndex = 1, weightKg = 60.0, isCompleted = true)

        assertEquals(1, tableCount("workout_exercises"))
        assertEquals(2, tableCount("workout_sets"))

        workoutDao.deleteWorkout("w")

        assertEquals(0, tableCount("workout_exercises"))
        assertEquals(0, tableCount("workout_sets"))
    }

    // endregion

    private suspend fun addExercise(name: String = "Жим штанги лёжа"): Long =
        exerciseDao.insert(
            ExerciseEntity(
                name = name,
                muscleGroup = MuscleGroup.CHEST,
                type = ExerciseType.STRENGTH,
            ),
        )

    private suspend fun addWorkout(id: String, startedAt: Long, finishedAt: Long?) {
        workoutDao.insertWorkout(
            WorkoutEntity(
                id = id,
                name = "Workout $id",
                startedAt = startedAt,
                finishedAt = finishedAt,
            ),
        )
    }

    private suspend fun addWorkoutExercise(workoutId: String, exerciseId: Long, position: Int = 0): Long =
        workoutDao.insertWorkoutExercise(
            WorkoutExerciseEntity(workoutId = workoutId, exerciseId = exerciseId, position = position),
        )

    private suspend fun addSet(
        workoutExerciseId: Long,
        setIndex: Int,
        weightKg: Double?,
        isCompleted: Boolean,
    ): Long =
        workoutDao.insertSet(
            WorkoutSetEntity(
                workoutExerciseId = workoutExerciseId,
                setIndex = setIndex,
                weightKg = weightKg,
                reps = 10,
                isCompleted = isCompleted,
            ),
        )

    private fun tableCount(table: String): Int =
        db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM $table")).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
