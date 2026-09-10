package com.valerochka1337.valerochkagym.ui.calendar

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.valerochka1337.valerochkagym.data.backend.CalendarCloudState
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp-xhdpi")
class CalendarStatusBannerTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `migration error exposes a retry action`() {
    var retries = 0
    compose.setContent {
      GymTheme {
        CalendarStatusBanner(
            CalendarStatusUi(
                CalendarMigrationUiState.Error("Не удалось подготовить календарь"),
                CalendarCloudState.Pending,
            ),
            onRetry = { retries++ },
        )
      }
    }

    compose.onNodeWithText("Не удалось подготовить календарь").assertExists()
    compose.onNodeWithText("Повторить").performClick()

    assertEquals(1, retries)
  }

  @Test
  fun `unsupported cloud status keeps the local calendar success visible`() {
    val state =
        mutableStateOf(
            CalendarStatusUi(
                CalendarMigrationUiState.Ready,
                CalendarCloudState.Unsupported,
            )
        )
    compose.setContent { GymTheme { CalendarStatusBanner(state.value, onRetry = {}) } }

    compose
        .onNodeWithText("Изменения сохранены на устройстве. Сервер пока не поддерживает календарь.")
        .assertExists()
  }
}
