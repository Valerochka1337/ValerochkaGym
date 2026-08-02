package com.valerochka1337.valerochkagym.ui

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.relation.RoutineExerciseWithExercise
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithCount
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.ui.routine.RoutineEditorViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [RoutineEditorViewModel]. The editor keeps every edit in memory and only touches
 * the DAOs on [RoutineEditorViewModel.save] and on load, so a [FakeRoutineDao] and a
 * [FakeExerciseDao] backed by plain state are enough — no Room, no Robolectric. Loading and
 * `addExerciseById` run inside `viewModelScope`, so the [MainDispatcherRule]'s unconfined dispatcher
 * lets the test read `uiState.value` right after the triggering call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutineEditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // region new routine

    @Test
    fun `a new routine starts empty and invalid`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())

        val state = viewModel.uiState.value
        assertTrue(state.isNew)
        assertEquals("", state.name)
        assertEquals(emptyList<Any>(), state.exercises)
        assertFalse(state.isValid)
    }

    @Test
    fun `save does nothing while the state is invalid`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val routineDao = FakeRoutineDao()
        val viewModel = RoutineEditorViewModel(SavedStateHandle(), routineDao, FakeExerciseDao())

        viewModel.save()

        assertEquals(0, routineDao.upsertCount)
        assertNull(routineDao.lastReplacedRoutineId)
    }

    // endregion

    // region loading an existing routine

    @Test
    fun `loading an existing routine maps its fields and sorts exercises by position`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val squat = exercise(id = 1, name = "Приседания", type = ExerciseType.STRENGTH)
            val plank = exercise(id = 2, name = "Планка", type = ExerciseType.TIMED)
            val routine = RoutineWithExercises(
                routine = RoutineEntity(id = 5, name = "День ног", note = "разминка"),
                exercises = listOf(
                    routineExercise(plank, position = 1, restSeconds = 30, plannedSets = listOf(PlannedSet(durationSec = 60))),
                    routineExercise(squat, position = 0, restSeconds = 90, plannedSets = listOf(PlannedSet(reps = 5))),
                ),
            )
            val viewModel = RoutineEditorViewModel(savedStateHandleFor(5), FakeRoutineDao(listOf(routine)), FakeExerciseDao())

            val state = viewModel.uiState.value
            assertFalse(state.isNew)
            assertEquals("День ног", state.name)
            assertEquals("разминка", state.note)
            assertEquals(listOf("Приседания", "Планка"), state.exercises.map { it.exerciseName })
            val first = state.exercises.first()
            assertEquals(1L, first.exerciseId)
            assertEquals(ExerciseType.STRENGTH, first.exerciseType)
            assertEquals(90, first.restSeconds)
            assertEquals(listOf(PlannedSet(reps = 5)), first.plannedSets)
        }

    // endregion

    // region editing exercises

    @Test
    fun `addExercise seeds three empty sets for a strength exercise`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())

            viewModel.addExercise(exercise(id = 1, name = "Жим", type = ExerciseType.STRENGTH))

            val added = viewModel.uiState.value.exercises.single()
            assertEquals(List(3) { PlannedSet() }, added.plannedSets)
        }

    @Test
    fun `addExercise seeds a single set for timed and cardio exercises`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())

            viewModel.addExercise(exercise(id = 1, name = "Планка", type = ExerciseType.TIMED))
            viewModel.addExercise(exercise(id = 2, name = "Бег", type = ExerciseType.CARDIO))

            val sizes = viewModel.uiState.value.exercises.map { it.plannedSets.size }
            assertEquals(listOf(1, 1), sizes)
        }

    @Test
    fun `removeExercise drops the exercise at the index`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
            viewModel.addExercise(exercise(id = 1, name = "Первое"))
            viewModel.addExercise(exercise(id = 2, name = "Второе"))

            viewModel.removeExercise(0)

            assertEquals(listOf("Второе"), viewModel.uiState.value.exercises.map { it.exerciseName })
        }

    @Test
    fun `moveUp swaps the exercise with the previous one`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
            viewModel.addExercise(exercise(id = 1, name = "Первое"))
            viewModel.addExercise(exercise(id = 2, name = "Второе"))

            viewModel.moveUp(1)

            assertEquals(listOf("Второе", "Первое"), viewModel.uiState.value.exercises.map { it.exerciseName })
        }

    @Test
    fun `moveDown swaps the exercise with the next one`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
            viewModel.addExercise(exercise(id = 1, name = "Первое"))
            viewModel.addExercise(exercise(id = 2, name = "Второе"))

            viewModel.moveDown(0)

            assertEquals(listOf("Второе", "Первое"), viewModel.uiState.value.exercises.map { it.exerciseName })
        }

    @Test
    fun `moveUp on the first exercise is a no-op`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
            viewModel.addExercise(exercise(id = 1, name = "Первое"))
            viewModel.addExercise(exercise(id = 2, name = "Второе"))

            viewModel.moveUp(0)

            assertEquals(listOf("Первое", "Второе"), viewModel.uiState.value.exercises.map { it.exerciseName })
        }

    @Test
    fun `moveDown on the last exercise is a no-op`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
            viewModel.addExercise(exercise(id = 1, name = "Первое"))
            viewModel.addExercise(exercise(id = 2, name = "Второе"))

            viewModel.moveDown(1)

            assertEquals(listOf("Первое", "Второе"), viewModel.uiState.value.exercises.map { it.exerciseName })
        }

    @Test
    fun `addPlannedSet copies the last set`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
            viewModel.addExercise(exercise(id = 1, name = "Жим", type = ExerciseType.STRENGTH))
            val lastSet = PlannedSet(weightKg = 60.0, reps = 8)
            viewModel.updatePlannedSet(0, 2, lastSet)

            viewModel.addPlannedSet(0)

            val sets = viewModel.uiState.value.exercises.single().plannedSets
            assertEquals(4, sets.size)
            assertEquals(lastSet, sets.last())
        }

    @Test
    fun `removePlannedSet drops the set at the index`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
            viewModel.addExercise(exercise(id = 1, name = "Жим", type = ExerciseType.STRENGTH))
            viewModel.updatePlannedSet(0, 0, PlannedSet(reps = 5))

            viewModel.removePlannedSet(0, 0)

            val sets = viewModel.uiState.value.exercises.single().plannedSets
            assertEquals(listOf(PlannedSet(), PlannedSet()), sets)
        }

    @Test
    fun `updatePlannedSet replaces the set at the index`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
            viewModel.addExercise(exercise(id = 1, name = "Жим", type = ExerciseType.STRENGTH))

            val updated = PlannedSet(weightKg = 80.0, reps = 3)
            viewModel.updatePlannedSet(0, 1, updated)

            assertEquals(updated, viewModel.uiState.value.exercises.single().plannedSets[1])
        }

    @Test
    fun `setRest updates the rest seconds of the exercise`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
            viewModel.addExercise(exercise(id = 1, name = "Жим", type = ExerciseType.STRENGTH))

            viewModel.setRest(0, 75)

            assertEquals(75, viewModel.uiState.value.exercises.single().restSeconds)
        }

    @Test
    fun `addExerciseById adds the exercise fetched from the library`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val curl = exercise(id = 7, name = "Сгибания", type = ExerciseType.STRENGTH)
            val viewModel = RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao(listOf(curl)))

            viewModel.addExerciseById(7)

            val added = viewModel.uiState.value.exercises.single()
            assertEquals(7L, added.exerciseId)
            assertEquals("Сгибания", added.exerciseName)
            assertEquals(3, added.plannedSets.size)
        }

    // endregion

    // region saving

    @Test
    fun `save on a new routine upserts a zero id and reindexes positions`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val routineDao = FakeRoutineDao()
            val viewModel = RoutineEditorViewModel(SavedStateHandle(), routineDao, FakeExerciseDao())
            viewModel.setName("  План  ")
            viewModel.addExercise(exercise(id = 1, name = "Первое"))
            viewModel.addExercise(exercise(id = 2, name = "Второе"))

            viewModel.save()

            assertEquals(0L, routineDao.lastUpsertedRoutine?.id)
            assertEquals("План", routineDao.lastUpsertedRoutine?.name)
            assertEquals("", routineDao.lastUpsertedRoutine?.note)
            // The DAO hands back a fresh id (1) for the zero-id insert; the exercises must be written
            // against that returned id, not against `routineId ?: 0`, which would slip through as 0.
            assertEquals(1L, routineDao.lastReplacedRoutineId)
            assertEquals(listOf(1L), routineDao.lastReplacedExercises.map { it.routineId }.distinct())
            assertEquals(listOf(1L, 2L), routineDao.lastReplacedExercises.map { it.exerciseId })
            assertEquals(listOf(0, 1), routineDao.lastReplacedExercises.map { it.position })
        }

    @Test
    fun `save on an existing routine trims the name and keeps the note and id`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val squat = exercise(id = 1, name = "Приседания", type = ExerciseType.STRENGTH)
            val routine = RoutineWithExercises(
                routine = RoutineEntity(id = 5, name = "День ног", note = "разминка"),
                exercises = listOf(routineExercise(squat, position = 0, restSeconds = 90, plannedSets = listOf(PlannedSet(reps = 5)))),
            )
            val routineDao = FakeRoutineDao(listOf(routine))
            val viewModel = RoutineEditorViewModel(savedStateHandleFor(5), routineDao, FakeExerciseDao())
            viewModel.setName("  Новое имя  ")

            viewModel.save()

            assertEquals(5L, routineDao.lastUpsertedRoutine?.id)
            assertEquals("Новое имя", routineDao.lastUpsertedRoutine?.name)
            assertEquals("разминка", routineDao.lastUpsertedRoutine?.note)
            assertEquals(5L, routineDao.lastReplacedRoutineId)
            assertEquals(listOf(0), routineDao.lastReplacedExercises.map { it.position })
        }

    @Test
    fun `save emits the saved event`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = RoutineEditorViewModel(SavedStateHandle(), FakeRoutineDao(), FakeExerciseDao())
        viewModel.setName("План")
        viewModel.addExercise(exercise(id = 1, name = "Первое"))
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.saved.collect { events += it } }

        viewModel.save()

        assertEquals(1, events.size)
    }

    // endregion

    private fun savedStateHandleFor(routineId: Long): SavedStateHandle =
        SavedStateHandle(mapOf(GymRoutes.ROUTINE_ID_ARG to routineId.toString()))

    private fun exercise(id: Long, name: String, type: ExerciseType = ExerciseType.STRENGTH): ExerciseEntity =
        ExerciseEntity(id = id, name = name, muscleGroup = MuscleGroup.LEGS, type = type)

    private fun routineExercise(
        exercise: ExerciseEntity,
        position: Int,
        restSeconds: Int?,
        plannedSets: List<PlannedSet>,
    ): RoutineExerciseWithExercise = RoutineExerciseWithExercise(
        routineExercise = RoutineExerciseEntity(
            routineId = 0,
            exerciseId = exercise.id,
            position = position,
            restSeconds = restSeconds,
            plannedSets = plannedSets,
        ),
        exercise = exercise,
    )

    /**
     * In-memory [RoutineDao] backed by a [MutableStateFlow]. [upsertRoutine] assigns a fresh id to a
     * zero-id routine and records the request; [replaceRoutineExercises] and [deleteRoutine] record
     * their arguments so the tests can assert on what the view model asked the DAO to persist. The
     * exercise-row helpers are not exercised by the editor and are left as no-ops.
     */
    private class FakeRoutineDao(initial: List<RoutineWithExercises> = emptyList()) : RoutineDao {

        private val routines = MutableStateFlow(initial)
        private var nextId = (initial.maxOfOrNull { it.routine.id } ?: 0L) + 1

        var upsertCount = 0
            private set
        var lastUpsertedRoutine: RoutineEntity? = null
            private set
        var lastReplacedRoutineId: Long? = null
            private set
        var lastReplacedExercises: List<RoutineExerciseEntity> = emptyList()
            private set

        override fun observeRoutinesWithCount(): Flow<List<RoutineWithCount>> =
            MutableStateFlow(emptyList())

        override fun observeRoutinesFull(): Flow<List<RoutineWithExercises>> = routines

        override suspend fun getRoutineWithExercises(id: Long): RoutineWithExercises? =
            routines.value.find { it.routine.id == id }

        override suspend fun getRoutineName(id: Long): String? =
            routines.value.find { it.routine.id == id }?.routine?.name

        override suspend fun upsertRoutine(routine: RoutineEntity): Long {
            upsertCount++
            lastUpsertedRoutine = routine
            val id = if (routine.id == 0L) nextId++ else routine.id
            routines.value = routines.value.filterNot { it.routine.id == id } +
                RoutineWithExercises(routine.copy(id = id), emptyList())
            return id
        }

        override suspend fun replaceRoutineExercises(routineId: Long, list: List<RoutineExerciseEntity>) {
            lastReplacedRoutineId = routineId
            lastReplacedExercises = list
        }

        override suspend fun deleteRoutine(id: Long) {
            routines.value = routines.value.filterNot { it.routine.id == id }
        }

        override suspend fun insertRoutineExercise(routineExercise: RoutineExerciseEntity): Long = 0
        override suspend fun insertRoutineExercises(routineExercises: List<RoutineExerciseEntity>): List<Long> = emptyList()
        override suspend fun updateRoutineExercise(routineExercise: RoutineExerciseEntity) = Unit
        override suspend fun deleteRoutineExercise(id: Long) = Unit
        override suspend fun deleteRoutineExercises(routineId: Long) = Unit
    }

    /**
     * In-memory [ExerciseDao] over a fixed catalogue; only [getById] is used by the editor, the rest
     * are the simplest correct implementations over the backing list.
     */
    private class FakeExerciseDao(private val items: List<ExerciseEntity> = emptyList()) : ExerciseDao {

        override fun getAll(): Flow<List<ExerciseEntity>> = MutableStateFlow(items)
        override suspend fun insert(exercise: ExerciseEntity): Long = 0
        override suspend fun update(exercise: ExerciseEntity) = Unit
        override suspend fun insertAll(exercises: List<ExerciseEntity>) = Unit
        override suspend fun count(): Int = items.size
        override suspend fun getById(id: Long): ExerciseEntity? = items.find { it.id == id }
        override suspend fun getAllOnce(): List<ExerciseEntity> = items
        override suspend fun getByIds(ids: List<Long>): List<ExerciseEntity> = items.filter { it.id in ids }
    }
}
