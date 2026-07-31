package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.ui.library.ExerciseLibraryViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [ExerciseLibraryViewModel]. Search and muscle-group filtering happen in memory
 * in the view model, so a [FakeExerciseDao] backed by a [MutableStateFlow] is enough — no Room,
 * no Robolectric. The `uiState` flow is produced by `stateIn(WhileSubscribed(5000))`, which stays
 * cold until it has a subscriber; every test that reads filtered data first attaches a live
 * collector via [collectUiState] so the upstream `combine` keeps running and `uiState.value`
 * reflects the latest query/group.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseLibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // region query filtering

    @Test
    fun `query filters by Cyrillic name ignoring case`() = runTest {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao)
        collectUiState(viewModel)

        viewModel.onQueryChange("жим")

        val names = viewModel.uiState.value.exercises?.map { it.name }
        assertEquals(listOf("Жим ногами", "Жим штанги лёжа"), names?.sorted())
    }

    @Test
    fun `query is trimmed before matching`() = runTest {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao)
        collectUiState(viewModel)

        viewModel.onQueryChange("  жим  ")

        val state = viewModel.uiState.value
        assertEquals("  жим  ", state.query)
        assertEquals(listOf("Жим ногами", "Жим штанги лёжа"), state.exercises?.map { it.name }?.sorted())
    }

    @Test
    fun `empty query returns the whole catalogue`() = runTest {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao)
        collectUiState(viewModel)

        viewModel.onQueryChange("")

        assertEquals(catalogue().size, viewModel.uiState.value.exercises?.size)
    }

    // endregion

    // region group filtering

    @Test
    fun `selecting a group narrows the list to that group`() = runTest {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao)
        collectUiState(viewModel)

        viewModel.onGroupClicked(MuscleGroup.LEGS)

        val state = viewModel.uiState.value
        val exercises = state.exercises!!
        assertEquals(MuscleGroup.LEGS, state.selectedGroup)
        assertTrue(exercises.all { it.muscleGroup == MuscleGroup.LEGS })
        assertEquals(listOf("Жим ногами", "Приседания"), exercises.map { it.name }.sorted())
    }

    @Test
    fun `re-tapping the active group clears the filter`() = runTest {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao)
        collectUiState(viewModel)

        viewModel.onGroupClicked(MuscleGroup.LEGS)
        viewModel.onGroupClicked(MuscleGroup.LEGS)

        val state = viewModel.uiState.value
        assertNull(state.selectedGroup)
        assertEquals(catalogue().size, state.exercises?.size)
    }

    // endregion

    // region combined filtering

    @Test
    fun `query and group filter together`() = runTest {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao)
        collectUiState(viewModel)

        viewModel.onGroupClicked(MuscleGroup.LEGS)
        viewModel.onQueryChange("жим")

        val names = viewModel.uiState.value.exercises?.map { it.name }
        assertEquals(listOf("Жим ногами"), names)
    }

    // endregion

    // region loading and empty states

    @Test
    fun `initial state is loading with no subscribers`() = runTest {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao)

        val state = viewModel.uiState.value
        assertNull(state.exercises)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `first emission carries data and is not empty`() = runTest {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao)
        collectUiState(viewModel)

        val state = viewModel.uiState.value
        assertEquals(catalogue().size, state.exercises?.size)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `a list filtered to nothing reports empty`() = runTest {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao)
        collectUiState(viewModel)

        viewModel.onQueryChange("плавание")

        val state = viewModel.uiState.value
        assertEquals(emptyList<ExerciseEntity>(), state.exercises)
        assertTrue(state.isEmpty)
    }

    // endregion

    // region createCustomExercise

    @Test
    fun `createCustomExercise inserts a trimmed custom exercise`() = runTest {
        val dao = FakeExerciseDao()
        val viewModel = ExerciseLibraryViewModel(dao)

        viewModel.createCustomExercise("  Планка  ", MuscleGroup.CORE, ExerciseType.TIMED)

        val inserted = dao.lastInserted
        assertEquals(1, dao.insertCount)
        assertEquals("Планка", inserted?.name)
        assertEquals(MuscleGroup.CORE, inserted?.muscleGroup)
        assertEquals(ExerciseType.TIMED, inserted?.type)
        assertTrue(inserted!!.isCustom)
    }

    @Test
    fun `createCustomExercise ignores a blank name`() = runTest {
        val dao = FakeExerciseDao()
        val viewModel = ExerciseLibraryViewModel(dao)

        viewModel.createCustomExercise("   ", MuscleGroup.CORE, ExerciseType.TIMED)

        assertEquals(0, dao.insertCount)
        assertNull(dao.lastInserted)
    }

    // endregion

    /**
     * Attaches a live collector to [ExerciseLibraryViewModel.uiState] so the `WhileSubscribed`
     * upstream stays active for the rest of the test. Runs on an unconfined dispatcher so the
     * subscription (and every downstream recomputation) is processed eagerly, letting the test
     * read `uiState.value` right after mutating the query or group.
     */
    private fun TestScope.collectUiState(viewModel: ExerciseLibraryViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
    }

    private fun catalogue(): List<ExerciseEntity> = listOf(
        ExerciseEntity(id = 1, name = "Жим штанги лёжа", muscleGroup = MuscleGroup.CHEST, type = ExerciseType.STRENGTH),
        ExerciseEntity(id = 2, name = "Жим ногами", muscleGroup = MuscleGroup.LEGS, type = ExerciseType.STRENGTH),
        ExerciseEntity(id = 3, name = "Приседания", muscleGroup = MuscleGroup.LEGS, type = ExerciseType.STRENGTH),
        ExerciseEntity(id = 4, name = "Подтягивания", muscleGroup = MuscleGroup.BACK, type = ExerciseType.STRENGTH),
    )

    /**
     * In-memory [ExerciseDao] backed by a [MutableStateFlow]; [insert] appends a row (assigning the
     * next id) and records the last inserted exercise so the tests can assert on it. The remaining
     * methods are the simplest correct implementations over the backing list.
     */
    private class FakeExerciseDao(initial: List<ExerciseEntity> = emptyList()) : ExerciseDao {

        private val items = MutableStateFlow(initial)

        var insertCount = 0
            private set
        var lastInserted: ExerciseEntity? = null
            private set

        override fun getAll(): Flow<List<ExerciseEntity>> = items

        override suspend fun insert(exercise: ExerciseEntity): Long {
            val id = (items.value.maxOfOrNull { it.id } ?: 0L) + 1
            val row = exercise.copy(id = id)
            items.value = items.value + row
            insertCount++
            lastInserted = row
            return id
        }

        override suspend fun insertAll(exercises: List<ExerciseEntity>) {
            exercises.forEach { insert(it) }
        }

        override suspend fun count(): Int = items.value.size

        override suspend fun getById(id: Long): ExerciseEntity? = items.value.find { it.id == id }

        override suspend fun getByIds(ids: List<Long>): List<ExerciseEntity> =
            items.value.filter { it.id in ids }
    }
}
