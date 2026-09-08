package com.valerochka1337.valerochkagym.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.db.EquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.domain.DeleteGymResult
import com.valerochka1337.valerochkagym.domain.GymConfiguration
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.SaveGymResult
import com.valerochka1337.valerochkagym.ui.gyms.GymEditorScreen
import com.valerochka1337.valerochkagym.ui.gyms.GymEditorViewModel
import com.valerochka1337.valerochkagym.ui.navigation.GymWindowWidthClass
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w1200dp-h800dp-xhdpi")
class ExpandedGymEditorScreenTest {

  private lateinit var previousCatalog: List<LocalEquipmentCatalog.Entry>

  @Before
  fun loadEquipmentCatalog() {
    previousCatalog = LocalEquipmentCatalog.state.value
    LocalEquipmentCatalog.publish(
        EquipmentCatalog.entries.map { LocalEquipmentCatalog.Entry(it, false) }
    )
  }

  @After
  fun restoreEquipmentCatalog() {
    LocalEquipmentCatalog.publish(previousCatalog)
  }

  @get:Rule val compose = createComposeRule()

  @Test
  fun `expanded equipment editor keeps search bulk actions and save accessible at large font`() {
    val viewModel = GymEditorViewModel(SavedStateHandle(), ExpandedGymRepository())
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          GymEditorScreen(
              onBack = {},
              windowWidthClass = GymWindowWidthClass.Expanded,
              viewModel = viewModel,
          )
        }
      }
    }

    compose.onNodeWithText("Поиск оборудования").assertIsDisplayed()
    compose.onNodeWithText("Выбрать всё").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag("gym_editor_content").performScrollToNode(hasText("Сохранить"))
    compose.onNodeWithText("Сохранить").assertIsDisplayed()
  }
}

private class ExpandedGymRepository : GymRepository {
  override fun observeGyms(): Flow<List<GymConfiguration>> = flowOf(emptyList())

  override fun observeExerciseCatalog(): Flow<List<ExerciseEntity>> = flowOf(emptyList())

  override suspend fun getGym(id: String): GymConfiguration? = null

  override suspend fun saveGym(id: String?, name: String, exerciseIds: Set<Long>): SaveGymResult =
      SaveGymResult.Saved("saved")

  override suspend fun deleteGym(id: String): DeleteGymResult = DeleteGymResult.Deleted
}
