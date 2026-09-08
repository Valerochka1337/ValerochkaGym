package com.valerochka1337.valerochkagym.ui

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.domain.DeleteGymResult
import com.valerochka1337.valerochkagym.domain.GymConfiguration
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.SaveGymResult
import com.valerochka1337.valerochkagym.ui.gyms.GymDetailViewModel
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GymDetailViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun `detail uses reactive availability instead of legacy gym exercise links`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val legacy = exercise(1, "Старое упражнение")
        val available = exercise(2, "Жим гантелей")
        val repository =
            DetailGymRepository(
                gyms =
                    listOf(
                        GymConfiguration(
                            id = "configured",
                            name = "Дом",
                            exercises = listOf(legacy),
                            equipmentIds = setOf("dumbbells"),
                            inventoryConfigured = true,
                        ),
                    ),
                available = listOf(available),
            )
        val viewModel = viewModel(repository)
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }

        advanceUntilIdle()

        assertEquals(setOf("configured"), repository.requestedGymIds.single())
        assertEquals(
            listOf("Жим гантелей"),
            viewModel.uiState.value.availableExercises.map { it.name },
        )
        assertFalse(viewModel.uiState.value.loading)

        repository.available.value = listOf(exercise(3, "Тяга"))
        advanceUntilIdle()
        assertEquals(listOf("Тяга"), viewModel.uiState.value.availableExercises.map { it.name })
        collector.cancel()
      }

  @Test
  fun `detail becomes missing when its gym disappears`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository =
            DetailGymRepository(
                gyms = listOf(GymConfiguration("configured", "Дом", emptyList())),
                available = emptyList(),
            )
        val viewModel = viewModel(repository)
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        repository.gyms.value = emptyList()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.gym)
        assertFalse(viewModel.uiState.value.loading)
        collector.cancel()
      }

  @Test
  fun `detail exposes a recoverable error when repository observation fails`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository =
            DetailGymRepository(
                gyms = listOf(GymConfiguration("configured", "Дом", emptyList())),
                available = emptyList(),
                failGyms = true,
            )
        val viewModel = viewModel(repository)
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.loadError)
        assertFalse(viewModel.uiState.value.loading)

        repository.failGyms = false
        viewModel.retry()
        advanceUntilIdle()

        assertEquals("Дом", viewModel.uiState.value.gym?.name)
        assertFalse(viewModel.uiState.value.loadError)
        collector.cancel()
      }

  private fun viewModel(repository: DetailGymRepository) =
      GymDetailViewModel(
          SavedStateHandle(mapOf(GymRoutes.GYM_ID_ARG to "configured")),
          repository,
      )

  private fun exercise(id: Long, name: String) =
      ExerciseEntity(id, name, MuscleGroup.CHEST, ExerciseType.STRENGTH)
}

private class DetailGymRepository(
    gyms: List<GymConfiguration>,
    available: List<ExerciseEntity>,
    var failGyms: Boolean = false,
) : GymRepository {
  val gyms = MutableStateFlow(gyms)
  val available = MutableStateFlow(available)
  val requestedGymIds = mutableListOf<Set<String>>()

  override fun observeGyms(): Flow<List<GymConfiguration>> =
      if (failGyms) flow { error("offline") } else gyms

  override fun observeExerciseCatalog(): Flow<List<ExerciseEntity>> = available

  override fun observeAvailableExercises(gymIds: Set<String>): Flow<List<ExerciseEntity>> {
    requestedGymIds += gymIds
    return available
  }

  override suspend fun getGym(id: String): GymConfiguration? = null

  override suspend fun saveGym(id: String?, name: String, exerciseIds: Set<Long>): SaveGymResult =
      SaveGymResult.Failure

  override suspend fun deleteGym(id: String): DeleteGymResult = DeleteGymResult.Failure
}
