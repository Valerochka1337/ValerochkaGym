package com.valerochka1337.valerochkagym.ui.gyms

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.data.db.EquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.domain.GymConfiguration
import com.valerochka1337.valerochkagym.ui.navigation.GymWindowWidthClass
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w420dp-h800dp-xhdpi")
class GymDetailScreenTest {

  @get:Rule val compose = createComposeRule()

  @Test
  fun `standard detail offers copy and opens available exercises`() {
    var copiedGymId: String? = null
    var openedExerciseId: Long? = null
    compose.setContent {
      GymTheme {
        GymDetailContent(
            gym =
                GymConfiguration(
                    "standard",
                    "Шаблонный зал",
                    emptyList(),
                    equipmentIds = setOf("dumbbells"),
                    inventoryConfigured = true,
                    origin = "STANDARD",
                ),
            equipment = listOf(EquipmentCatalog.require("dumbbells")),
            availableExercises =
                listOf(ExerciseEntity(4, "Жим гантелей", MuscleGroup.CHEST, ExerciseType.STRENGTH)),
            onCopyGym = { copiedGymId = it },
            onExerciseClick = { openedExerciseId = it },
            windowWidthClass = GymWindowWidthClass.Compact,
        )
      }
    }

    compose.onNodeWithText("Гантели").assertExists()
    compose.onNodeWithText("Клонировать").performClick()
    compose.onNodeWithContentDescription("Упражнение Жим гантелей").performClick()

    assertEquals("standard", copiedGymId)
    assertEquals(4L, openedExerciseId)
  }

  @Test
  fun `only personal detail exposes edit action`() {
    var gym by
        mutableStateOf(GymConfiguration("standard", "Стандарт", emptyList(), origin = "STANDARD"))
    compose.setContent {
      GymTheme {
        GymDetailHeader(
            gym = gym,
            onBack = {},
            onEdit = {},
            onCopy = {},
        )
      }
    }
    compose.onNodeWithContentDescription("Редактировать зал").assertDoesNotExist()

    compose.runOnIdle { gym = GymConfiguration("personal", "Дом", emptyList()) }
    compose.onNodeWithContentDescription("Редактировать зал").assertExists()
    compose.onNodeWithContentDescription("Меню зала").assertExists()
  }

  @Test
  @Config(application = Application::class, qualifiers = "w1200dp-h800dp-xhdpi")
  fun `expanded detail keeps personal edit and standard actions reachable by scroll`() {
    var gym by mutableStateOf(GymConfiguration("personal", "Дом", emptyList()))
    var edits = 0
    var copiedGymId: String? = null
    var openedExerciseId: Long? = null
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          Column(Modifier.fillMaxSize()) {
            GymDetailHeader(
                gym = gym,
                onBack = {},
                onEdit = { edits++ },
                onCopy = { copiedGymId = gym.id },
            )
            GymDetailContent(
                gym = gym,
                equipment = listOf(EquipmentCatalog.require("dumbbells")),
                availableExercises =
                    listOf(
                        ExerciseEntity(4, "Жим гантелей", MuscleGroup.CHEST, ExerciseType.STRENGTH)
                    ),
                onCopyGym = { copiedGymId = it },
                onExerciseClick = { openedExerciseId = it },
                windowWidthClass = GymWindowWidthClass.Expanded,
                modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }

    compose.onNodeWithContentDescription("Редактировать зал").performClick()
    assertEquals(1, edits)
    compose.onNodeWithContentDescription("Меню зала").performClick()
    compose.onNodeWithText("Клонировать").performClick()
    assertEquals("personal", copiedGymId)

    compose.runOnIdle {
      gym =
          GymConfiguration(
              "standard",
              "Шаблонный зал",
              emptyList(),
              equipmentIds = setOf("dumbbells"),
              inventoryConfigured = true,
              origin = "STANDARD",
          )
    }
    compose.onNodeWithText("Клонировать").performScrollTo().performClick()
    compose.onNodeWithText("Гантели").performScrollTo().assertExists()
    compose.onNodeWithContentDescription("Упражнение Жим гантелей").performScrollTo().performClick()

    assertEquals("standard", copiedGymId)
    assertEquals(4L, openedExerciseId)
  }
}
