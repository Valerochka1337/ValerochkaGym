package com.valerochka1337.valerochkagym.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ConfigurationCloneDialogTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `dialog presents the draft and forwards save and cancel`() {
    var saves = 0
    var dismisses = 0
    compose.setContent {
      GymTheme {
        ConfigurationCloneDialog(
            state =
                ConfigurationCloneUiState(
                    target = ConfigurationCloneTarget.Routine(3, "Ноги"),
                    name = "Ноги (копия)",
                ),
            onNameChange = {},
            onSave = { saves++ },
            onDismiss = { dismisses++ },
        )
      }
    }

    compose.onNodeWithText("Клонировать программу").assertExists()
    compose.onNodeWithContentDescription("Название копии").assertTextContains("Ноги (копия)")
    compose.onNodeWithText("Сохранить").performClick()
    compose.onNodeWithText("Отмена").performClick()

    assertEquals(1, saves)
    assertEquals(1, dismisses)
  }

  @Test
  fun `saving dialog disables dismissal actions`() {
    compose.setContent {
      GymTheme {
        ConfigurationCloneDialog(
            state =
                ConfigurationCloneUiState(
                    target = ConfigurationCloneTarget.Gym("home", "Дом"),
                    name = "Дом (копия)",
                    isSaving = true,
                ),
            onNameChange = {},
            onSave = {},
            onDismiss = {},
        )
      }
    }

    compose.onNodeWithText("Отмена").assertIsNotEnabled()
    compose.onNodeWithContentDescription("Название копии").assertIsNotEnabled()
  }
}
