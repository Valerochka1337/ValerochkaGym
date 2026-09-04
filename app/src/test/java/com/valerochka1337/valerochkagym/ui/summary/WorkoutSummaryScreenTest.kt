package com.valerochka1337.valerochkagym.ui.summary

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w320dp-h720dp-xhdpi")
class WorkoutSummaryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `summary keeps done as the full width primary action and save as secondary`() {
        var minimumTargetPx = 0f
        composeRule.setContent {
            val density = LocalDensity.current
            minimumTargetPx = with(density) { 48.dp.toPx() }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                GymTheme {
                    Column {
                        WorkoutSummaryActions(
                            canSaveAsProgram = true,
                            onSaveAsProgram = {},
                            onDone = {},
                        )
                    }
                }
            }
        }

        val save = composeRule.onNodeWithContentDescription("Сохранить тренировку как программу")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val done = composeRule.onNodeWithText("Готово")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue(save.width >= minimumTargetPx && save.height >= minimumTargetPx)
        assertTrue(done.width > save.width)
    }
}
