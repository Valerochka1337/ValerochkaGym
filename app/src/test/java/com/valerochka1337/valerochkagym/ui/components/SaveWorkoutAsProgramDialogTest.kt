package com.valerochka1337.valerochkagym.ui.components

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w320dp-h720dp-xhdpi")
class SaveWorkoutAsProgramDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `dialog exposes title editable name validation and accessible actions`() {
        val saving = mutableStateOf(false)
        var minimumTargetPx = 0f
        composeRule.setContent {
            val density = LocalDensity.current
            minimumTargetPx = with(density) { 48.dp.toPx() }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                GymTheme {
                    SaveWorkoutAsProgramDialog(
                        name = "Грудь",
                        isSaving = saving.value,
                        error = "Введите название программы.",
                        onNameChange = {},
                        onConfirm = {},
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Сохранить как программу").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Название программы").assertIsDisplayed()
        composeRule.onNodeWithText("Введите название программы.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Сохранить").assertIsDisplayed()
        composeRule.onNodeWithText("Отмена").assertIsDisplayed()
        val saveBounds = composeRule.onNodeWithContentDescription("Сохранить")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(saveBounds.width >= minimumTargetPx && saveBounds.height >= minimumTargetPx)

        composeRule.runOnIdle { saving.value = true }
        composeRule.onNodeWithContentDescription("Сохранить").assertIsNotEnabled()
        composeRule.onNodeWithText("Отмена").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Сохраняем программу").assertIsDisplayed()
    }
}
