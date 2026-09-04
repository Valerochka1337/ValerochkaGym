package com.valerochka1337.valerochkagym.ui.exercise

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w420dp-h800dp-xhdpi")
class ExerciseDetailScreenComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `detail keeps role text and exposes a read-only body map`() {
        compose.setContent {
            GymTheme {
                ExerciseDetailContent(
                    exercise = ExerciseEntity(
                        id = 1L,
                        name = "Тестовое упражнение",
                        muscleGroup = MuscleGroup.CHEST,
                        type = ExerciseType.STRENGTH,
                        isCustom = true,
                    ),
                    loads = listOf(
                        MuscleLoad(Muscle.UPPER_CHEST, 100),
                        MuscleLoad(Muscle.TRICEPS, 50),
                        MuscleLoad(Muscle.SERRATUS_ANTERIOR, 0),
                    ),
                    statistics = null,
                )
            }
        }

        compose.onNodeWithText("Основная").fetchSemanticsNode()
        compose.onNodeWithText("Вторичная").fetchSemanticsNode()
        compose.onNodeWithText("Стабилизатор").fetchSemanticsNode()
        val map = compose.onNodeWithContentDescription("Карта тела, спереди").fetchSemanticsNode()
        org.junit.Assert.assertFalse(map.config.contains(SemanticsActions.OnClick))
        org.junit.Assert.assertFalse(map.config.contains(SemanticsActions.CustomActions))
    }
}
