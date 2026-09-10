package com.valerochka1337.valerochkagym.ui.measurements

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
class MeasurementEditorScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `disclosure and photo actions remain reachable at font scale two`() {
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            InBodyImportCard(
                state = MeasurementEditorUiState(healthAiDisclosureEnabled = false),
                onScan = {},
                onDisclosureChange = {},
            )
          }
        }
      }
    }

    compose
        .onNodeWithText("Разрешить обработку фото")
        .performScrollTo()
        .assertIsDisplayed()
        .assertIsEnabled()
    compose
        .onNodeWithText("Выбрать фото листа")
        .performScrollTo()
        .assertIsDisplayed()
        .assertIsEnabled()
  }

  @Test
  fun `revoke stays enabled while an earlier disclosure operation is pending`() {
    compose.setContent {
      GymTheme {
        InBodyImportCard(
            state =
                MeasurementEditorUiState(
                    healthAiDisclosureEnabled = true,
                    isUpdatingHealthAiDisclosure = true,
                ),
            onScan = {},
            onDisclosureChange = {},
        )
      }
    }

    compose.onNodeWithText("Отозвать разрешение на фото").assertIsEnabled()
    compose.onNodeWithText("Выбрать фото листа").assertIsEnabled()
    compose.onNodeWithText("Разрешить обработку фото").assertDoesNotExist()
  }

  @Test
  fun `revoke stays enabled while InBody scan is in flight`() {
    compose.setContent {
      GymTheme {
        InBodyImportCard(
            state =
                MeasurementEditorUiState(healthAiDisclosureEnabled = true, isScanningInBody = true),
            onScan = {},
            onDisclosureChange = {},
        )
      }
    }

    compose.onNodeWithText("Отозвать разрешение на фото").assertIsEnabled()
  }

  @Test
  fun `grant stays disabled while another disclosure operation is pending`() {
    compose.setContent {
      GymTheme {
        InBodyImportCard(
            state = MeasurementEditorUiState(isUpdatingHealthAiDisclosure = true),
            onScan = {},
            onDisclosureChange = {},
        )
      }
    }

    compose.onNodeWithText("Разрешить обработку фото").assertIsNotEnabled()
  }
}
