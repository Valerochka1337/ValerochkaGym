package com.valerochka1337.valerochkagym.ui.health

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.health.HealthArchiveExporter
import com.valerochka1337.valerochkagym.data.health.HealthArchiveExportResult
import com.valerochka1337.valerochkagym.data.health.HealthArchivePreview
import com.valerochka1337.valerochkagym.data.health.HealthArchiveSelection
import com.valerochka1337.valerochkagym.data.health.ArchiveMissingOriginal
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionEntity
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class HealthArchiveViewModelTest : RoomDaoTest() {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun `preview selection export failure and SAF cancel keep source usable`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        db.healthDao().upsertReport(HealthReportEntity("report", 1, 1, false, "CONFIRMED", "LAB", 1, "CBC"))
        val exporter = FakeExporter()
        val vm = HealthArchiveViewModel(exporter, db.healthDao(), db.bodyMeasurementDao())
        val loaded = vm.uiState.first { it.preview != null }
        assertEquals(1, loaded.preview!!.reports)
        assertEquals(0, exporter.exports) // user cancelled SAF: no output stream means no call.

        vm.toggleReport("report")
        testScheduler.advanceUntilIdle()
        assertEquals(emptySet<String>(), vm.uiState.value.reportIds)
        vm.prepareExport()
        val output = ByteArrayOutputStream(); vm.export(output); testScheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.success!!.contains("Архив"))
        assertEquals(emptySet<String>(), exporter.lastSelection!!.reportIds)
        assertEquals("zip".encodeToByteArray().toList(), output.toByteArray().toList())
        assertEquals("CBC", db.healthDao().report("report")!!.title)

        exporter.fail = true; vm.prepareExport(); vm.export(ByteArrayOutputStream()); testScheduler.advanceUntilIdle()
        assertEquals("ошибка", vm.uiState.value.error)
        assertFalse(vm.uiState.value.exporting)
    }

    @Test fun `initial explicit selection deselects only chosen records and survives a fresh view model`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        db.healthDao().upsertReport(HealthReportEntity("r1", 1, 1, false, "CONFIRMED", "LAB", 1, "CBC"))
        db.healthDao().upsertReport(HealthReportEntity("r2", 1, 2, false, "CONFIRMED", "LAB", 2, "Chemistry"))
        db.healthDao().upsertRestriction(HealthRestrictionEntity("x1", 1, 1, false, "ACTIVE", "USER", 1, description = "No sprint"))
        db.healthDao().upsertRestriction(HealthRestrictionEntity("x2", 1, 2, false, "TEMPORARY", "USER", 2, description = "No jump"))
        val exporter = FakeExporter()
        db.bodyMeasurementDao().insert(BodyMeasurementEntity("m1", 1, weightKg = 70.0))
        db.bodyMeasurementDao().insert(BodyMeasurementEntity("m2", 2, weightKg = 69.0))
        val vm = HealthArchiveViewModel(exporter, db.healthDao(), db.bodyMeasurementDao())
        val loaded = vm.uiState.first { it.preview != null }
        assertEquals(setOf("r1", "r2"), loaded.reportIds)
        assertEquals(setOf("x1", "x2"), loaded.restrictionIds)
        assertEquals(setOf("m1", "m2"), loaded.measurementIds)

        vm.toggleReport("r1")
        vm.toggleRestriction("x2")
        vm.toggleMeasurement("m1")
        testScheduler.advanceUntilIdle()
        assertEquals(setOf("r2"), vm.uiState.value.reportIds)
        assertEquals(setOf("x1"), vm.uiState.value.restrictionIds)
        assertEquals(setOf("m2"), vm.uiState.value.measurementIds)
        vm.prepareExport(); vm.export(ByteArrayOutputStream()); testScheduler.advanceUntilIdle()
        assertEquals(setOf("r2"), exporter.lastSelection!!.reportIds)
        assertEquals(setOf("x1"), exporter.lastSelection!!.restrictionIds)

        val recreated = HealthArchiveViewModel(exporter, db.healthDao(), db.bodyMeasurementDao())
        val recreatedState = recreated.uiState.first { it.preview != null }
        assertEquals(setOf("r1", "r2"), recreatedState.reportIds)
        assertFalse(recreatedState.exporting)
    }

    @Test fun `view model closes caller owned output exactly once after export`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val stream = TrackingOutputStream()
        val vm = HealthArchiveViewModel(FakeExporter(), db.healthDao(), db.bodyMeasurementDao())
        vm.export(stream)
        testScheduler.advanceUntilIdle()
        assertEquals(1, stream.closeCalls)
    }

    @Test fun `view model closes streams on early initialization failure and exporter failure`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val exporter = FakeExporter()
        val vm = HealthArchiveViewModel(exporter, db.healthDao(), db.bodyMeasurementDao())
        val early = TrackingOutputStream()
        vm.export(early)
        testScheduler.advanceUntilIdle()
        assertEquals(1, early.closeCalls)
        assertEquals("Состав архива нужно выбрать заново", vm.uiState.value.error)
        assertEquals(0, exporter.exports)

        vm.uiState.first { !it.initializing }
        exporter.fail = true
        val failing = TrackingOutputStream()
        vm.prepareExport()
        vm.export(failing)
        testScheduler.advanceUntilIdle()
        assertEquals(1, failing.closeCalls)
        assertEquals("ошибка", vm.uiState.value.error)
    }

    @Test fun `period presets use injected clock and inclusive bounds`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val now = Instant.parse("2026-09-04T12:00:00Z")
        val vm = HealthArchiveViewModel(
            FakeExporter(), db.healthDao(), db.bodyMeasurementDao(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        vm.uiState.first { !it.initializing }

        vm.selectPeriod(30)
        testScheduler.advanceUntilIdle()

        assertEquals(now.toEpochMilli(), vm.uiState.value.periodEnd)
        assertEquals(now.minusSeconds(30 * 86_400).toEpochMilli(), vm.uiState.value.periodStart)
        assertEquals("30 дней", vm.uiState.value.periodLabel)
    }

    private class FakeExporter : HealthArchiveExporter {
        var exports = 0; var fail = false; var lastSelection: HealthArchiveSelection? = null
        override suspend fun preview(selection: HealthArchiveSelection): HealthArchivePreview {
            lastSelection = selection
            return HealthArchivePreview(selection.measurementIds.size, selection.reportIds.size, selection.restrictionIds.size, 0, listOf(ArchiveMissingOriginal("missing", "Замер m", "inbody.jpg", "повреждён")))
        }
        override suspend fun export(output: OutputStream, selection: HealthArchiveSelection): HealthArchiveExportResult {
            exports++; lastSelection = selection
            if (fail) return HealthArchiveExportResult.Failure("ошибка")
            output.write("zip".encodeToByteArray())
            return HealthArchiveExportResult.Success(1, listOf("missing"))
        }
    }

    private class TrackingOutputStream : OutputStream() {
        var closeCalls = 0
        override fun write(b: Int) = Unit
        override fun close() { closeCalls++ }
    }
}
