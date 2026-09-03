package com.valerochka1337.valerochkagym.ui

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.ai.ExerciseAiGenerationResult
import com.valerochka1337.valerochkagym.data.ai.ExerciseAiGenerator
import com.valerochka1337.valerochkagym.data.ai.MODEL_UNAVAILABLE_MESSAGE
import com.valerochka1337.valerochkagym.data.ai.AiApiConfigurationProvider
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.domain.DeleteGymResult
import com.valerochka1337.valerochkagym.domain.GymConfiguration
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.NewExerciseConfiguration
import com.valerochka1337.valerochkagym.domain.SaveGymResult
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogFilters
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogOrigin
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogRepository
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogRepositoryState
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogSnapshot
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogSort
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogTypeFilter
import com.valerochka1337.valerochkagym.ui.library.ExerciseLibraryViewModel
import com.valerochka1337.valerochkagym.ui.library.SavedExerciseResult
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

    @Test
    fun `rapid query changes stay immediate while repository projection reaches the last query`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = repositoryLibraryViewModel(
                computeDispatcher = StandardTestDispatcher(testScheduler),
            )
            collectUiState(viewModel)

            viewModel.onQueryChange("п")
            viewModel.onQueryChange("по")
            viewModel.onQueryChange("под")

            assertEquals("под", viewModel.query.value)

            advanceUntilIdle()

            assertEquals("под", viewModel.uiState.value.query)
            assertEquals(listOf("Подтягивания"), viewModel.uiState.value.exercises?.map { it.name })
        }

    @Test
    fun `repository catalog controls restore and reset`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val handle = SavedStateHandle(
            mapOf(
                "catalog_query" to "жим",
                "catalog_group" to MuscleGroup.LEGS.name,
                "catalog_type" to ExerciseCatalogTypeFilter.STRENGTH.name,
                "catalog_origin" to ExerciseCatalogOrigin.BUILT_IN.name,
                "catalog_sort" to ExerciseCatalogSort.ALPHABETICAL.name,
            ),
        )
        val viewModel = repositoryLibraryViewModel(
            computeDispatcher = StandardTestDispatcher(testScheduler),
            savedStateHandle = handle,
        )
        collectUiState(viewModel)

        assertEquals("жим", viewModel.query.value)
        advanceUntilIdle()
        assertEquals(ExerciseCatalogFilters(MuscleGroup.LEGS, ExerciseCatalogTypeFilter.STRENGTH, ExerciseCatalogOrigin.BUILT_IN), viewModel.uiState.value.filters)
        assertEquals(ExerciseCatalogSort.ALPHABETICAL, viewModel.uiState.value.sort)
        assertEquals(listOf("Жим ногами"), viewModel.uiState.value.exercises?.map { it.name })

        viewModel.onQueryChange("под")
        viewModel.clearQuery()
        viewModel.setSort(ExerciseCatalogSort.FREQUENT)
        viewModel.setOrigin(ExerciseCatalogOrigin.CUSTOM)
        viewModel.resetCatalog()

        assertEquals("", viewModel.query.value)
        advanceUntilIdle()
        assertEquals(ExerciseCatalogFilters(), viewModel.uiState.value.filters)
        assertEquals(ExerciseCatalogSort.RECENT, viewModel.uiState.value.sort)
        assertEquals(catalogue().size, viewModel.uiState.value.exercises?.size)
        assertEquals("", handle.get<String>("catalog_query"))
        assertEquals(ExerciseCatalogSort.RECENT.name, handle.get<String>("catalog_sort"))
    }

    // endregion

    @Test
    fun `reset filters preserves query and sort`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val handle = SavedStateHandle()
        val viewModel = ExerciseLibraryViewModel(FakeExerciseDao(catalogue()), FakeExerciseMuscleDao(), savedStateHandle = handle)
        collectUiState(viewModel)

        viewModel.onQueryChange("жим")
        viewModel.toggleGroupFacet(MuscleGroup.LEGS)
        viewModel.setTypeFilter(ExerciseCatalogTypeFilter.STRENGTH)
        viewModel.setOrigin(ExerciseCatalogOrigin.BUILT_IN)
        viewModel.setSort(ExerciseCatalogSort.ALPHABETICAL)

        viewModel.resetFilters()

        assertEquals(ExerciseCatalogFilters(), viewModel.uiState.value.filters)
        assertEquals("жим", viewModel.uiState.value.query)
        assertEquals(ExerciseCatalogSort.ALPHABETICAL, viewModel.uiState.value.sort)
        assertNull(handle.get<String>("catalog_group"))
        assertEquals(ExerciseCatalogTypeFilter.ALL.name, handle.get<String>("catalog_type"))
        assertEquals(ExerciseCatalogOrigin.ALL.name, handle.get<String>("catalog_origin"))
    }

    @Test
    fun `legacy saved type values restore to their compatible type families`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        fun restoredType(value: String): ExerciseCatalogTypeFilter {
            val viewModel = ExerciseLibraryViewModel(
                FakeExerciseDao(catalogue()),
                FakeExerciseMuscleDao(),
                savedStateHandle = SavedStateHandle(mapOf("catalog_type" to value)),
            )
            collectUiState(viewModel)
            return viewModel.uiState.value.filters.type
        }

        assertEquals(ExerciseCatalogTypeFilter.CARDIO_OR_TIMED, restoredType(ExerciseType.CARDIO.name))
        assertEquals(ExerciseCatalogTypeFilter.CARDIO_OR_TIMED, restoredType(ExerciseType.TIMED.name))
        assertEquals(ExerciseCatalogTypeFilter.STRENGTH, restoredType(ExerciseType.STRENGTH.name))
    }

    @Test
    fun `legacy muscle facet state no longer constrains the visible catalog`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = ExerciseLibraryViewModel(
            FakeExerciseDao(catalogue()),
            FakeExerciseMuscleDao(),
            savedStateHandle = SavedStateHandle(mapOf("catalog_muscle" to "CHEST")),
        )
        collectUiState(viewModel)

        assertEquals(ExerciseCatalogFilters(), viewModel.uiState.value.filters)
        assertEquals(catalogue().size, viewModel.uiState.value.exercises?.size)
    }

    @Test
    fun `unavailable selected type persists and exposes resettable empty state`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = ExerciseLibraryViewModel(FakeExerciseDao(catalogue()), FakeExerciseMuscleDao())
        collectUiState(viewModel)

        viewModel.setTypeFilter(ExerciseCatalogTypeFilter.CARDIO_OR_TIMED)

        assertEquals(ExerciseCatalogTypeFilter.CARDIO_OR_TIMED, viewModel.uiState.value.filters.type)
        assertTrue(viewModel.uiState.value.isEmpty)
        assertTrue(viewModel.uiState.value.hasActiveConstraints)
        viewModel.resetCatalog()
        assertEquals(ExerciseCatalogTypeFilter.ALL, viewModel.uiState.value.filters.type)
    }

    // region group filtering

    @Test
    fun `selecting a group narrows the list to that group`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao, FakeExerciseMuscleDao())
        collectUiState(viewModel)

        viewModel.toggleGroupFacet(MuscleGroup.LEGS)

        val state = viewModel.uiState.value
        val exercises = state.exercises!!
        assertEquals(MuscleGroup.LEGS, state.filters.group)
        assertTrue(exercises.all { it.muscleGroup == MuscleGroup.LEGS })
        assertEquals(listOf("Жим ногами", "Приседания"), exercises.map { it.name }.sorted())
    }

    @Test
    fun `re-tapping the active group clears the filter`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao, FakeExerciseMuscleDao())
        collectUiState(viewModel)

        viewModel.toggleGroupFacet(MuscleGroup.LEGS)
        viewModel.toggleGroupFacet(MuscleGroup.LEGS)

        val state = viewModel.uiState.value
        assertNull(state.filters.group)
        assertEquals(catalogue().size, state.exercises?.size)
    }

    // endregion

    // region combined filtering

    @Test
    fun `query and group filter together`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = FakeExerciseDao(catalogue())
        val viewModel = ExerciseLibraryViewModel(dao, FakeExerciseMuscleDao())
        collectUiState(viewModel)

        viewModel.toggleGroupFacet(MuscleGroup.LEGS)
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
    fun `saving a new picker exercise assigns it to selected gyms and emits it`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val repository = FakePickerGymRepository()
            val viewModel = ExerciseLibraryViewModel(
                exerciseDao = FakeExerciseDao(),
                exerciseMuscleDao = FakeExerciseMuscleDao(),
                savedStateHandle = SavedStateHandle(
                    mapOf(GymRoutes.GYM_IDS_ARG to "gym-first,gym-second"),
                ),
                gymRepository = repository,
            )
            val savedExercises = mutableListOf<SavedExerciseResult>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.savedExercise.collect { savedExercises += it }
            }

            viewModel.openManualCreate()
            viewModel.saveEditor(
                name = "  Тяга сумо  ",
                type = ExerciseType.STRENGTH,
                loads = listOf(
                    MuscleLoad(Muscle.GLUTES, 100),
                    MuscleLoad(Muscle.LOWER_BACK, 70),
                ),
            )
            mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(setOf("gym-first", "gym-second"), repository.lastAssignedGymIds)
            assertEquals("Тяга сумо", repository.lastCreation?.exercise?.name)
            assertEquals(
                mapOf(Muscle.GLUTES to 100, Muscle.LOWER_BACK to 70),
                repository.lastCreation?.muscles?.associate { it.muscle to it.contribution },
            )
            assertEquals(listOf(42L), savedExercises.map { it.exercise.id })
            assertEquals(listOf("Тяга сумо"), savedExercises.map { it.exercise.name })
            assertEquals(listOf(false), savedExercises.map { it.addedToWorkout })
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
                aiApiConfigurationProvider = FakeAiApiConfigurationProvider(configured = true),
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
                aiApiConfigurationProvider = FakeAiApiConfigurationProvider(configured = true),
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
                aiApiConfigurationProvider = FakeAiApiConfigurationProvider(configured = true),
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
    fun `ai generation exposes a settings action when the selected model is unavailable`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = ExerciseLibraryViewModel(
                exerciseDao = FakeExerciseDao(),
                exerciseMuscleDao = FakeExerciseMuscleDao(),
                exerciseAiGenerator = FakeExerciseAiGenerator(
                    ExerciseAiGenerationResult.Failure(
                        message = MODEL_UNAVAILABLE_MESSAGE,
                        modelUnavailable = true,
                    ),
                ),
                aiApiConfigurationProvider = FakeAiApiConfigurationProvider(configured = true),
            )
            mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

            viewModel.openCreate()
            viewModel.onAiDescriptionChange("Упражнение")
            viewModel.generateAiExercise()
            mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.aiCreation.value?.modelUnavailable ?: false)
        }

    @Test
    fun `manual creation remains available without an AiApi key`() =
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
                aiApiConfigurationProvider = FakeAiApiConfigurationProvider(configured = true),
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

    private fun repositoryLibraryViewModel(
        computeDispatcher: TestDispatcher,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): ExerciseLibraryViewModel = ExerciseLibraryViewModel(
        exerciseDao = FakeExerciseDao(),
        exerciseMuscleDao = FakeExerciseMuscleDao(),
        savedStateHandle = savedStateHandle,
        catalogRepository = FakeExerciseCatalogRepository(catalogue()),
        computeDispatcher = computeDispatcher,
    )

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

    /** Production catalog path backed by an in-memory source, without falling back to the exercise DAO. */
    private class FakeExerciseCatalogRepository(exercises: List<ExerciseEntity>) : ExerciseCatalogRepository {

        private val state = MutableStateFlow(
            ExerciseCatalogRepositoryState(
                snapshot = ExerciseCatalogSnapshot(exercises, emptyList(), emptyList()),
                gymNames = emptyList(),
            ),
        )

        override fun observeCatalog(gymIds: Set<String>): Flow<ExerciseCatalogRepositoryState> = state
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

    private class FakeAiApiConfigurationProvider(configured: Boolean) : AiApiConfigurationProvider {
        private val configuredFlow = MutableStateFlow(configured)

        override val isConfigured: Flow<Boolean> = configuredFlow

        override suspend fun connection() = null

        override suspend fun requestConfiguration() = null
    }

    private class FakePickerGymRepository : GymRepository {
        var lastCreation: NewExerciseConfiguration? = null
            private set
        var lastAssignedGymIds: Set<String>? = null
            private set

        override fun observeGyms(): Flow<List<GymConfiguration>> = MutableStateFlow(emptyList())

        override fun observeExerciseCatalog(): Flow<List<ExerciseEntity>> = MutableStateFlow(emptyList())

        override fun observeAvailableExercises(gymIds: Set<String>): Flow<List<ExerciseEntity>> =
            MutableStateFlow(emptyList())

        override suspend fun getGym(id: String): GymConfiguration? = null

        override suspend fun saveGym(
            id: String?,
            name: String,
            exerciseIds: Set<Long>,
        ): SaveGymResult = SaveGymResult.Failure

        override suspend fun deleteGym(id: String): DeleteGymResult = DeleteGymResult.NotFound

        override suspend fun createExerciseAndAssign(
            configuration: NewExerciseConfiguration,
            gymIds: Set<String>,
        ): ExerciseEntity {
            lastCreation = configuration
            lastAssignedGymIds = gymIds
            return configuration.exercise.copy(id = 42L)
        }
    }
}
