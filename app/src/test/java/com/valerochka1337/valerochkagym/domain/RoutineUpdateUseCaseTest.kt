package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RoutineUpdateUseCaseTest : RoomDaoTest() {

    private lateinit var routineDao: RoutineDao
    private lateinit var exerciseDao: ExerciseDao
    private lateinit var useCase: RoutineUpdateUseCase

    @Before
    fun setUp() {
        routineDao = db.routineDao()
        exerciseDao = db.exerciseDao()
        useCase = RoutineUpdateUseCase(routineDao)
    }

    // region hasDiverged

    @Test
    fun `hasDiverged is false when completed sets match plannedSets`() = runTest {
        val squat = addExercise("Присед")
        val routineId = addRoutine("День A")
        addRoutineExercise(routineId, squat.id, position = 0, plannedSets = listOf(PlannedSet(100.0, 5)))
        val workout = workoutFull(
            routineId,
            listOf(exerciseWithSets(squat, position = 0, sets = listOf(completed(0, 100.0, 5)))),
        )

        assertFalse(useCase.hasDiverged(workout))
    }

    @Test
    fun `hasDiverged is true when a set weight changes`() = runTest {
        val squat = addExercise("Присед")
        val routineId = addRoutine("День A")
        addRoutineExercise(routineId, squat.id, position = 0, plannedSets = listOf(PlannedSet(100.0, 5)))
        val workout = workoutFull(
            routineId,
            listOf(exerciseWithSets(squat, position = 0, sets = listOf(completed(0, 105.0, 5)))),
        )

        assertTrue(useCase.hasDiverged(workout))
    }

    @Test
    fun `hasDiverged is true when reps change`() = runTest {
        val squat = addExercise("Присед")
        val routineId = addRoutine("День A")
        addRoutineExercise(routineId, squat.id, position = 0, plannedSets = listOf(PlannedSet(100.0, 5)))
        val workout = workoutFull(
            routineId,
            listOf(exerciseWithSets(squat, position = 0, sets = listOf(completed(0, 100.0, 6)))),
        )

        assertTrue(useCase.hasDiverged(workout))
    }

    @Test
    fun `hasDiverged is true when a set is added`() = runTest {
        val squat = addExercise("Присед")
        val routineId = addRoutine("День A")
        addRoutineExercise(routineId, squat.id, position = 0, plannedSets = listOf(PlannedSet(100.0, 5)))
        val workout = workoutFull(
            routineId,
            listOf(
                exerciseWithSets(
                    squat,
                    position = 0,
                    sets = listOf(completed(0, 100.0, 5), completed(1, 100.0, 5)),
                ),
            ),
        )

        assertTrue(useCase.hasDiverged(workout))
    }

    @Test
    fun `hasDiverged is true when an exercise is added`() = runTest {
        val squat = addExercise("Присед")
        val bench = addExercise("Жим")
        val routineId = addRoutine("День A")
        addRoutineExercise(routineId, squat.id, position = 0, plannedSets = listOf(PlannedSet(100.0, 5)))
        val workout = workoutFull(
            routineId,
            listOf(
                exerciseWithSets(squat, position = 0, sets = listOf(completed(0, 100.0, 5))),
                exerciseWithSets(bench, position = 1, sets = listOf(completed(0, 60.0, 8))),
            ),
        )

        assertTrue(useCase.hasDiverged(workout))
    }

    @Test
    fun `hasDiverged is true when an exercise is removed`() = runTest {
        val squat = addExercise("Присед")
        val bench = addExercise("Жим")
        val routineId = addRoutine("День A")
        addRoutineExercise(routineId, squat.id, position = 0, plannedSets = listOf(PlannedSet(100.0, 5)))
        addRoutineExercise(routineId, bench.id, position = 1, plannedSets = listOf(PlannedSet(60.0, 8)))
        val workout = workoutFull(
            routineId,
            listOf(exerciseWithSets(squat, position = 0, sets = listOf(completed(0, 100.0, 5)))),
        )

        assertTrue(useCase.hasDiverged(workout))
    }

    @Test
    fun `hasDiverged is false when the workout has no routine`() = runTest {
        val squat = addExercise("Присед")
        val workout = workoutFull(
            routineId = null,
            exercises = listOf(exerciseWithSets(squat, position = 0, sets = listOf(completed(0, 100.0, 5)))),
        )

        assertFalse(useCase.hasDiverged(workout))
    }

    @Test
    fun `hasDiverged is false when the routine was deleted`() = runTest {
        val squat = addExercise("Присед")
        // Points at a routine id that has no row, mimicking a routine deleted after the workout ran.
        val workout = workoutFull(
            routineId = 999,
            exercises = listOf(exerciseWithSets(squat, position = 0, sets = listOf(completed(0, 100.0, 5)))),
        )

        assertFalse(useCase.hasDiverged(workout))
    }

    // endregion

    // region applyToRoutine

    @Test
    fun `applyToRoutine overwrites plannedSets with the actual completed sets`() = runTest {
        val squat = addExercise("Присед")
        val routineId = addRoutine("День A")
        addRoutineExercise(routineId, squat.id, position = 0, plannedSets = listOf(PlannedSet(100.0, 5)))
        val workout = workoutFull(
            routineId,
            listOf(
                exerciseWithSets(squat, position = 0, sets = listOf(completed(0, 110.0, 6), completed(1, 112.5, 4))),
            ),
        )

        useCase.applyToRoutine(workout)

        val stored = routineDao.getRoutineWithExercises(routineId)!!.exercises.single()
        assertEquals(listOf(PlannedSet(110.0, 6), PlannedSet(112.5, 4)), stored.routineExercise.plannedSets)
    }

    @Test
    fun `applyToRoutine drops uncompleted sets`() = runTest {
        val squat = addExercise("Присед")
        val routineId = addRoutine("День A")
        addRoutineExercise(routineId, squat.id, position = 0, plannedSets = listOf(PlannedSet(100.0, 5)))
        val workout = workoutFull(
            routineId,
            listOf(
                exerciseWithSets(squat, position = 0, sets = listOf(completed(0, 100.0, 5), uncompleted(1, 999.0, 999))),
            ),
        )

        useCase.applyToRoutine(workout)

        val stored = routineDao.getRoutineWithExercises(routineId)!!.exercises.single()
        assertEquals(listOf(PlannedSet(100.0, 5)), stored.routineExercise.plannedSets)
    }

    @Test
    fun `applyToRoutine drops exercises without completed sets`() = runTest {
        val squat = addExercise("Присед")
        val bench = addExercise("Жим")
        val routineId = addRoutine("День A")
        addRoutineExercise(routineId, squat.id, position = 0, plannedSets = listOf(PlannedSet(100.0, 5)))
        addRoutineExercise(routineId, bench.id, position = 1, plannedSets = listOf(PlannedSet(60.0, 8)))
        val workout = workoutFull(
            routineId,
            listOf(
                exerciseWithSets(squat, position = 0, sets = listOf(completed(0, 100.0, 5))),
                exerciseWithSets(bench, position = 1, sets = listOf(uncompleted(0, 60.0, 8))),
            ),
        )

        useCase.applyToRoutine(workout)

        val stored = routineDao.getRoutineWithExercises(routineId)!!.exercises
        assertEquals(listOf(squat.id), stored.map { it.routineExercise.exerciseId })
    }

    @Test
    fun `applyToRoutine keeps restSeconds for surviving exercises`() = runTest {
        val squat = addExercise("Присед")
        val routineId = addRoutine("День A")
        addRoutineExercise(routineId, squat.id, position = 0, restSeconds = 90, plannedSets = listOf(PlannedSet(100.0, 5)))
        val workout = workoutFull(
            routineId,
            listOf(exerciseWithSets(squat, position = 0, sets = listOf(completed(0, 105.0, 5)))),
        )

        useCase.applyToRoutine(workout)

        val stored = routineDao.getRoutineWithExercises(routineId)!!.exercises.single()
        assertEquals(90, stored.routineExercise.restSeconds)
    }

    @Test
    fun `applyToRoutine reindexes positions of the surviving exercises`() = runTest {
        val squat = addExercise("Присед")
        val bench = addExercise("Жим")
        val row = addExercise("Тяга")
        val routineId = addRoutine("День A")
        addRoutineExercise(routineId, squat.id, position = 0, plannedSets = listOf(PlannedSet(100.0, 5)))
        addRoutineExercise(routineId, bench.id, position = 1, plannedSets = listOf(PlannedSet(60.0, 8)))
        addRoutineExercise(routineId, row.id, position = 2, plannedSets = listOf(PlannedSet(70.0, 10)))
        val workout = workoutFull(
            routineId,
            listOf(
                exerciseWithSets(squat, position = 0, sets = listOf(completed(0, 100.0, 5))),
                exerciseWithSets(bench, position = 1, sets = listOf(uncompleted(0, 60.0, 8))), // dropped
                exerciseWithSets(row, position = 2, sets = listOf(completed(0, 70.0, 10))),
            ),
        )

        useCase.applyToRoutine(workout)

        val stored = routineDao.getRoutineWithExercises(routineId)!!.exercises.sortedBy { it.routineExercise.position }
        assertEquals(listOf(squat.id, row.id), stored.map { it.routineExercise.exerciseId })
        assertEquals(listOf(0, 1), stored.map { it.routineExercise.position })
    }

    // endregion

    // region helpers

    private suspend fun addExercise(name: String, type: ExerciseType = ExerciseType.STRENGTH): ExerciseEntity {
        val entity = ExerciseEntity(name = name, muscleGroup = MuscleGroup.LEGS, type = type)
        return entity.copy(id = exerciseDao.insert(entity))
    }

    private suspend fun addRoutine(name: String): Long = routineDao.upsertRoutine(RoutineEntity(name = name))

    private suspend fun addRoutineExercise(
        routineId: Long,
        exerciseId: Long,
        position: Int,
        plannedSets: List<PlannedSet>,
        restSeconds: Int? = null,
    ) {
        routineDao.insertRoutineExercise(
            RoutineExerciseEntity(
                routineId = routineId,
                exerciseId = exerciseId,
                position = position,
                restSeconds = restSeconds,
                plannedSets = plannedSets,
            ),
        )
    }

    private fun workoutFull(routineId: Long?, exercises: List<WorkoutExerciseWithSets>): WorkoutFull =
        WorkoutFull(
            workout = WorkoutEntity(id = "w", routineId = routineId, name = "W", startedAt = 1_000, finishedAt = null),
            exercises = exercises,
        )

    private fun exerciseWithSets(
        exercise: ExerciseEntity,
        position: Int,
        sets: List<WorkoutSetEntity>,
    ): WorkoutExerciseWithSets =
        WorkoutExerciseWithSets(
            workoutExercise = WorkoutExerciseEntity(
                id = position + 1L,
                workoutId = "w",
                exerciseId = exercise.id,
                position = position,
            ),
            exercise = exercise,
            sets = sets,
        )

    private fun completed(setIndex: Int, weightKg: Double, reps: Int): WorkoutSetEntity =
        WorkoutSetEntity(
            workoutExerciseId = 0,
            setIndex = setIndex,
            weightKg = weightKg,
            reps = reps,
            isCompleted = true,
        )

    private fun uncompleted(setIndex: Int, weightKg: Double, reps: Int): WorkoutSetEntity =
        WorkoutSetEntity(
            workoutExerciseId = 0,
            setIndex = setIndex,
            weightKg = weightKg,
            reps = reps,
            isCompleted = false,
        )

    // endregion
}
