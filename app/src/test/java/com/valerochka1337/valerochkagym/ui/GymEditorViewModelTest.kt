package com.valerochka1337.valerochkagym.ui

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.domain.DeleteGymResult
import com.valerochka1337.valerochkagym.domain.GymConfiguration
import com.valerochka1337.valerochkagym.domain.GymConfigurationConflict
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.GymRoutineReference
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
  fun `loading an existing gym restores its name and selected exercises`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val squat = exercise(1, "Приседания")
        val press = exercise(2, "Жим")
        val repository =
            FakeGymRepository(
                catalog = listOf(squat, press),
                gym = GymConfiguration("gym-id", "Основной", listOf(press)),
            )

        val viewModel = GymEditorViewModel(savedStateHandle("gym-id"), repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isNew)
        assertFalse(state.isLoading)
        assertEquals("Основной", state.name)
        assertEquals(setOf(2L), state.selectedExerciseIds)
      }

  @Test
  fun `saving trims the name and forwards the selected catalog ids`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = FakeGymRepository(catalog = listOf(exercise(1, "Приседания")))
        val viewModel = GymEditorViewModel(SavedStateHandle(), repository)
        advanceUntilIdle()

        viewModel.setName("  Зал у дома  ")
        viewModel.toggleExercise(1)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(SaveRequest(null, "Зал у дома", setOf(1L)), repository.lastSaveRequest)
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
  fun `search matches an exercise group and type as well as its name`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val cardio =
            ExerciseEntity(
                id = 1,
                name = "Гребля",
                muscleGroup = MuscleGroup.CARDIO,
                type = ExerciseType.TIMED,
            )
        val viewModel =
            GymEditorViewModel(
                SavedStateHandle(),
                FakeGymRepository(catalog = listOf(cardio, exercise(2, "Жим"))),
            )
        advanceUntilIdle()

        viewModel.setQuery("на время")

        assertEquals(listOf("Гребля"), viewModel.uiState.value.filteredExercises.map { it.name })
        assertTrue(viewModel.uiState.value.canSave.not())
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
    val exerciseIds: Set<Long>,
)

private class FakeGymRepository(
    gyms: List<GymConfiguration> = emptyList(),
    catalog: List<ExerciseEntity> = emptyList(),
    private val gym: GymConfiguration? = null,
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
    lastSaveRequest = SaveRequest(id, name, exerciseIds)
    return saveResult
  }

  override suspend fun deleteGym(id: String): DeleteGymResult = deleteResult
}
