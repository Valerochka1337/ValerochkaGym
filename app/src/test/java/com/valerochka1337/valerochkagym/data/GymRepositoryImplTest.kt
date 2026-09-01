package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.ConfigurationTombstoneKind
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.domain.DeleteGymResult
import com.valerochka1337.valerochkagym.domain.NewExerciseConfiguration
import com.valerochka1337.valerochkagym.domain.RoutineConfigurationDraft
import com.valerochka1337.valerochkagym.domain.SaveGymResult
import com.valerochka1337.valerochkagym.domain.SaveRoutineConfigurationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GymRepositoryImplTest : RoomDaoTest() {

    private lateinit var repository: GymRepositoryImpl

    @Before
    fun createRepository() {
        repository = GymRepositoryImpl(
            database = db,
            gymDao = db.gymDao(),
            exerciseDao = db.exerciseDao(),
            exerciseMuscleDao = db.exerciseMuscleDao(),
            routineDao = db.routineDao(),
            workoutDao = db.workoutDao(),
        )
    }

    @Test
    fun `creating an exercise assigns it to every selected gym atomically`() = runTest {
        val alpha = savedGym("Альфа")
        val beta = savedGym("Бета")

        val exercise = repository.createExerciseAndAssign(
            NewExerciseConfiguration(
                exercise = exercise("Жим"),
                muscles = listOf(ExerciseMuscleEntity(0, Muscle.CHEST, 100)),
            ),
            setOf(alpha, beta),
        )!!

        assertTrue(exercise.id > 0)
        assertEquals(listOf(exercise.id), repository.getGym(alpha)!!.exercises.map { it.id })
        assertEquals(listOf(exercise.id), repository.getGym(beta)!!.exercises.map { it.id })
        assertEquals(listOf(exercise.id), repository.observeAvailableExercises(setOf(alpha, beta)).first().map { it.id })
        assertEquals(1, db.exerciseMuscleDao().getForExercise(exercise.id).size)
    }

    @Test
    fun `creating from the active picker also adds the exercise to its workout`() = runTest {
        val gym = savedGym("Альфа")
        db.workoutDao().insertWorkout(
            WorkoutEntity(id = "active", name = "Тренировка", startedAt = 1_000),
        )
        val localGymId = db.gymDao().getGymBySyncId(gym)!!.id
        db.gymDao().replaceWorkoutGyms("active", listOf(localGymId))

        val exercise = repository.createExerciseAssignAndAddToWorkout(
            NewExerciseConfiguration(
                exercise = exercise("Жим"),
                muscles = listOf(ExerciseMuscleEntity(0, Muscle.CHEST, 100)),
            ),
            setOf(gym),
            "active",
        )!!

        assertEquals(listOf(exercise.id), db.workoutDao().getWorkoutExercises("active").map { it.exerciseId })
        assertEquals(listOf(exercise.id), repository.getGym(gym)!!.exercises.map { it.id })
        assertEquals(1, tableCount("workout_sets"))
    }

    @Test
    fun `routine save and gym narrowing both reject unavailable exercises`() = runTest {
        val exerciseId = db.exerciseDao().insert(exercise("Присед"))
        val alpha = savedGym("Альфа", setOf(exerciseId))
        val beta = savedGym("Бета", setOf(exerciseId))
        val saved = repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine = RoutineEntity(name = "Ноги"),
                exercises = listOf(
                    RoutineExerciseEntity(routineId = 0, exerciseId = exerciseId, position = 0),
                ),
                gymIds = setOf(alpha, beta),
            ),
        )

        assertTrue(saved is SaveRoutineConfigurationResult.Saved)
        val narrowing = repository.saveGym(beta, "Бета", emptySet())
        assertTrue(narrowing is SaveGymResult.Conflict)
        narrowing as SaveGymResult.Conflict
        assertEquals(listOf("Ноги"), narrowing.details.routines.map { it.name })
        assertEquals(listOf("Присед"), narrowing.details.exercises.map { it.name })
        assertEquals(setOf(exerciseId), repository.getGym(beta)!!.exercises.mapTo(hashSetOf()) { it.id })
    }

    @Test
    fun `deleting a gym linked to a routine is blocked`() = runTest {
        val exerciseId = db.exerciseDao().insert(exercise("Тяга"))
        val gym = savedGym("Альфа", setOf(exerciseId))
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine = RoutineEntity(name = "Спина"),
                exercises = listOf(
                    RoutineExerciseEntity(routineId = 0, exerciseId = exerciseId, position = 0),
                ),
                gymIds = setOf(gym),
            ),
        )

        val result = repository.deleteGym(gym)

        assertTrue(result is DeleteGymResult.InUse)
        assertTrue(repository.getGym(gym) != null)
    }

    @Test
    fun `renaming a linked gym without narrowing its catalogue succeeds`() = runTest {
        val exerciseId = db.exerciseDao().insert(exercise("Тяга"))
        val gym = savedGym("Альфа", setOf(exerciseId))
        repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine = RoutineEntity(name = "Спина"),
                exercises = listOf(
                    RoutineExerciseEntity(routineId = 0, exerciseId = exerciseId, position = 0),
                ),
                gymIds = setOf(gym),
            ),
        )

        val result = repository.saveGym(gym, "Основной зал", setOf(exerciseId))

        assertTrue(result is SaveGymResult.Saved)
        assertEquals("Основной зал", repository.getGym(gym)?.name)
    }

    @Test
    fun `updating an exercise keeps its cloud version strictly monotonic`() = runTest {
        val futureVersion = System.currentTimeMillis() + 60_000
        val exerciseId = db.exerciseDao().insert(exercise("Тяга").copy(updatedAt = futureVersion))
        val staleDraft = db.exerciseDao().getById(exerciseId)!!.copy(
            name = "Тяга блока",
            updatedAt = 1,
        )

        val saved = repository.updateExerciseAndAssign(
            NewExerciseConfiguration(
                exercise = staleDraft,
                muscles = listOf(ExerciseMuscleEntity(exerciseId, Muscle.LATS, 100)),
            ),
            emptySet(),
        )!!

        assertEquals(futureVersion + 1, saved.updatedAt)
        assertEquals(futureVersion + 1, db.exerciseDao().getById(exerciseId)?.updatedAt)
    }

    @Test
    fun `duplicating a routine copies exercises and gyms atomically`() = runTest {
        val exerciseId = db.exerciseDao().insert(exercise("Тяга"))
        val gym = savedGym("Альфа", setOf(exerciseId))
        val source = repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine = RoutineEntity(name = "Спина", note = "Тяжёлый день"),
                exercises = listOf(
                    RoutineExerciseEntity(routineId = 0, exerciseId = exerciseId, position = 0),
                ),
                gymIds = setOf(gym),
            ),
        ) as SaveRoutineConfigurationResult.Saved

        val copy = repository.duplicateRoutine(source.routineId)!!
        val full = db.routineDao().getRoutineWithExercises(copy.id)!!

        assertEquals("Спина (копия)", full.routine.name)
        assertEquals("Тяжёлый день", full.routine.note)
        assertEquals(listOf(exerciseId), full.exercises.map { it.exercise.id })
        assertEquals(listOf(gym), full.gyms.map { it.syncId })
    }

    @Test
    fun `deleting configuration keeps durable tombstones until upload succeeds`() = runTest {
        val gym = savedGym("Временный")
        val routine = repository.saveRoutineConfiguration(
            RoutineConfigurationDraft(
                routine = RoutineEntity(name = "Пустая"),
                exercises = emptyList(),
                gymIds = emptySet(),
            ),
        ) as SaveRoutineConfigurationResult.Saved

        assertEquals(DeleteGymResult.Deleted, repository.deleteGym(gym))
        val routineDeletion = repository.deleteRoutine(routine.routineId)!!

        assertEquals(
            listOf(gym),
            db.configurationTombstoneDao().getByKind(ConfigurationTombstoneKind.GYM)
                .map { it.syncId },
        )
        assertEquals(
            listOf(routineDeletion.syncId),
            db.configurationTombstoneDao().getByKind(ConfigurationTombstoneKind.ROUTINE)
                .map { it.syncId },
        )
    }

    private suspend fun savedGym(name: String, exerciseIds: Set<Long> = emptySet()): String =
        (repository.saveGym(null, name, exerciseIds) as SaveGymResult.Saved).gymId

    private fun exercise(name: String) = ExerciseEntity(
        name = name,
        muscleGroup = MuscleGroup.FULL_BODY,
        type = ExerciseType.STRENGTH,
        isCustom = true,
    )
}
