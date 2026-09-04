package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.GymDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GymDaoTest : RoomDaoTest() {

  private lateinit var gymDao: GymDao
  private lateinit var exerciseDao: ExerciseDao
  private lateinit var routineDao: RoutineDao
  private lateinit var workoutDao: WorkoutDao

  @Before
  fun grabDaos() {
    gymDao = db.gymDao()
    exerciseDao = db.exerciseDao()
    routineDao = db.routineDao()
    workoutDao = db.workoutDao()
  }

  @Test
  fun `gym CRUD and relation expose the configured exercise inventory`() = runTest {
    val press = addExercise("Жим")
    val squat = addExercise("Присед")
    val gymId = gymDao.insertGym(GymEntity(syncId = "gym-a", updatedAt = 10, name = "Альфа"))

    gymDao.replaceGymExercises(gymId, listOf(press, squat, press))

    val stored = gymDao.getGymWithExercises(gymId)!!
    assertEquals("Альфа", stored.gym.name)
    assertEquals(setOf("Жим", "Присед"), stored.exercises.map { it.name }.toSet())
    assertEquals(listOf(press, squat).sorted(), gymDao.getGymExerciseIds(gymId))
    assertEquals(stored.gym, gymDao.getGymBySyncId("gym-a"))
    assertEquals(stored.gym, gymDao.observeGyms().first().single())
    assertEquals(2, gymDao.observeGymExerciseIds(listOf(gymId)).first().size)

    gymDao.updateGym(stored.gym.copy(name = "Бета", updatedAt = 11))
    assertEquals("Бета", gymDao.observeGymWithExercises(gymId).first()!!.gym.name)

    gymDao.replaceGymExercises(gymId, emptyList())
    assertEquals(emptyList<Long>(), gymDao.getGymExerciseIds(gymId))
    assertEquals(1, gymDao.deleteGym(gymId))
    assertNull(gymDao.getGym(gymId))
  }

  @Test
  fun `available exercises are the intersection of every selected gym`() = runTest {
    val press = addExercise("Жим")
    val squat = addExercise("Присед")
    val row = addExercise("Тяга")
    val alpha = gymDao.insertGym(GymEntity(name = "Альфа"))
    val beta = gymDao.insertGym(GymEntity(name = "Бета"))
    val gamma = gymDao.insertGym(GymEntity(name = "Гамма"))
    gymDao.replaceGymExercises(alpha, listOf(press, squat, row))
    gymDao.replaceGymExercises(beta, listOf(squat, row))
    gymDao.replaceGymExercises(gamma, listOf(row))

    assertEquals(
        listOf("Присед", "Тяга"),
        gymDao.getAvailableExercises(listOf(alpha, beta), gymCount = 2).map { it.name },
    )
    assertEquals(
        listOf("Тяга"),
        gymDao.getAvailableExercises(listOf(alpha, beta, gamma), gymCount = 3).map { it.name },
    )
    assertEquals(
        listOf("Жим", "Присед", "Тяга"),
        gymDao.getAvailableExercises(listOf(alpha), gymCount = 1).map { it.name },
    )
  }

  @Test
  fun `routine and workout relations expose gyms and diagnostics only report active workouts`() =
      runTest {
        val alpha = gymDao.insertGym(GymEntity(name = "Альфа"))
        val beta = gymDao.insertGym(GymEntity(name = "Бета"))
        val routineId = routineDao.upsertRoutine(RoutineEntity(name = "Верх"))
        workoutDao.insertWorkout(WorkoutEntity(id = "active", name = "Верх", startedAt = 1_000))
        workoutDao.insertWorkout(
            WorkoutEntity(id = "finished", name = "Верх", startedAt = 500, finishedAt = 900),
        )

        gymDao.replaceRoutineGyms(routineId, listOf(alpha, beta, alpha))
        gymDao.replaceWorkoutGyms("active", listOf(alpha, beta))
        gymDao.replaceWorkoutGyms("finished", listOf(alpha))

        assertEquals(setOf(alpha, beta), gymDao.getGymsForRoutine(routineId).map { it.id }.toSet())
        assertEquals(
            setOf(alpha, beta),
            routineDao.getRoutineWithExercises(routineId)!!.gyms.map { it.id }.toSet(),
        )
        assertEquals(setOf(alpha, beta), gymDao.getGymsForWorkout("active").map { it.id }.toSet())
        assertEquals(
            setOf(alpha, beta),
            workoutDao.getWorkoutFull("active")!!.gyms.map { it.id }.toSet(),
        )
        assertEquals(listOf("Верх"), gymDao.getLinkedRoutines(alpha).map { it.name })
        assertEquals(listOf("active"), gymDao.getLinkedActiveWorkouts(alpha).map { it.id })

        gymDao.replaceRoutineGyms(routineId, listOf(beta))
        gymDao.replaceWorkoutGyms("active", emptyList())
        assertEquals(emptyList<RoutineEntity>(), gymDao.getLinkedRoutines(alpha))
        assertEquals(emptyList<WorkoutEntity>(), gymDao.getLinkedActiveWorkouts(alpha))
      }

  private suspend fun addExercise(name: String): Long =
      exerciseDao.insert(
          ExerciseEntity(
              name = name,
              muscleGroup = MuscleGroup.FULL_BODY,
              type = ExerciseType.STRENGTH,
          ),
      )
}
