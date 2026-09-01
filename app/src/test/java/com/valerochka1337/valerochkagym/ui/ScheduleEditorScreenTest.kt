package com.valerochka1337.valerochkagym.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.valerochka1337.valerochkagym.ui.calendar.ScheduleClearButton
import com.valerochka1337.valerochkagym.ui.calendar.ScheduleSaveButton
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ScheduleEditorScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `save and clear are disabled and re-enabled by the same busy state`() {
        val busy = mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                Column {
                    ScheduleClearButton(enabled = !busy.value, onClick = {})
                    ScheduleSaveButton(enabled = !busy.value, onClick = {})
                }
            }
        }

        composeRule.onNodeWithText("Очистить расписание").assertIsNotEnabled()
        composeRule.onNodeWithText("Сохранить").assertIsNotEnabled()

        composeRule.runOnIdle { busy.value = false }
        composeRule.onNodeWithText("Очистить расписание").assertIsEnabled()
        composeRule.onNodeWithText("Сохранить").assertIsEnabled()
    }
}
