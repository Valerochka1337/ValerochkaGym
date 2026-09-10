package com.valerochka1337.valerochkagym.ui.health

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.domain.*
import com.valerochka1337.valerochkagym.ui.analysis.AnalysisScreenContent
import com.valerochka1337.valerochkagym.ui.analysis.AnalysisUiState
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w840dp-h900dp-xhdpi")
class HealthAnalysisComposeTest {
  @get:Rule val compose = createComposeRule()

  @Test
  @Config(qualifiers = "w360dp-h900dp-xhdpi")
  fun `actual analysis selector reaches health after progress with no workout data`() {
    var created: HealthRecordKind? = null
    compose.setContent {
      TestTheme {
        AnalysisScreenContent(
            AnalysisUiState(loading = false),
            {},
            {},
            {},
            healthContent = {
              HealthAnalysisContent(
                  HealthUiState(loading = false),
                  {},
                  { created = it },
                  {},
                  {},
                  {},
                  {},
                  {},
              )
            },
        )
      }
    }
    val progress = compose.onNodeWithText("Прогресс").getUnclippedBoundsInRoot()
    val health = compose.onNodeWithText("Здоровье").getUnclippedBoundsInRoot()
    assertTrue(health.top >= progress.bottom || health.left >= progress.right)
    compose.onNodeWithText("Здоровье").performScrollTo().performClick()
    compose
        .onNodeWithContentDescription("Добавить: Отчёт")
        .performScrollTo()
        .assertHeightIsAtLeast(48.dp)
        .assertWidthIsAtLeast(48.dp)
        .performClick()
    compose.runOnIdle { assertEquals(HealthRecordKind.REPORT, created) }
  }

  @Test
  @Config(qualifiers = "w360dp-h800dp-xhdpi")
  fun `empty health remains actionable inside actual lazy parent at font scale two`() {
    var created: HealthRecordKind? = null
    compose.setContent {
      TestTheme {
        LazyColumn {
          item {
            HealthAnalysisContent(
                HealthUiState(loading = false),
                {},
                { created = it },
                {},
                {},
                {},
                {},
                {},
            )
          }
        }
      }
    }
    compose
        .onNodeWithContentDescription("Добавить: Отчёт")
        .performScrollTo()
        .assertIsDisplayed()
        .performClick()
    compose.runOnIdle { assertEquals(HealthRecordKind.REPORT, created) }
  }

  @Test
  @Config(qualifiers = "w600dp-h900dp-xhdpi")
  fun `storage server and ai choices remain independent at medium width`() {
    var acknowledgements = 0
    var sync: Boolean? = null
    var ai: Boolean? = null
    compose.setContent {
      TestTheme {
        LazyColumn {
          item {
            HealthAnalysisContent(
                HealthUiState(loading = false),
                {},
                {},
                {},
                { acknowledgements++ },
                { sync = it },
                { ai = it },
                {},
            )
          }
        }
      }
    }
    compose.onNodeWithText("Подтвердить хранение").performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals(1, acknowledgements)
      assertNull(sync)
      assertNull(ai)
    }
    compose.onNodeWithText("Включить синхронизацию здоровья").performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals(true, sync)
      assertNull(ai)
    }
    compose.onNodeWithText("Разрешить обработку InBody AI").performScrollTo().performClick()
    compose.runOnIdle { assertEquals(true, ai) }
  }

  @Test
  fun `compatible chart exposes selectable original table and measurement navigation once`() {
    var opened: String? = null
    val trend =
        HealthTrend(
            HealthTrendKey("metric", "ед.", null, null),
            listOf(
                HealthNumericPoint("one", "2026-09-09", "-2.5"),
                HealthNumericPoint("two", "2026-09-10", "0"),
            ),
            listOf(
                HealthTrendIncompatibility("other", HealthTrendIncompatibilityReason.UNIT, "12 мг")
            ),
        )
    val measurement = BodyMeasurementEntity("measurement", 0, weightKg = 80.0)
    val state =
        HealthUiState(
            loading = false,
            analysis = HealthAnalysisSnapshot(emptyList(), listOf(trend), listOf("measurement")),
            measurements = listOf(measurement, measurement),
        )
    val restoration = StateRestorationTester(compose)
    restoration.setContent {
      TestTheme {
        LazyColumn {
          item { HealthAnalysisContent(state, {}, {}, { opened = it }, {}, {}, {}, {}) }
        }
      }
    }
    compose.onNodeWithContentDescription("График тренда, 2 точек").performScrollTo().assertExists()
    compose.onNodeWithContentDescription("2026-09-10: 0").performScrollTo().performClick()
    compose.onNodeWithText("Выбрано: 2026-09-10, 0").performScrollTo().assertIsDisplayed()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithText("Выбрано: 2026-09-10, 0").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("12 мг: другая единица").performScrollTo().assertIsDisplayed()
    compose.onAllNodesWithText("Масса: 80.0 кг").assertCountEquals(1)
    compose.onNodeWithText("Масса: 80.0 кг").performScrollTo()
    compose
        .onNodeWithContentDescription("Замер ", substring = true)
        .assertHeightIsAtLeast(48.dp)
        .assertWidthIsAtLeast(48.dp)
        .performClick()
    compose.runOnIdle { assertEquals("measurement", opened) }
  }

  @Test
  fun `nonnumeric observations explain the absent line and errors expose retry`() {
    var retries = 0
    val trend =
        HealthTrend(
            HealthTrendKey("metric", null, null, null),
            emptyList(),
            listOf(
                HealthTrendIncompatibility(
                    "record",
                    HealthTrendIncompatibilityReason.VALUE_KIND,
                    "не обнаружено",
                )
            ),
        )
    val state =
        HealthUiState(
            loading = false,
            analysis = HealthAnalysisSnapshot(emptyList(), listOf(trend), emptyList()),
            error = "Связь недоступна",
        )
    compose.setContent {
      TestTheme {
        LazyColumn { item { HealthAnalysisContent(state, {}, {}, {}, {}, {}, {}, { retries++ }) } }
      }
    }
    compose
        .onNodeWithText("не обнаружено: тип значения не подходит для числовой линии")
        .performScrollTo()
        .assertIsDisplayed()
    compose.onNodeWithContentDescription("График тренда, 0 точек").assertDoesNotExist()
    compose.onNodeWithText("Повторить").performScrollTo().performClick()
    compose.runOnIdle { assertEquals(1, retries) }
  }

  @Test
  fun `deleted record retains readable version history without correction action`() {
    val current =
        HealthCurrentRecord("r", HealthRecordKind.RESTRICTION, 1, "deleted", 2, true, 4, null)
    val history =
        HealthHistory(
            current,
            listOf(
                HealthVersionSnapshot(
                    "one",
                    "r",
                    null,
                    HealthRecordKind.RESTRICTION,
                    HealthVersionState.CONFIRMED,
                    1,
                    HealthPayload.Restriction("Исходная формулировка", 1),
                    1,
                    1,
                ),
                HealthVersionSnapshot(
                    "deleted",
                    "r",
                    "one",
                    HealthRecordKind.RESTRICTION,
                    HealthVersionState.TOMBSTONE,
                    2,
                    null,
                    2,
                    3,
                ),
            ),
            listOf(
                HealthHeadHistorySnapshot("r", 2, "deleted", HealthRecordKind.RESTRICTION, true, 4)
            ),
        )
    compose.setContent { TestTheme { HealthDetailContent(history, false, null, {}, {}, {}) } }
    compose.onNodeWithText("Исходная формулировка").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("Изменение 2: удаление").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("Исправить или удалить").assertDoesNotExist()
    compose.onNodeWithContentDescription("Назад").assertIsDisplayed()
  }
}

@Composable
internal fun TestTheme(content: @Composable () -> Unit) {
  val density = LocalDensity.current
  CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
    GymTheme { content() }
  }
}
