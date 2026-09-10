package com.valerochka1337.valerochkagym.ui.library

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.data.db.EquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.domain.ExerciseEquipmentRequirements
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w420dp-h800dp-xhdpi")
class ExerciseEditorSheetComposeTest {

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
  fun `search selects multiple equipment items and saves them after removing one`() {
    var savedRequirements: ExerciseEquipmentRequirements? = null
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          ExerciseEditorSheet(
              initial = editorState(ExerciseEquipmentRequirements.UnknownLegacy),
              onDismiss = {},
              onSave = { _, _, _, requirements -> savedRequirements = requirements },
          )
        }
      }
    }

    compose.onNodeWithText("Гантели").assertDoesNotExist()
    compose.onNodeWithText("Поиск оборудования").performScrollTo().performClick()
    "ГАНЕТЛИ"
        .forEach { compose.onNodeWithText("Поиск оборудования").performTextInput(it.toString()) }
    compose.onNodeWithText("Гантели").performClick()
    compose.onNodeWithText("Поиск оборудования").performTextReplacement("ШТАНГА")
    compose.onNodeWithText("Штанга").performClick()
    compose.onNodeWithText("Поиск оборудования").performTextReplacement("несуществующее")
    compose.onNodeWithText("Оборудование не найдено").assertIsDisplayed()
    compose.onNodeWithContentDescription("Очистить поиск оборудования").performClick()
    compose.onNodeWithText("Поиск оборудования").performClick()
    compose.onNodeWithContentDescription("Убрать Гантели").performScrollTo().performClick()
    compose.onNodeWithText("Сохранить").performScrollTo().performClick()

    assertEquals(ExerciseEquipmentRequirements.Required(setOf("barbell")), savedRequirements)
  }

  @Test
  fun `legacy requirements require an explicit choice and save explicit none`() {
    var savedRequirements: ExerciseEquipmentRequirements? = null
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          ExerciseEditorSheet(
              initial = editorState(ExerciseEquipmentRequirements.UnknownLegacy),
              onDismiss = {},
              onSave = { _, _, _, requirements -> savedRequirements = requirements },
          )
        }
      }
    }

    compose
        .onNodeWithText(
            "Для этого старого упражнения оборудование ещё не задано. Выберите вариант перед сохранением."
        )
        .performScrollTo()
        .assertIsDisplayed()
    compose.onNodeWithText("Сохранить").performScrollTo().assertIsNotEnabled()

    compose.onNodeWithText("Без оборудования").performScrollTo().performClick()
    compose.onNodeWithText("Сохранить").performScrollTo().performClick()

    assertEquals(ExerciseEquipmentRequirements.ExplicitNone, savedRequirements)
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w1200dp-h800dp-xhdpi")
class ExpandedExerciseEditorSheetComposeTest {

  @get:Rule val compose = createComposeRule()

  @Test
  fun `expanded editor exposes equipment choice and save action`() {
    compose.setContent {
      GymTheme {
        ExerciseEditorSheet(
            initial = editorState(ExerciseEquipmentRequirements.ExplicitNone),
            onDismiss = {},
            onSave = { _, _, _, _ -> },
        )
      }
    }

    compose.onNodeWithText("Без оборудования").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("Сохранить").performScrollTo().assertIsDisplayed()
  }
}

private fun editorState(requirements: ExerciseEquipmentRequirements) =
    ExerciseEditorState(
        exerciseId = 42L,
        name = "Старое упражнение",
        type = ExerciseType.STRENGTH,
        loads = mapOf(Muscle.QUADS to 100),
        editableName = true,
        requirements = requirements,
    )
