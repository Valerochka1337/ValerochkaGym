package com.valerochka1337.valerochkagym.ui.health

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.valerochka1337.valerochkagym.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w840dp-h900dp-xhdpi")
class HealthEditorComposeTest {
  @get:Rule val compose = createComposeRule()
  private val target = HealthEditTarget("guest", null, 1, null, null, 0)

  @Test
  @Config(qualifiers = "w360dp-h900dp-xhdpi")
  fun `actual observation controls choose report metric operator and preserve raw fields`() {
    var submitted: HealthEditorDraft.Observation? = null
    val report =
        HealthCurrentRecord(
            "report-two",
            HealthRecordKind.REPORT,
            1,
            "v",
            1,
            false,
            null,
            HealthPayload.Report(
                "Лаборатория, сентябрь",
                null,
                "2026-09-10",
                HealthObservedPrecision.DATE,
            ),
        )
    val state =
        HealthUiState(
            loading = false,
            records = listOf(report),
            metrics = listOf(HealthMetricIdentity("metric-two", "Явно выбранная метрика", 1)),
            consent = HealthConsentSnapshot(1),
        )
    compose.setContent {
      TestTheme {
        var draft by remember {
          mutableStateOf(
              healthObservationDraft()
                  .copy(
                      valueKind = HealthValueKind.RANGE,
                      numberValue = null,
                      rangeLow = "-2.5",
                      rangeHigh = "10",
                  )
          )
        }
        HealthEditorContent(
            HealthEditorSnapshot(target, draft),
            HealthEditorState(),
            state,
            {},
            { draft = it as HealthEditorDraft.Observation },
            {},
            {},
            { submitted = draft },
            {},
            {},
        )
      }
    }
    compose.onNodeWithContentDescription("Лаборатория, сентябрь").performScrollTo().performClick()
    compose.onNodeWithContentDescription("Явно выбранная метрика").performScrollTo().performClick()
    compose.onNodeWithContentDescription("Сравнение").performScrollTo().performClick()
    compose.onNodeWithContentDescription("Не больше").performScrollTo().performClick()
    compose.onNodeWithText("Числовое значение").performScrollTo().performTextReplacement("0")
    compose.onNodeWithText("Подтвердить запись").performScrollTo().performClick()
    compose.runOnIdle {
      val value = requireNotNull(submitted)
      assertEquals("report-two", value.reportLogicalId)
      assertEquals("metric-two", value.metricIdentityId)
      assertEquals(HealthOperator.LE, value.operator)
      assertEquals("0", value.numberValue)
      assertNull(value.rangeLow)
      assertNull(value.rangeHigh)
      assertEquals("исходный референс", value.referenceOriginal)
      assertEquals("образец", value.specimenOriginal)
    }
  }

  @Test
  fun `report edits retain exact offset datetime and original source`() {
    var submitted: HealthEditorDraft.Report? = null
    compose.setContent {
      TestTheme {
        var draft by remember {
          mutableStateOf(
              HealthEditorDraft.Report(
                  "Отчёт",
                  "исходный текст",
                  "2026-09-10",
                  HealthObservedPrecision.DATE,
              )
          )
        }
        HealthEditorContent(
            HealthEditorSnapshot(target, draft),
            HealthEditorState(),
            HealthUiState(loading = false, consent = HealthConsentSnapshot(1)),
            {},
            { draft = it as HealthEditorDraft.Report },
            {},
            {},
            { submitted = draft },
            {},
            {},
        )
      }
    }
    compose.onNodeWithContentDescription("Дата и время").performScrollTo().performClick()
    compose
        .onNodeWithText("Дата или дата-время")
        .performScrollTo()
        .performTextReplacement("2026-09-10T11:22:33.123+03:00")
    compose.onNodeWithText("Подтвердить запись").performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals("2026-09-10T11:22:33.123+03:00", submitted!!.observedAt)
      assertEquals(HealthObservedPrecision.DATETIME, submitted!!.observedPrecision)
      assertEquals("исходный текст", submitted!!.sourceText)
    }
  }

  @Test
  @Config(qualifiers = "w600dp-h900dp-xhdpi")
  fun `renewed notice and tombstone require their distinct explicit actions`() {
    var acknowledgements = 0
    var deletes = 0
    var saves = 0
    compose.setContent {
      TestTheme {
        HealthEditorContent(
            HealthEditorSnapshot(
                target.copy(logicalId = "r", currentVersionId = "v"),
                HealthEditorDraft.Restriction("Исходный текст"),
            ),
            HealthEditorState(
                requiresAcknowledgement = true,
                error = "Требуется новое подтверждение",
            ),
            HealthUiState(loading = false, consent = HealthConsentSnapshot(1)),
            {},
            {},
            {},
            { acknowledgements++ },
            { saves++ },
            { deletes++ },
            {},
        )
      }
    }
    compose.onNodeWithText("Подтвердить хранение").performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals(1, acknowledgements)
      assertEquals(0, saves)
      assertEquals(0, deletes)
    }
    compose.onNodeWithText("Удалить запись").performScrollTo().performClick()
    compose.runOnIdle { assertEquals(0, deletes) }
    compose.onNodeWithText("Подтвердить удаление").performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals(1, deletes)
      assertEquals(0, saves)
    }
  }

  @Test
  fun `stale editor exposes back and retry without a save action`() {
    var retries = 0
    compose.setContent {
      TestTheme {
        HealthEditorContent(
            null,
            HealthEditorState(error = "Запись больше недоступна"),
            HealthUiState(loading = false),
            {},
            {},
            {},
            {},
            {},
            {},
            { retries++ },
        )
      }
    }
    compose.onNodeWithText("Запись больше недоступна").assertIsDisplayed()
    compose.onNodeWithText("Подтвердить запись").assertDoesNotExist()
    compose.onNodeWithText("Повторить").performClick()
    compose.onNodeWithContentDescription("Назад").assertIsDisplayed()
    compose.runOnIdle { assertEquals(1, retries) }
  }
}
