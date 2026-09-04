package com.valerochka1337.valerochkagym.ui.routine

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w420dp-h900dp-xhdpi")
class RoutineEditorScreenTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun `reorder keys map exercise cards without counting service list items`() {
    val exercises =
        listOf(
            editorExercise(editorId = "first", exerciseId = 1),
            editorExercise(editorId = "second", exerciseId = 2),
            editorExercise(editorId = "third", exerciseId = 3),
        )

    assertEquals(0, exercises.indexOfReorderKey("first"))
    assertEquals(2, exercises.indexOfReorderKey("third"))
    assertEquals(-1, exercises.indexOfReorderKey("gym-selection-card"))
  }

  @Test
  fun `tapping an exercise header expands and collapses its sets`() {
    composeRule.setContent {
      GymTheme {
        ExerciseCard(
            exercise =
                EditorExercise(
                    editorId = "bench",
                    exerciseId = 1,
                    exerciseName = "Жим лёжа",
                    exerciseType = ExerciseType.STRENGTH,
                    restSeconds = 90,
                    plannedSets =
                        listOf(
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

  private fun editorExercise(editorId: String, exerciseId: Long) =
      EditorExercise(
          editorId = editorId,
          exerciseId = exerciseId,
          exerciseName = "Упражнение $exerciseId",
          exerciseType = ExerciseType.STRENGTH,
          restSeconds = null,
          plannedSets = listOf(PlannedSet()),
      )
}
