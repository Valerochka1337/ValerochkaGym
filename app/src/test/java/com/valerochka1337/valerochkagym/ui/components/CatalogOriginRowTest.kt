package com.valerochka1337.valerochkagym.ui.components

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CatalogOriginRowTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `standard origin exposes an accessible explicit copy action`() {
    var copies = 0
    compose.setContent { GymTheme { CatalogOriginContent("STANDARD", false, null) { copies++ } } }
    compose.onNodeWithText("Стандартное").assertExists()
    compose.onNodeWithText("Создать личную копию").assertHasClickAction().performClick()
    assertEquals(1, copies)
  }

  @Test
  fun `pending copy disables repeated action and personal record labels its origin`() {
    compose.setContent { GymTheme { CatalogOriginContent("STANDARD", true, "Сохраняем") {} } }
    compose.onNodeWithText("Создать личную копию").assertIsNotEnabled()
    compose.onNodeWithText("Сохраняем").assertExists()
  }
}
