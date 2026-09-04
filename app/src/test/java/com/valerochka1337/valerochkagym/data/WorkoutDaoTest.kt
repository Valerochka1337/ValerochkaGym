package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkoutDaoTest : RoomDaoTest() {

  private lateinit var workoutDao: WorkoutDao
  private lateinit var exerciseDao: ExerciseDao

  @Before
  fun grabDaos() {
    workoutDao = db.workoutDao()
    exerciseDao = db.exerciseDao()
  }

  @Test
  fun `finished exercise history has one row per completed exercise workout and excludes active`() =
      runTest {
        val exerciseId = addExercise()
        insertWorkout("finished", startedAt = 1_000, finishedAt = 9_000)
        val row = insertWorkoutExercise("finished", exerciseId)
        insertSet(row, setIndex = 0, isCompleted = true)
        insertSet(row, setIndex = 1, isCompleted = true)
        insertWorkout("active", startedAt = 10_000, finishedAt = null)
        val active = insertWorkoutExercise("active", exerciseId)
        insertSet(active, setIndex = 0, isCompleted = true)

        val history = workoutDao.observeFinishedExerciseHistory().first()

        assertEquals(1, history.size)
        assertEquals(exerciseId, history.single().exerciseId)
        assertEquals("finished", history.single().workoutId)
        assertEquals(9_000L, history.single().finishedAt)
      }

  // region lastCompletedSetsForExercise

  @Test
  fun `lastCompletedSetsForExercise returns completed sets of the latest finished workout ordered by index`() =
      runTest {
        val exerciseId = addExercise()

        insertWorkout("old", startedAt = 1_000, finishedAt = 2_000)
        val oldWe = insertWorkoutExercise("old", exerciseId)
        insertSet(oldWe, setIndex = 0, weightKg = 40.0, isCompleted = true)

        insertWorkout("new", startedAt = 3_000, finishedAt = 4_000)
        val newWe = insertWorkoutExercise("new", exerciseId)
        // Inserted out of index order to prove the query sorts by setIndex.
        insertSet(newWe, setIndex = 2, weightKg = 60.0, isCompleted = true)
        insertSet(newWe, setIndex = 0, weightKg = 50.0, isCompleted = true)
        insertSet(newWe, setIndex = 1, weightKg = 55.0, isCompleted = true)

        val result = workoutDao.lastCompletedSetsForExercise(exerciseId)

        assertEquals(listOf(0, 1, 2), result.map { it.setIndex })
        assertEquals(listOf(50.0, 55.0, 60.0), result.map { it.weightKg })
        assertTrue(result.all { it.workoutExerciseId == newWe })
      }

  @Test
  fun `lastCompletedSetsForExercise ignores unfinished workouts`() = runTest {
    val exerciseId = addExercise()

    insertWorkout("finished", startedAt = 1_000, finishedAt = 2_000)
    val finishedWe = insertWorkoutExercise("finished", exerciseId)
    insertSet(finishedWe, setIndex = 0, weightKg = 42.0, isCompleted = true)

    // Started later but never finished, so it must not shadow the finished one.
    insertWorkout("active", startedAt = 5_000, finishedAt = null)
    val activeWe = insertWorkoutExercise("active", exerciseId)
    insertSet(activeWe, setIndex = 0, weightKg = 99.0, isCompleted = true)

    val result = workoutDao.lastCompletedSetsForExercise(exerciseId)

    assertEquals(1, result.size)
    assertEquals(42.0, result.single().weightKg!!, 0.0)
  }

  @Test
  fun `lastCompletedSetsForExercise falls back to the previous workout that has completed sets`() =
      runTest {
        val exerciseId = addExercise()

        insertWorkout("previous", startedAt = 1_000, finishedAt = 2_000)
        val previousWe = insertWorkoutExercise("previous", exerciseId)
        insertSet(previousWe, setIndex = 0, weightKg = 30.0, isCompleted = true)
        insertSet(previousWe, setIndex = 1, weightKg = 35.0, isCompleted = true)

        // Latest finished workout contains the exercise but nothing was completed.
        insertWorkout("latest", startedAt = 3_000, finishedAt = 4_000)
        val latestWe = insertWorkoutExercise("latest", exerciseId)
        insertSet(latestWe, setIndex = 0, weightKg = 70.0, isCompleted = false)

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

    insertWorkout("w1", startedAt = 1_000, finishedAt = 2_000)
    val we1 = insertWorkoutExercise("w1", exerciseId)
    insertSet(we1, setIndex = 0, weightKg = 50.0, isCompleted = true)
    insertSet(we1, setIndex = 1, weightKg = 80.0, isCompleted = true)
    insertSet(we1, setIndex = 2, weightKg = 500.0, isCompleted = false) // not completed -> ignored

    insertWorkout("w2", startedAt = 3_000, finishedAt = 4_000)
    val we2 = insertWorkoutExercise("w2", exerciseId)
    insertSet(we2, setIndex = 0, weightKg = 100.0, isCompleted = true)

    insertWorkout("active", startedAt = 5_000, finishedAt = null)
    val activeWe = insertWorkoutExercise("active", exerciseId)
    insertSet(
        activeWe,
        setIndex = 0,
        weightKg = 200.0,
        isCompleted = true,
    ) // not finished -> ignored

    assertEquals(100.0, workoutDao.maxCompletedWeight(exerciseId, "no-such-workout")!!, 0.0)
  }

  @Test
  fun `maxCompletedWeight excludes the given workout`() = runTest {
    val exerciseId = addExercise()

    insertWorkout("w1", startedAt = 1_000, finishedAt = 2_000)
    val we1 = insertWorkoutExercise("w1", exerciseId)
    insertSet(we1, setIndex = 0, weightKg = 80.0, isCompleted = true)

    insertWorkout("w2", startedAt = 3_000, finishedAt = 4_000)
    val we2 = insertWorkoutExercise("w2", exerciseId)
    insertSet(we2, setIndex = 0, weightKg = 100.0, isCompleted = true)

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

    insertWorkout("w1", startedAt = 1_000, finishedAt = 2_000)
    val we1 = insertWorkoutExercise("w1", exerciseId)
    insertSet(we1, setIndex = 0, weightKg = null, isCompleted = true)

    assertNull(workoutDao.maxCompletedWeight(exerciseId, "no-such-workout"))
  }

  // endregion

  // region active workout

  @Test
  fun `getActiveWorkoutId is null when nothing is active`() = runTest {
    insertWorkout("finished", startedAt = 1_000, finishedAt = 2_000)

    assertNull(workoutDao.getActiveWorkoutId())
  }

  @Test
  fun `active workout is the one without finishedAt`() = runTest {
    insertWorkout("finished", startedAt = 1_000, finishedAt = 2_000)
    insertWorkout("active", startedAt = 3_000, finishedAt = null)

    assertEquals("active", workoutDao.getActiveWorkoutId())
    assertEquals("active", workoutDao.observeActiveWorkout().first()?.workout?.id)
  }

  @Test
  fun `with several active workouts the most recent by startedAt wins`() = runTest {
    insertWorkout("active-old", startedAt = 3_000, finishedAt = null)
    insertWorkout("active-new", startedAt = 9_000, finishedAt = null)

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
    insertWorkout("w", startedAt = 1_000, finishedAt = 2_000)
    val we = insertWorkoutExercise("w", exerciseId)
    insertSet(we, setIndex = 0, weightKg = 50.0, isCompleted = true)
    insertSet(we, setIndex = 1, weightKg = 60.0, isCompleted = true)

    assertEquals(1, tableCount("workout_exercises"))
    assertEquals(2, tableCount("workout_sets"))

    workoutDao.deleteWorkout("w")

    assertEquals(0, tableCount("workout_exercises"))
    assertEquals(0, tableCount("workout_sets"))
  }

  // endregion

  // region completedAt

  @Test
  fun `setSetCompleted writes completedAt when completing and clears it when uncompleting`() =
      runTest {
        val exerciseId = addExercise()
        insertWorkout("w", startedAt = 1_000, finishedAt = null)
        val we = insertWorkoutExercise("w", exerciseId)
        val setId = insertSet(we, setIndex = 0, weightKg = 50.0, isCompleted = false)

        workoutDao.setSetCompleted(setId, completed = true, completedAt = 12_345L)
        assertEquals(12_345L, workoutDao.getSet(setId)!!.completedAt)

        workoutDao.setSetCompleted(setId, completed = false, completedAt = null)
        assertNull(workoutDao.getSet(setId)!!.completedAt)
      }

  @Test
  fun `getExistingWorkoutIds returns all workout ids`() = runTest {
    insertWorkout("a", startedAt = 1_000, finishedAt = 2_000)
    insertWorkout("b", startedAt = 3_000, finishedAt = null)

    assertEquals(setOf("a", "b"), workoutDao.getExistingWorkoutIds().toSet())
  }

  @Test
  fun `getExistingWorkoutIds is empty without workouts`() = runTest {
    assertTrue(workoutDao.getExistingWorkoutIds().isEmpty())
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
}
