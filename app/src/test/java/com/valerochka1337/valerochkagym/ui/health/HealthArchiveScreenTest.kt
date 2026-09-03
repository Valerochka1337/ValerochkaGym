package com.valerochka1337.valerochkagym.ui.health

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.db.entity.HealthDocumentEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import com.valerochka1337.valerochkagym.data.health.ArchiveMissingOriginal
import com.valerochka1337.valerochkagym.data.health.HealthArchiveExporter
import com.valerochka1337.valerochkagym.data.health.HealthArchiveExportResult
import com.valerochka1337.valerochkagym.data.health.HealthArchivePreview
import com.valerochka1337.valerochkagym.data.health.HealthArchiveSelection
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w420dp-h900dp-xhdpi")
class HealthArchiveScreenTest : RoomDaoTest() {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `archive controls and missing original warning remain semantic at two hundred percent text`() = runTest {
        db.healthDao().upsertReport(HealthReportEntity("report", 1, 1, false, "CONFIRMED", "DOCUMENT", 1, "CBC"))
        db.healthDao().upsertDocument(HealthDocumentEntity("pending", "report", "hash", "PENDING", "scan.pdf", "application/pdf", 1, null, 1))
        val vm = HealthArchiveViewModel(FakeExporter(), db.healthDao(), db.bodyMeasurementDao(), db.measurementDocumentDao())

        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                GymTheme { HealthArchiveScreen(onBack = {}, viewModel = vm) }
            }
        }
        composeRule.waitForIdle()

        listOf("30 дней", "90 дней", "1 год", "Создать ZIP").forEach {
            composeRule.onNodeWithText(it).assertExists()
        }
        assertTrue(composeRule.onAllNodesWithText("Всё время").fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithText("Исследование report · scan.pdf · ещё готовится").assertIsNotEnabled()
        composeRule.onNodeWithText("30 дней").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("30 дней")[0].assertIsSelected()
    }

    private class FakeExporter : HealthArchiveExporter {
        override suspend fun preview(selection: HealthArchiveSelection) = HealthArchivePreview(
            selection.measurementIds.size, selection.reportIds.size, selection.restrictionIds.size, 0,
            listOf(ArchiveMissingOriginal("health:pending", "Исследование report", "scan.pdf", "ещё не готов")),
        )
        override suspend fun export(output: OutputStream, selection: HealthArchiveSelection) = HealthArchiveExportResult.Success(0, emptyList())
    }
}
