package com.valerochka1337.valerochkagym.ui

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.ui.theme.AccentColor
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import com.valerochka1337.valerochkagym.ui.theme.PaletteMode
import com.valerochka1337.valerochkagym.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Проверки выбора акцента.
 *
 * Акцент связывает три вещи, которые компилятор не сверяет между собой: сохранённый `id`, схему
 * цветов темы и `activity-alias` с иконкой в манифесте. Разъехавшийся `aliasName` не сломает сборку
 * — он просто оставит пользователя со старой иконкой, поэтому связка проверяется тестом.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccentColorTest {

  @get:Rule val composeRule = createComposeRule()

  // region хранение

  @Test
  fun `fromId returns the saved accent`() {
    AccentColor.entries.forEach { accent -> assertEquals(accent, AccentColor.fromId(accent.id)) }
  }

  @Test
  fun `fromId falls back to the default for unknown and missing values`() {
    assertEquals(AccentColor.DEFAULT, AccentColor.fromId(null))
    assertEquals(AccentColor.DEFAULT, AccentColor.fromId("magenta"))
  }

  @Test
  fun `ids are unique`() {
    assertEquals(AccentColor.entries.size, AccentColor.entries.map { it.id }.toSet().size)
  }

  // endregion

  // region тема

  @Test
  fun `theme paints distinct roles for every brand palette`() {
    // setContent на правиле вызывается один раз, поэтому все варианты темы собираются
    // в одной композиции — соседними GymTheme, каждый со своим акцентом.
    val roles = mutableMapOf<AccentColor, List<Color>>()

    composeRule.setContent {
      AccentColor.entries.forEach { accent ->
        GymTheme(
            themeMode = ThemeMode.DARK,
            paletteMode = PaletteMode.fromAccent(accent),
            accent = accent,
        ) {
          roles[accent] =
              listOf(
                  MaterialTheme.colorScheme.primary,
                  MaterialTheme.colorScheme.primaryContainer,
                  MaterialTheme.colorScheme.onPrimaryContainer,
              )
        }
      }
    }
    composeRule.waitForIdle()

    assertEquals(AccentColor.entries.size, roles.values.toSet().size)
  }

  // endregion

  // region иконка

  @Test
  fun `every accent has its launcher alias in the manifest`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val flags = PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS
    val activities =
        context.packageManager.getPackageInfo(context.packageName, flags).activities.orEmpty().map {
          it.name
        }

    AccentColor.entries.forEach { accent ->
      val component = "${context.packageName}.${accent.aliasName}"
      assertTrue(
          "нет activity-alias $component — иконка для «${accent.label}» не переключится",
          component in activities,
      )
    }
  }

  // endregion
}
