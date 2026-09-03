package com.valerochka1337.valerochkagym.ui.settings

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.data.settings.GymSettings
import com.valerochka1337.valerochkagym.data.settings.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.settings.HealthSyncSettings
import com.valerochka1337.valerochkagym.domain.WorkoutRowMapper
import com.valerochka1337.valerochkagym.domain.health.HealthSheetRows
import com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementRowMapper
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HealthSyncDisclosureDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `real category disclosures follow exported primary field contracts and keep actions reachable at font scale two`() {
        val disclosures = HealthSyncCategory.entries.map(HealthSyncCategory::healthSyncDisclosure)
        assertEquals(
            listOf(
                "Тренировки и программы",
                "Состав тела и InBody",
                "Медицинские анализы",
                "Ограничения и важная информация",
            ),
            disclosures.map(HealthSyncDisclosure::title),
        )
        assertEquals(
            listOf(
                "Идентификаторы, даты завершённых тренировок, названия, секции и заметки программ",
                "Выполненные подходы: упражнения, варианты, веса, повторы, длительность, скорость, наклон и объём",
                "Программы: план подходов и повторений, отдых, упражнения, варианты, залы и связи с залами",
            ),
            disclosures[0].fields,
        )
        assertEquals(
            listOf(
                "Идентификатор, дата и время замера",
                "Все исходные показатели состава тела, InBody и обхватов",
                "Условия замера: после еды, после тренировки, необычная гидратация и заметка",
            ),
            disclosures[1].fields,
        )
        assertEquals(
            listOf(
                "Стабильные идентификаторы, версии, статусы, происхождение, даты, названия и заметки исследований",
                "Каждый результат: исходное название, тип, значение, единица и референс",
                "Метод, материал, источник, страница документа и каноническое сопоставление показателя",
            ),
            disclosures[2].fields,
        )
        assertEquals(
            listOf(
                "Стабильные идентификаторы, версии, структурированное описание, статус и источник",
                "Даты подтверждения, начала и пересмотра",
            ),
            disclosures[3].fields,
        )
        assertEquals(listOf("PDF и фото", "сырой ответ AI", "черновики"), disclosures[2].exclusions)
        assertEquals(listOf("исходный свободный текст"), disclosures[3].exclusions)
        assertEquals(
            "Объём каждого выполненного подхода передаётся в его первичной строке. Отдельные строки и наборы агрегированной аналитики — общий тоннаж, e1RM, нагрузка по мышцам, изменения, тренды, сравнения и сводки — не создаются и не передаются.",
            disclosures.first().localOnly,
        )
        // The disclosure describes the actual wire contract, rather than a second invented list.
        assertTrue(WorkoutRowMapper.HEADER_ROW.containsAll(listOf(
            "workout_id", "weight_kg", "reps", "duration_sec", "speed_kmh", "incline_pct", "volume",
        )))
        assertTrue(disclosures.first().fields.any { it.contains("объём") })
        assertTrue(disclosures.first().localOnly.contains("Объём каждого выполненного подхода передаётся"))
        assertTrue(disclosures.first().localOnly.contains("общий тоннаж"))
        assertTrue(BodyMeasurementRowMapper.HEADER_ROW.containsAll(listOf(
            "after_meal", "after_workout", "unusual_hydration", "condition_note",
        )))
        assertTrue(BodyMeasurementRowMapper.HEADER_ROW.containsAll(listOf("measurement_id", "date", "time")))
        assertTrue(disclosures[1].fields.any { it.contains("после еды") && it.contains("гидратация") })
        assertTrue(HealthSheetRows.OBSERVATION_HEADER.containsAll(listOf(
            "raw_name", "value_type", "raw_value", "unit", "reference_range", "method", "material", "source", "source_page",
        )))
        assertTrue(disclosures[2].fields.none { it.contains("лабораторный флаг", ignoreCase = true) })
        lateinit var current: MutableState<HealthSyncDisclosure>
        composeRule.setContent {
            current = remember { mutableStateOf(disclosures.first()) }
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                GymTheme { HealthSyncDisclosureDialog(current.value, {}, {}) }
            }
        }

        disclosures.forEach { disclosure ->
            composeRule.runOnIdle { current.value = disclosure }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Передача: ${disclosure.title}").assertExists()
            disclosure.fields.forEach { composeRule.onNodeWithText("• $it").assertExists() }
            composeRule.onNodeWithText(disclosure.warning).assertExists()
            composeRule.onNodeWithText(disclosure.localOnly).assertExists()
            disclosure.exclusions.forEach { composeRule.onNodeWithText("• $it").assertExists() }
            composeRule.onNodeWithText("Отмена").assertIsDisplayed()
            composeRule.onNodeWithText("Подтвердить").assertIsDisplayed()
        }
    }

    @Test fun `sync rows expose one switch semantics node with accessible state`() {
        val changes = mutableListOf<Pair<HealthSyncCategory?, Boolean>>()
        val settings = GymSettings(
            healthSync = HealthSyncSettings(
                enabled = true,
                categories = setOf(HealthSyncCategory.MEASUREMENTS),
            ),
        )
        composeRule.setContent {
            GymTheme {
                HealthSyncCard(
                    settings = settings,
                    onEnabledChange = { changes += null to it },
                    onCategoryChange = { category, enabled -> changes += category to enabled },
                )
            }
        }

        val labels = listOf("Включить синхронизацию") +
            HealthSyncCategory.entries.map { it.healthSyncDisclosure().title }
        labels.forEach { label ->
            composeRule.onAllNodesWithContentDescription(label).assertCountEquals(1)
            composeRule.onNodeWithContentDescription(label).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
            )
        }
        composeRule.onNodeWithContentDescription("Состав тела и InBody").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Включено"),
        ).performClick()
        assertEquals(listOf(HealthSyncCategory.MEASUREMENTS to false), changes)
    }

    @Test fun `remote clear selector and destructive confirmation expose all categories at font scale two`() {
        lateinit var current: MutableState<RemoteClearUiState>
        val toggled = mutableListOf<HealthSyncCategory>()
        var continued = 0
        var cancelled = 0
        var confirmed = 0
        composeRule.setContent {
            current = remember { mutableStateOf(RemoteClearUiState.Selecting()) }
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                GymTheme {
                    RemoteClearDialog(
                        state = current.value,
                        onToggleCategory = toggled::add,
                        onContinue = { continued++ },
                        onCancel = { cancelled++ },
                        onConfirm = { confirmed++ },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Удалить данные из Google Sheets").assertIsDisplayed()
        composeRule.onNodeWithText("Продолжить").assertIsNotEnabled()
        HealthSyncCategory.entries.forEach { category ->
            composeRule.onNodeWithContentDescription(category.healthSyncDisclosure().title).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
            ).performClick()
        }
        assertEquals(HealthSyncCategory.entries.toList(), toggled)
        composeRule.onNodeWithText("Отмена").assertIsDisplayed()

        composeRule.runOnIdle {
            current.value = RemoteClearUiState.Confirming(HealthSyncCategory.entries.toList())
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Подтвердить удаление из Google Sheets").assertIsDisplayed()
        HealthSyncCategory.entries.forEach { category ->
            composeRule.onNodeWithText("• ${category.healthSyncDisclosure().title}").assertExists()
        }
        composeRule.onNodeWithText("Локальные данные и настройки останутся без изменений. Операция в таблице может завершиться частично.").assertExists()
        composeRule.onNodeWithText("Назад").assertIsDisplayed().performClick()
        assertEquals(1, cancelled)
        assertEquals(0, continued)
        assertEquals(0, confirmed)
    }
}
