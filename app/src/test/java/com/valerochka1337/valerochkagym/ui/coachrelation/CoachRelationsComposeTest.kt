package com.valerochka1337.valerochkagym.ui.coachrelation

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class CoachRelationsComposeTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `two grants remain independent accessible controls at font scale two`() {
    var calendar = false
    var completed = false
    var confirmed = false
    compose.setContent {
      GymTheme {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
          var c by remember { mutableStateOf(false) }
          var w by remember { mutableStateOf(false) }
          RelationLayout {
            RelationGrant(
                "Календарь",
                c,
                {
                  c = it
                  calendar = it
                },
                true,
            )
            RelationGrant(
                "Завершённые тренировки",
                w,
                {
                  w = it
                  completed = it
                },
                true,
            )
            RelationAction("Подтвердить разрешения", { confirmed = true })
          }
        }
      }
    }
    compose.onNodeWithText("Календарь").assertIsOff().performClick().assertIsOn()
    compose.onNodeWithText("Завершённые тренировки").assertIsOff()
    compose.onNodeWithText("Подтвердить разрешения").performScrollTo().performClick()
    compose.runOnIdle {
      assertTrue(calendar)
      assertFalse(completed)
      assertTrue(confirmed)
    }
  }
}
