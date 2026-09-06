package com.valerochka1337.valerochkagym.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.domain.DeleteGymResult
import com.valerochka1337.valerochkagym.domain.GymConfiguration
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.SaveGymResult
import com.valerochka1337.valerochkagym.ui.gyms.GymEditorScreen
import com.valerochka1337.valerochkagym.ui.gyms.GymEditorViewModel
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w320dp-h720dp-xhdpi")
class GymEditorScreenTest {

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `equipment editor exposes tri-state group selection at large font`() {
    val viewModel = GymEditorViewModel(SavedStateHandle(), ScreenGymRepository())
    var targetPx = 0f
    compose.setContent {
      val density = LocalDensity.current
      targetPx = with(density) { 48.dp.toPx() }
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme { GymEditorScreen(onBack = {}, viewModel = viewModel) }
      }
    }

    compose.onNodeWithText("Поиск оборудования").assertIsDisplayed()
    compose.onNodeWithText("Выбрать всё").assertIsDisplayed()
    compose.onNodeWithText("Сохранить").assertIsDisplayed()
    val freeWeights = hasText("Свободные веса", substring = true)
    compose.onNodeWithTag("gym_equipment_inventory").performScrollToNode(freeWeights)
    compose.onNode(freeWeights).performClick()
    val group = compose.onNodeWithContentDescription("Оборудование группы Свободные веса")
    group.performClick()
    compose.onNodeWithTag("gym_equipment_inventory")
        .performScrollToNode(hasContentDescription("Гантели"))
    val dumbbells = compose.onNodeWithContentDescription("Гантели")
    dumbbells.assertIsDisplayed()
    assertTrue(dumbbells.fetchSemanticsNode().boundsInRoot.height >= targetPx)
    dumbbells.performClick()
    compose.onNodeWithTag("gym_equipment_inventory")
        .performScrollToNode(hasContentDescription("Оборудование группы Свободные веса"))
    group.assert(
        androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.ToggleableState,
            androidx.compose.ui.state.ToggleableState.Indeterminate,
        ),
    )
    group.performClick()
    group.assert(
        androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.ToggleableState,
            androidx.compose.ui.state.ToggleableState.On,
        ),
    )
  }

  @Test
  fun `system back stays on saving draft until save finishes`() {
    val repository = PendingGymRepository()
    val viewModel = GymEditorViewModel(SavedStateHandle(), repository)
    var backCount = 0
    compose.setContent { GymTheme { GymEditorScreen(onBack = { backCount++ }, viewModel = viewModel) } }

    viewModel.setName("Дом")
    viewModel.save()
    compose.waitUntil { viewModel.uiState.value.isSaving }
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

    assertEquals(0, backCount)
    assertEquals("Дом", viewModel.uiState.value.name)
    repository.save.complete(SaveGymResult.Saved("saved"))
    compose.waitUntil { !viewModel.uiState.value.isSaving }
    compose.waitUntil { backCount == 1 }
  }

  @Test
  fun `system back stays on deleting draft until delete finishes`() {
    val repository =
        PendingGymRepository(
            gym = GymConfiguration("gym", "Дом", emptyList(), inventoryConfigured = true),
        )
    val viewModel = GymEditorViewModel(SavedStateHandle(mapOf("gymId" to "gym")), repository)
    var backCount = 0
    compose.setContent { GymTheme { GymEditorScreen(onBack = { backCount++ }, viewModel = viewModel) } }
    compose.waitUntil { !viewModel.uiState.value.isLoading }

    viewModel.delete()
    compose.waitUntil { viewModel.uiState.value.isDeleting }
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

    assertEquals(0, backCount)
    assertEquals("Дом", viewModel.uiState.value.name)
    repository.delete.complete(DeleteGymResult.Deleted)
    compose.waitUntil { !viewModel.uiState.value.isDeleting }
    compose.waitUntil { backCount == 1 }
  }
}

private class ScreenGymRepository : GymRepository {
  private val catalog = MutableStateFlow<List<ExerciseEntity>>(emptyList())

  override fun observeGyms(): Flow<List<GymConfiguration>> = MutableStateFlow(emptyList())

  override fun observeExerciseCatalog(): Flow<List<ExerciseEntity>> = catalog

  override suspend fun getGym(id: String): GymConfiguration? = null

  override suspend fun saveGym(id: String?, name: String, exerciseIds: Set<Long>): SaveGymResult =
      SaveGymResult.Saved("saved")

  override suspend fun deleteGym(id: String): DeleteGymResult = DeleteGymResult.Deleted
}

private class PendingGymRepository(
    private val gym: GymConfiguration? = null,
) : GymRepository {
  val save = CompletableDeferred<SaveGymResult>()
  val delete = CompletableDeferred<DeleteGymResult>()

  override fun observeGyms(): Flow<List<GymConfiguration>> = MutableStateFlow(emptyList())

  override fun observeExerciseCatalog(): Flow<List<ExerciseEntity>> = MutableStateFlow(emptyList())

  override suspend fun getGym(id: String): GymConfiguration? = gym?.takeIf { it.id == id }

  override suspend fun saveGym(id: String?, name: String, exerciseIds: Set<Long>): SaveGymResult =
      SaveGymResult.Failure

  override suspend fun saveGymInventory(
      id: String?,
      name: String,
      equipmentIds: Set<String>,
  ): SaveGymResult = save.await()

  override suspend fun deleteGym(id: String): DeleteGymResult = delete.await()
}
