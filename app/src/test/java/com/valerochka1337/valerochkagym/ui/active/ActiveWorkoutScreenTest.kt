package com.valerochka1337.valerochkagym.ui.active

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseVariantEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.service.RestTimerState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateConnectionState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateReading
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Проверяет пользовательское правило: развёрнутым остаётся только один текущий подход. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w420dp-h4000dp-xhdpi")
class ActiveWorkoutScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `variant chooser keeps named and no variant actions accessible at two times font scale`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                GymTheme {
                    ExerciseVariantSelectionSheet(
                        exerciseName = "Жим",
                        variants = List(12) { index ->
                            ExerciseVariantEntity(
                                exerciseId = 1,
                                syncId = "00000000-0000-0000-0000-${"%012d".format(index + 1)}",
                                name = "Вариант ${index + 1}",
                            )
                        },
                        onChoose = {},
                        onDismiss = {},
                    )
                }
            }
        }
        composeRule.onAllNodesWithText("Без варианта").assertCountEquals(1)
        composeRule.onAllNodesWithText("Вариант 12").assertCountEquals(1)
    }

    @Test
    fun `one completion button follows the focused set`() {
        val workout = mutableStateOf(workoutWithIncompleteExercises())
        val completedSetIds = mutableListOf<Long>()
        val actions = SetActions(
            stepWeight = { _, _ -> },
            stepReps = { _, _ -> },
            stepDuration = { _, _ -> },
            stepSpeed = { _, _ -> },
            stepIncline = { _, _ -> },
            setWeight = { _, _ -> },
            setReps = { _, _ -> },
            setDuration = { _, _ -> },
            setSpeed = { _, _ -> },
            setIncline = { _, _ -> },
            complete = { setId ->
                completedSetIds += setId
                workout.value = workout.value.markSetCompleted(setId)
            },
            uncomplete = { _ -> },
            addSet = { _ -> },
            deleteSet = { _ -> },
        )

        composeRule.setContent {
            GymTheme {
                ActiveWorkoutContent(
                    state = ActiveWorkoutUiState(loading = false, workout = workout.value),
                    elapsedSeconds = MutableStateFlow(0L),
                    restTimer = MutableStateFlow<RestTimerState?>(null),
                    heartRateState = MutableStateFlow<HeartRateConnectionState>(
                        HeartRateConnectionState.Idle,
                    ),
                    heartRateReading = MutableStateFlow<HeartRateReading?>(null),
                    setActions = actions,
                    onDeleteExercise = {},
                    onEditVariant = {},
                    onReorderExercises = {},
                    onAddExercise = {},
                    onExerciseClick = { _, _ -> },
                    onFinish = {},
                    onDiscard = {},
                    onAddRestSeconds = {},
                    onSkipRest = {},
                    onScanHeartRate = {},
                    onConnectHeartRate = {},
                    onCancelHeartRateSelection = {},
                )
            }
        }

        completeFocusedSet()
        assertEquals(listOf(FIRST_SET_ID), completedSetIds)

        completeFocusedSet()
        assertEquals(listOf(FIRST_SET_ID, SECOND_SET_ID), completedSetIds)

        completeFocusedSet()
        assertEquals(listOf(FIRST_SET_ID, SECOND_SET_ID, THIRD_SET_ID), completedSetIds)
        composeRule.onAllNodesWithText("Подход выполнен").assertCountEquals(0)
    }

    private fun completeFocusedSet() {
        composeRule.onAllNodesWithText("Подход выполнен").assertCountEquals(1)
        composeRule.onNodeWithText("Подход выполнен").performClick()
        composeRule.waitForIdle()
    }

    private fun workoutWithIncompleteExercises(): WorkoutFull = WorkoutFull(
        workout = WorkoutEntity(id = "active", name = "Тестовая тренировка", startedAt = 0L),
        exercises = listOf(
            exercise(position = 0, workoutExerciseId = 11L, setId = FIRST_SET_ID, name = "Жим лёжа"),
            exercise(position = 1, workoutExerciseId = 12L, setId = SECOND_SET_ID, name = "Тяга блока"),
            exercise(position = 2, workoutExerciseId = 13L, setId = THIRD_SET_ID, name = "Присед"),
        ),
    )

    private fun exercise(
        position: Int,
        workoutExerciseId: Long,
        setId: Long,
        name: String,
    ): WorkoutExerciseWithSets = WorkoutExerciseWithSets(
        workoutExercise = WorkoutExerciseEntity(
            id = workoutExerciseId,
            workoutId = "active",
            exerciseId = workoutExerciseId + 100L,
            position = position,
        ),
        exercise = ExerciseEntity(
            id = workoutExerciseId + 100L,
            name = name,
            muscleGroup = MuscleGroup.CHEST,
            type = ExerciseType.STRENGTH,
        ),
        sets = listOf(
            WorkoutSetEntity(
                id = setId,
                workoutExerciseId = workoutExerciseId,
                setIndex = 0,
                weightKg = 60.0,
                reps = 8,
            ),
        ),
    )

    private fun WorkoutFull.markSetCompleted(setId: Long): WorkoutFull = copy(
        exercises = exercises.map { exercise ->
            exercise.copy(
                sets = exercise.sets.map { set ->
                    if (set.id == setId) set.copy(isCompleted = true) else set
                },
            )
        },
    )

    private companion object {
        const val FIRST_SET_ID = 101L
        const val SECOND_SET_ID = 102L
        const val THIRD_SET_ID = 103L
    }
}
