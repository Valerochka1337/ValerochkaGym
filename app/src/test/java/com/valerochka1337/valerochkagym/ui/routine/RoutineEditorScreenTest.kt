package com.valerochka1337.valerochkagym.ui.routine

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w420dp-h900dp-xhdpi")
class RoutineEditorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tapping an exercise header expands and collapses its sets`() {
        composeRule.setContent {
            GymTheme {
                ExerciseCard(
                    exercise = EditorExercise(
                        editorId = "bench",
                        exerciseId = 1,
                        exerciseName = "Жим лёжа",
                        exerciseType = ExerciseType.STRENGTH,
                        restSeconds = 90,
                        plannedSets = listOf(
                            PlannedSet(weightKg = 80.0, reps = 8),
                            PlannedSet(weightKg = 80.0, reps = 8),
                            PlannedSet(weightKg = 80.0, reps = 8),
                        ),
                    ),
                    dragHandle = {},
                    onRemove = {},
                    onRestChange = {},
                    onAddSet = {},
                    onRemoveSet = {},
                    onSetChange = { _, _ -> },
                )
            }
        }

        val header = composeRule.onNodeWithText("Жим лёжа")
        header.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Подходы свернуты"),
        )
        composeRule.onNodeWithText("3 подхода · отдых 90 сек").assertIsDisplayed()
        composeRule.onNodeWithText("Отдых, сек").assertDoesNotExist()

        header.performClick()
        composeRule.waitForIdle()

        header.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Подходы раскрыты"),
        )
        composeRule.onNodeWithText("Отдых, сек").assertIsDisplayed()

        header.performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Отдых, сек").assertDoesNotExist()
    }
}
