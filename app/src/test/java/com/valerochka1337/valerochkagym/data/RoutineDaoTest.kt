package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RoutineDaoTest : RoomDaoTest() {

    private lateinit var routineDao: RoutineDao
    private lateinit var workoutDao: WorkoutDao
    private lateinit var exerciseDao: ExerciseDao

    @Before
    fun grabDaos() {
        routineDao = db.routineDao()
        workoutDao = db.workoutDao()
        exerciseDao = db.exerciseDao()
    }

    @Test
    fun `deleting a routine removes its exercises but keeps workouts with a null routineId`() =
        runTest {
            val routineId = routineDao.upsertRoutine(RoutineEntity(name = "День ног"))
            val exerciseId = addExercise()
            routineDao.insertRoutineExercises(
                listOf(RoutineExerciseEntity(routineId = routineId, exerciseId = exerciseId, position = 0)),
            )
            workoutDao.insertWorkout(
                WorkoutEntity(
                    id = "w",
                    routineId = routineId,
                    name = "День ног",
                    startedAt = 1_000,
                    finishedAt = 2_000,
                ),
            )

            assertEquals(1, tableCount("routine_exercises"))

            routineDao.deleteRoutine(routineId)

            assertEquals(0, tableCount("routine_exercises"))
            val workout = workoutDao.getWorkoutFull("w")
            assertNotNull(workout)
            assertNull(workout!!.workout.routineId)
            assertEquals("День ног", workout.workout.name)
        }

    @Test
    fun `replaceRoutineExercises swaps the old rows for the new ones`() = runTest {
        val routineId = routineDao.upsertRoutine(RoutineEntity(name = "Сплит"))
        val a = addExercise("A")
        val b = addExercise("B")
        val c = addExercise("C")

        routineDao.insertRoutineExercises(
            listOf(
                RoutineExerciseEntity(routineId = routineId, exerciseId = a, position = 0),
                RoutineExerciseEntity(routineId = routineId, exerciseId = b, position = 1),
            ),
        )

        routineDao.replaceRoutineExercises(
            routineId,
            listOf(
                RoutineExerciseEntity(routineId = routineId, exerciseId = b, position = 0),
                RoutineExerciseEntity(routineId = routineId, exerciseId = c, position = 1),
            ),
        )

        assertEquals(2, tableCount("routine_exercises"))
        val stored = routineDao.getRoutineWithExercises(routineId)!!
            .exercises
            .sortedBy { it.routineExercise.position }
            .map { it.routineExercise.exerciseId }
        assertEquals(listOf(b, c), stored)
    }

    private suspend fun addExercise(name: String = "Приседания со штангой"): Long =
        exerciseDao.insert(
            ExerciseEntity(
                name = name,
                muscleGroup = MuscleGroup.LEGS,
                type = ExerciseType.STRENGTH,
            ),
        )
}
