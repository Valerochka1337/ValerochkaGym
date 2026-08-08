package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.ai.ExerciseAiGenerationResult
import com.valerochka1337.valerochkagym.data.ai.ExerciseAiGenerator
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.data.settings.OpenRouterKeyStore
import com.valerochka1337.valerochkagym.ui.library.ExerciseLibraryViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `query filters by Cyrillic name ignoring case`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao, FakeExerciseMuscleDao())
        collectUiState(viewModel)

        viewModel.onQueryChange("жим")

        val names = viewModel.uiState.value.exercises?.map { it.name }
        assertEquals(listOf("Жим ногами", "Жим штанги лёжа"), names?.sorted())
    }

    @Test
    fun `query is trimmed before matching`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao, FakeExerciseMuscleDao())
        collectUiState(viewModel)

        viewModel.onQueryChange("  жим  ")

        val state = viewModel.uiState.value
        assertEquals("  жим  ", state.query)
        assertEquals(listOf("Жим ногами", "Жим штанги лёжа"), state.exercises?.map { it.name }?.sorted())
    }

    @Test
    fun `empty query returns the whole catalogue`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao, FakeExerciseMuscleDao())
        collectUiState(viewModel)

        viewModel.onQueryChange("")

        assertEquals(catalogue().size, viewModel.uiState.value.exercises?.size)
    }

    @Test
    fun `clearQuery restores the full catalogue`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao, FakeExerciseMuscleDao())
        collectUiState(viewModel)

        viewModel.onQueryChange("жим")
        viewModel.clearQuery()

        val state = viewModel.uiState.value
        assertEquals("", state.query)
        assertEquals(catalogue().size, state.exercises?.size)
    }

    // endregion

    // region group filtering

    @Test
    fun `selecting a group narrows the list to that group`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao, FakeExerciseMuscleDao())
        collectUiState(viewModel)

        viewModel.onGroupClicked(MuscleGroup.LEGS)

        val state = viewModel.uiState.value
        val exercises = state.exercises!!
        assertEquals(MuscleGroup.LEGS, state.selectedGroup)
        assertTrue(exercises.all { it.muscleGroup == MuscleGroup.LEGS })
        assertEquals(listOf("Жим ногами", "Приседания"), exercises.map { it.name }.sorted())
    }

    @Test
    fun `re-tapping the active group clears the filter`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao, FakeExerciseMuscleDao())
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
    fun `query and group filter together`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao, FakeExerciseMuscleDao())
        collectUiState(viewModel)

        viewModel.onGroupClicked(MuscleGroup.LEGS)
        viewModel.onQueryChange("жим")

        val names = viewModel.uiState.value.exercises?.map { it.name }
        assertEquals(listOf("Жим ногами"), names)
    }

    // endregion

    // region loading and empty states

    @Test
    fun `initial state is loading with no subscribers`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao, FakeExerciseMuscleDao())

        val state = viewModel.uiState.value
        assertNull(state.exercises)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `first emission carries data and is not empty`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao, FakeExerciseMuscleDao())
        collectUiState(viewModel)

        val state = viewModel.uiState.value
        assertEquals(catalogue().size, state.exercises?.size)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `a list filtered to nothing reports empty`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao, FakeExerciseMuscleDao())
        collectUiState(viewModel)

        viewModel.onQueryChange("плавание")

        val state = viewModel.uiState.value
        assertEquals(emptyList<ExerciseEntity>(), state.exercises)
        assertTrue(state.isEmpty)
    }

    // endregion

    // region editor

    @Test
    fun `saving a new exercise stores it with the muscle map and derived group`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val dao = FakeExerciseDao()
            val muscleDao = FakeExerciseMuscleDao()
            val viewModel = ExerciseLibraryViewModel(dao, muscleDao)

            viewModel.openManualCreate()
            viewModel.saveEditor(
                name = "  Тяга сумо  ",
                type = ExerciseType.STRENGTH,
                loads = listOf(
                    MuscleLoad(Muscle.GLUTES, 100),
                    MuscleLoad(Muscle.LOWER_BACK, 70),
                ),
            )

            val inserted = dao.lastInserted!!
            assertEquals("Тяга сумо", inserted.name)
            assertTrue(inserted.isCustom)
            // Крупная группа выведена из самой вовлечённой мышцы, вручную её не выбирают.
            assertEquals(MuscleGroup.LEGS, inserted.muscleGroup)
            assertEquals(
                mapOf(Muscle.GLUTES to 100, Muscle.LOWER_BACK to 70),
                muscleDao.rows[inserted.id]?.associate { it.muscle to it.contribution },
            )
            assertNull(viewModel.editor.value)
        }

    @Test
    fun `saving without a name or without muscles is ignored`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val dao = FakeExerciseDao()
            val viewModel = ExerciseLibraryViewModel(dao, FakeExerciseMuscleDao())

            viewModel.openManualCreate()
            viewModel.saveEditor("   ", ExerciseType.STRENGTH, listOf(MuscleLoad(Muscle.CHEST, 100)))
            viewModel.saveEditor("Жим", ExerciseType.STRENGTH, emptyList())

            assertEquals(0, dao.insertCount)
            assertNotNull("шторка должна остаться открытой", viewModel.editor.value)
        }

    @Test
    fun `editing a built-in exercise replaces its muscle map but keeps name and group`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val dao = FakeExerciseDao(catalogue())
            val muscleDao = FakeExerciseMuscleDao()
            muscleDao.rows[1L] = mutableListOf(ExerciseMuscleEntity(1L, Muscle.CHEST, 100))
            val viewModel = ExerciseLibraryViewModel(dao, muscleDao)

            viewModel.openEdit(catalogue().first())
            val editor = viewModel.editor.value!!
            assertEquals(mapOf(Muscle.CHEST to 100), editor.loads)
            assertFalse("встроенное упражнение нельзя переименовать", editor.editableName)

            viewModel.saveEditor(
                name = "Другое имя",
                type = ExerciseType.STRENGTH,
                loads = listOf(MuscleLoad(Muscle.CHEST, 100), MuscleLoad(Muscle.TRICEPS, 65)),
            )

            val stored = dao.items.value.first { it.id == 1L }
            assertEquals("Жим штанги лёжа", stored.name)
            assertEquals(MuscleGroup.CHEST, stored.muscleGroup)
            assertEquals(
                mapOf(Muscle.CHEST to 100, Muscle.TRICEPS to 65),
                muscleDao.rows[1L]?.associate { it.muscle to it.contribution },
            )
        }

    @Test
    fun `ai generation opens a prefilled editor without saving an exercise`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val dao = FakeExerciseDao()
            val viewModel = ExerciseLibraryViewModel(
                exerciseDao = dao,
                exerciseMuscleDao = FakeExerciseMuscleDao(),
                exerciseAiGenerator = FakeExerciseAiGenerator(
                    ExerciseAiGenerationResult.New(
                        name = "Тяга сумо",
                        type = ExerciseType.STRENGTH,
                        loads = listOf(MuscleLoad(Muscle.GLUTES, 100), MuscleLoad(Muscle.LOWER_BACK, 70)),
                    ),
                ),
                openRouterKeyStore = FakeOpenRouterKeyStore(configured = true),
            )
            mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

            viewModel.openCreate()
            viewModel.onAiDescriptionChange("Тяга штанги широким хватом сумо")
            viewModel.generateAiExercise()
            mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.aiCreation.value)
            assertEquals(0, dao.insertCount)
            val editor = viewModel.editor.value!!
            assertEquals("Тяга сумо", editor.name)
            assertEquals(ExerciseType.STRENGTH, editor.type)
            assertEquals(mapOf(Muscle.GLUTES to 100, Muscle.LOWER_BACK to 70), editor.loads)
            assertFalse(editor.wasFoundByAi)
        }

    @Test
    fun `ai generation opens the current editor for an existing exercise`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val dao = FakeExerciseDao(catalogue())
            val muscleDao = FakeExerciseMuscleDao().apply {
                rows[1L] = mutableListOf(ExerciseMuscleEntity(1L, Muscle.CHEST, 100))
            }
            val viewModel = ExerciseLibraryViewModel(
                exerciseDao = dao,
                exerciseMuscleDao = muscleDao,
                exerciseAiGenerator = FakeExerciseAiGenerator(ExerciseAiGenerationResult.Existing(1L)),
                openRouterKeyStore = FakeOpenRouterKeyStore(configured = true),
            )
            mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

            viewModel.openCreate()
            viewModel.onAiDescriptionChange("Жим лёжа")
            viewModel.generateAiExercise()
            mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

            val editor = viewModel.editor.value!!
            assertEquals(1L, editor.exerciseId)
            assertEquals("Жим штанги лёжа", editor.name)
            assertEquals(mapOf(Muscle.CHEST to 100), editor.loads)
            assertFalse(editor.editableName)
            assertTrue(editor.wasFoundByAi)
        }

    @Test
    fun `ai generation failure keeps the description available for retry`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = ExerciseLibraryViewModel(
                exerciseDao = FakeExerciseDao(),
                exerciseMuscleDao = FakeExerciseMuscleDao(),
                exerciseAiGenerator = FakeExerciseAiGenerator(
                    ExerciseAiGenerationResult.Failure("Лимит бесплатной модели исчерпан — попробуйте позже"),
                ),
                openRouterKeyStore = FakeOpenRouterKeyStore(configured = true),
            )
            mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

            viewModel.openCreate()
            viewModel.onAiDescriptionChange("Упражнение")
            viewModel.generateAiExercise()
            mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Упражнение", viewModel.aiCreation.value?.description)
            assertEquals("Лимит бесплатной модели исчерпан — попробуйте позже", viewModel.aiCreation.value?.error)
            assertNull(viewModel.editor.value)
        }

    @Test
    fun `manual creation remains available without an OpenRouter key`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = ExerciseLibraryViewModel(FakeExerciseDao(), FakeExerciseMuscleDao())

            viewModel.openCreate()
            viewModel.openManualCreate()

            assertNull(viewModel.aiCreation.value)
            assertNotNull(viewModel.editor.value)
        }

    @Test
    fun `dismissing AI creation cancels a pending result`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val generator = PendingExerciseAiGenerator()
            val viewModel = ExerciseLibraryViewModel(
                exerciseDao = FakeExerciseDao(),
                exerciseMuscleDao = FakeExerciseMuscleDao(),
                exerciseAiGenerator = generator,
                openRouterKeyStore = FakeOpenRouterKeyStore(configured = true),
            )
            mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

            viewModel.openCreate()
            viewModel.onAiDescriptionChange("Упражнение")
            viewModel.generateAiExercise()
            mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
            assertTrue(generator.started.isCompleted)

            viewModel.closeAiCreation()
            generator.result.complete(
                ExerciseAiGenerationResult.New(
                    name = "Не должно открыться",
                    type = ExerciseType.STRENGTH,
                    loads = listOf(MuscleLoad(Muscle.CHEST, 100)),
                ),
            )
            mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.aiCreation.value)
            assertNull(viewModel.editor.value)
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

        val items = MutableStateFlow(initial)

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

        override suspend fun update(exercise: ExerciseEntity) {
            items.value = items.value.map { if (it.id == exercise.id) exercise else it }
        }

        override suspend fun insertAll(exercises: List<ExerciseEntity>) {
            exercises.forEach { insert(it) }
        }

        override suspend fun count(): Int = items.value.size

        override suspend fun getById(id: Long): ExerciseEntity? = items.value.find { it.id == id }

        override suspend fun getAllOnce(): List<ExerciseEntity> = items.value

    }

    /** In-memory [ExerciseMuscleDao]: карта мышц по упражнению, без Room. */
    private class FakeExerciseMuscleDao : ExerciseMuscleDao {

        val rows = mutableMapOf<Long, MutableList<ExerciseMuscleEntity>>()

        override fun observeAll(): Flow<List<ExerciseMuscleEntity>> =
            MutableStateFlow(rows.values.flatten())

        override suspend fun getForExercise(exerciseId: Long): List<ExerciseMuscleEntity> =
            rows[exerciseId].orEmpty().sortedByDescending { it.contribution }

        override suspend fun getMappedExerciseIds(): List<Long> = rows.keys.toList()

        override suspend fun upsertAll(rows: List<ExerciseMuscleEntity>) {
            rows.forEach { row ->
                val list = this.rows.getOrPut(row.exerciseId) { mutableListOf() }
                list.removeAll { it.muscle == row.muscle }
                list += row
            }
        }

        override suspend fun deleteForExercise(exerciseId: Long) {
            rows.remove(exerciseId)
        }
    }

    private class FakeExerciseAiGenerator(
        private val result: ExerciseAiGenerationResult,
    ) : ExerciseAiGenerator {
        override suspend fun generate(description: String): ExerciseAiGenerationResult = result
    }

    private class PendingExerciseAiGenerator : ExerciseAiGenerator {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<ExerciseAiGenerationResult>()

        override suspend fun generate(description: String): ExerciseAiGenerationResult {
            started.complete(Unit)
            return result.await()
        }
    }

    private class FakeOpenRouterKeyStore(configured: Boolean) : OpenRouterKeyStore {
        private val configuredFlow = MutableStateFlow(configured)

        override val isConfigured: Flow<Boolean> = configuredFlow

        override suspend fun save(value: String) {
            configuredFlow.value = value.isNotBlank()
        }

        override suspend fun read(): String? = if (configuredFlow.value) "test-key" else null

        override suspend fun clear() {
            configuredFlow.value = false
        }
    }
}
