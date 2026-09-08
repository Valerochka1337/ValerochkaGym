package com.valerochka1337.valerochkagym.ui.gyms

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.domain.DeleteGymResult
import com.valerochka1337.valerochkagym.domain.GymConfiguration
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.SaveGymResult
import com.valerochka1337.valerochkagym.ui.navigation.GymWindowWidthClass
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w420dp-h800dp-xhdpi")
class GymsScreenTest {

  @get:Rule val compose = createComposeRule()

  @Test
  fun `gyms keep standards collapsed and open every gym detail`() {
    var openedGymId: String? = null
    compose.setContent {
      GymTheme {
        var expanded by remember { mutableStateOf(false) }
        GymsList(
            gyms =
                listOf(
                    GymConfiguration("standard", "Шаблонный зал", emptyList(), origin = "STANDARD"),
                    GymConfiguration("personal", "Дом", emptyList()),
                ),
            templatesExpanded = expanded,
            onTemplatesExpandedChange = { expanded = it },
            onOpenGym = { openedGymId = it },
            windowWidthClass = GymWindowWidthClass.Compact,
        )
      }
    }

    compose.onNodeWithText("Шаблонный зал").assertDoesNotExist()
    compose.onNodeWithText("Дом").assertExists().performClick()
    assertEquals("personal", openedGymId)

    compose.onNodeWithContentDescription("Встроенные, 1").performClick()
    compose.onNodeWithText("Шаблонный зал").assertExists().performClick()
    assertEquals("standard", openedGymId)
    compose.onNodeWithText("Личное", substring = true).assertDoesNotExist()
  }

  @Test
  @Config(application = Application::class, qualifiers = "w320dp-h720dp-xhdpi")
  fun `compact large font template header expands and its gym opens`() {
    var openedGymId: String? = null
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          var expanded by remember { mutableStateOf(false) }
          GymsList(
              gyms =
                  listOf(
                      GymConfiguration(
                          "standard",
                          "Шаблонный зал",
                          emptyList(),
                          origin = "STANDARD",
                      )
                  ),
              templatesExpanded = expanded,
              onTemplatesExpandedChange = { expanded = it },
              onOpenGym = { openedGymId = it },
              windowWidthClass = GymWindowWidthClass.Compact,
          )
        }
      }
    }

    compose.onNodeWithContentDescription("Встроенные, 1").assertIsDisplayed().performClick()
    compose.onNodeWithText("Шаблонный зал").performScrollTo().performClick()

    assertEquals("standard", openedGymId)
  }

  @Test
  fun `gym templates stay expanded after state restoration`() {
    val viewModel = GymsViewModel(RestorationGymsRepository())
    val restoration = StateRestorationTester(compose)
    restoration.setContent {
      GymTheme {
        GymsScreen(
            onBack = {},
            onCreateGym = {},
            onOpenGym = {},
            viewModel = viewModel,
        )
      }
    }

    compose.waitUntil {
      compose.onAllNodesWithContentDescription("Встроенные, 1").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithContentDescription("Встроенные, 1").performClick()
    restoration.emulateSavedInstanceStateRestore()

    compose.onNodeWithText("Шаблонный зал").assertExists()
  }
}

private class RestorationGymsRepository : GymRepository {
  override fun observeGyms(): Flow<List<GymConfiguration>> =
      flowOf(
          listOf(GymConfiguration("standard", "Шаблонный зал", emptyList(), origin = "STANDARD"))
      )

  override fun observeExerciseCatalog(): Flow<List<ExerciseEntity>> = flowOf(emptyList())

  override suspend fun getGym(id: String): GymConfiguration? = null

  override suspend fun saveGym(id: String?, name: String, exerciseIds: Set<Long>): SaveGymResult =
      SaveGymResult.Failure

  override suspend fun deleteGym(id: String): DeleteGymResult = DeleteGymResult.Failure
}
