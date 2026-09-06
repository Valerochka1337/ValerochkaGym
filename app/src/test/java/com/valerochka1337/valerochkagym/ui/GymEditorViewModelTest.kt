package com.valerochka1337.valerochkagym.ui

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.db.EquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.domain.DeleteGymResult
import com.valerochka1337.valerochkagym.domain.GymConfiguration
import com.valerochka1337.valerochkagym.domain.GymConfigurationConflict
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.GymRoutineReference
import com.valerochka1337.valerochkagym.domain.ExerciseEquipmentRequirements
import com.valerochka1337.valerochkagym.domain.SaveGymResult
import com.valerochka1337.valerochkagym.ui.gyms.GymEditorViewModel
import com.valerochka1337.valerochkagym.ui.gyms.GymsViewModel
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GymEditorViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun `gyms are sorted by name after the repository emits`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository =
            FakeGymRepository(
                gyms =
                    listOf(
                        GymConfiguration("second", "Яблоко", emptyList()),
                        GymConfiguration("first", "Альфа", emptyList()),
                    ),
            )
        val viewModel = GymsViewModel(repository)
        collectUiState(viewModel)
        advanceUntilIdle()

        assertEquals(listOf("Альфа", "Яблоко"), viewModel.uiState.value.gyms?.map { it.name })
      }

  @Test
  fun `loading an existing gym restores its name and selected equipment`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val squat = exercise(1, "Приседания")
        val press = exercise(2, "Жим")
        val repository =
            FakeGymRepository(
                catalog = listOf(squat, press),
                gym =
                    GymConfiguration(
                        "gym-id",
                        "Основной",
                        listOf(press),
                        equipmentIds = setOf("dumbbells"),
                        inventoryConfigured = true,
                    ),
            )

        val viewModel = GymEditorViewModel(savedStateHandle("gym-id"), repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isNew)
        assertFalse(state.isLoading)
        assertEquals("Основной", state.name)
        assertEquals(setOf("dumbbells"), state.selectedEquipmentIds)
      }

  @Test
  fun `saving trims the name and forwards the selected equipment ids`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = FakeGymRepository(catalog = listOf(exercise(1, "Приседания")))
        val viewModel = GymEditorViewModel(SavedStateHandle(), repository)
        advanceUntilIdle()

        viewModel.setName("  Зал у дома  ")
        viewModel.toggleEquipment("dumbbells")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(SaveRequest(null, "Зал у дома", setOf("dumbbells")), repository.lastSaveRequest)
        assertFalse(viewModel.uiState.value.isSaving)
      }

  @Test
  fun `a save conflict stays visible and does not finish the editor`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val unavailable = exercise(2, "Жим ногами")
        val conflict =
            GymConfigurationConflict(
                routines = listOf(GymRoutineReference(4, "Ноги")),
                exercises = listOf(unavailable),
            )
        val repository =
            FakeGymRepository(
                catalog = listOf(unavailable),
                saveResult = SaveGymResult.Conflict(conflict),
            )
        val viewModel = GymEditorViewModel(SavedStateHandle(), repository)
        advanceUntilIdle()

        viewModel.setName("Основной")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(conflict, viewModel.uiState.value.saveConflict)
        assertFalse(viewModel.uiState.value.isSaving)
      }

  @Test
  fun `deleting an in-use gym exposes the blocking routines`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val routines = listOf(GymRoutineReference(8, "Верх тела"))
        val repository =
            FakeGymRepository(
                gym = GymConfiguration("gym-id", "Основной", emptyList()),
                deleteResult = DeleteGymResult.InUse(routines),
            )
        val viewModel = GymEditorViewModel(savedStateHandle("gym-id"), repository)
        advanceUntilIdle()

        viewModel.delete()
        advanceUntilIdle()

        assertEquals(routines, viewModel.uiState.value.deleteConflict)
        assertFalse(viewModel.uiState.value.isDeleting)
      }

  @Test
  fun `bulk scope respects search and selected mode keeps full group totals`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel =
            GymEditorViewModel(
                SavedStateHandle(),
                FakeGymRepository(),
            )
        advanceUntilIdle()

        viewModel.setQuery("гант")
        viewModel.toggleAll()
        assertEquals(setOf("dumbbells"), viewModel.uiState.value.selectedEquipmentIds)
        viewModel.setMode(com.valerochka1337.valerochkagym.ui.gyms.GymEquipmentMode.SELECTED)

        assertEquals(
            EquipmentCatalog.search("гант").size,
            viewModel.uiState.value.groupedBulkEquipment.values.flatten().size,
        )
      }

  @Test
  fun `searched selected group action changes only visible selected equipment`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = GymEditorViewModel(SavedStateHandle(), FakeGymRepository())
        advanceUntilIdle()

        viewModel.toggleEquipment("dumbbells")
        viewModel.setQuery("свободные")
        viewModel.setMode(com.valerochka1337.valerochkagym.ui.gyms.GymEquipmentMode.SELECTED)
        viewModel.toggleGroup("Свободные веса")

        assertEquals(emptySet<String>(), viewModel.uiState.value.selectedEquipmentIds)
      }

  @Test
  fun `individual edit invalidates bulk undo and saved state restores draft`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val handle = SavedStateHandle()
        val viewModel = GymEditorViewModel(handle, FakeGymRepository())
        advanceUntilIdle()

        viewModel.toggleAll()
        assertTrue(viewModel.uiState.value.bulkUndo != null)
        viewModel.setName("Дом")
        assertEquals(null, viewModel.uiState.value.bulkUndo)

        val restored = GymEditorViewModel(handle, FakeGymRepository())
        assertEquals("Дом", restored.uiState.value.name)
        assertEquals(viewModel.uiState.value.selectedEquipmentIds, restored.uiState.value.selectedEquipmentIds)
      }

  @Test
  fun `copy opens an unsaved independent inventory draft and saves without source id`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val source =
            GymConfiguration(
                id = "source",
                name = "Исходный",
                exercises = emptyList(),
                equipmentIds = setOf("dumbbells"),
                inventoryConfigured = true,
            )
        val repository = FakeGymRepository(gym = source)
        val viewModel =
            GymEditorViewModel(
                SavedStateHandle(mapOf(GymRoutes.GYM_COPY_SOURCE_ARG to "source")),
                repository,
            )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isNew)
        assertTrue(viewModel.uiState.value.isCopy)
        assertEquals(setOf("dumbbells"), viewModel.uiState.value.selectedEquipmentIds)
        viewModel.setName("Копия")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(SaveRequest(null, "Копия", setOf("dumbbells")), repository.lastSaveRequest)
        assertEquals("Исходный", source.name)
      }

  @Test
  fun `legacy copy starts with an explicitly empty equipment draft`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val source = GymConfiguration("source", "Старый", emptyList(), inventoryConfigured = false)
        val viewModel =
            GymEditorViewModel(
                SavedStateHandle(mapOf(GymRoutes.GYM_COPY_SOURCE_ARG to source.id)),
                FakeGymRepository(gym = source),
            )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.copySourceWasLegacy)
        assertEquals(emptySet<String>(), viewModel.uiState.value.selectedEquipmentIds)
      }

  @Test
  fun `preview uses coverage and excludes unknown requirements`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val flat = exercise(1, "Жим лёжа")
        val incline = exercise(2, "Жим на наклонной")
        val decline = exercise(3, "Жим вниз")
        val free = exercise(4, "Планка")
        val unknown = exercise(5, "Старое")
        val repository =
            FakeGymRepository(
                catalog = listOf(flat, incline, decline, free, unknown),
                requirements =
                    mapOf(
                        flat.id to ExerciseEquipmentRequirements.Required(setOf("flat_bench")),
                        incline.id to ExerciseEquipmentRequirements.Required(setOf("incline_bench")),
                        decline.id to ExerciseEquipmentRequirements.Required(setOf("decline_bench")),
                        free.id to ExerciseEquipmentRequirements.ExplicitNone,
                        unknown.id to ExerciseEquipmentRequirements.UnknownLegacy,
                    ),
            )
        val viewModel = GymEditorViewModel(SavedStateHandle(), repository)
        advanceUntilIdle()

        viewModel.toggleEquipment("adjustable_bench")
        viewModel.setPreview(true)
        advanceUntilIdle()

        assertEquals(
            setOf(flat.id, incline.id, free.id),
            viewModel.uiState.value.previewExercises.orEmpty().mapTo(linkedSetOf()) { it.id },
        )
      }

  @Test
  fun `save failure retains the equipment draft`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = FakeGymRepository(saveResult = SaveGymResult.Failure)
        val viewModel = GymEditorViewModel(SavedStateHandle(), repository)
        advanceUntilIdle()

        viewModel.setName("Дом")
        viewModel.toggleEquipment("dumbbells")
        viewModel.save()
        advanceUntilIdle()

        assertEquals("Дом", viewModel.uiState.value.name)
        assertEquals(setOf("dumbbells"), viewModel.uiState.value.selectedEquipmentIds)
        assertTrue(viewModel.uiState.value.actionError != null)
      }

  private fun TestScope.collectUiState(viewModel: GymsViewModel) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
  }

  private fun savedStateHandle(gymId: String): SavedStateHandle =
      SavedStateHandle(mapOf(GymRoutes.GYM_ID_ARG to gymId))

  private fun exercise(id: Long, name: String): ExerciseEntity =
      ExerciseEntity(
          id = id,
          name = name,
          muscleGroup = MuscleGroup.LEGS,
          type = ExerciseType.STRENGTH,
      )
}

private data class SaveRequest(
    val id: String?,
    val name: String,
    val equipmentIds: Set<String>,
)

private class FakeGymRepository(
    gyms: List<GymConfiguration> = emptyList(),
    catalog: List<ExerciseEntity> = emptyList(),
    private val gym: GymConfiguration? = null,
    private val requirements: Map<Long, ExerciseEquipmentRequirements> = emptyMap(),
    var saveResult: SaveGymResult = SaveGymResult.Saved("saved-id"),
    var deleteResult: DeleteGymResult = DeleteGymResult.Deleted,
) : GymRepository {
  private val gymsFlow = MutableStateFlow(gyms)
  private val catalogFlow = MutableStateFlow(catalog)

  var lastSaveRequest: SaveRequest? = null
    private set

  override fun observeGyms(): Flow<List<GymConfiguration>> = gymsFlow

  override fun observeExerciseCatalog(): Flow<List<ExerciseEntity>> = catalogFlow

  override suspend fun getGym(id: String): GymConfiguration? = gym?.takeIf { it.id == id }

  override suspend fun saveGym(
      id: String?,
      name: String,
      exerciseIds: Set<Long>,
  ): SaveGymResult {
    error("Equipment editor must save inventory, not legacy exercise links")
  }

  override suspend fun saveGymInventory(
      id: String?,
      name: String,
      equipmentIds: Set<String>,
  ): SaveGymResult {
    lastSaveRequest = SaveRequest(id, name, equipmentIds)
    return saveResult
  }

  override suspend fun requirementsFor(exercise: ExerciseEntity): ExerciseEquipmentRequirements =
      requirements[exercise.id] ?: ExerciseEquipmentRequirements.UnknownLegacy

  override suspend fun deleteGym(id: String): DeleteGymResult = deleteResult
}
