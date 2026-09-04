package com.valerochka1337.valerochkagym.ui.health

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncConflictEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthDocumentEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportSnapshotEntity
import com.valerochka1337.valerochkagym.data.health.HealthRepository
import com.valerochka1337.valerochkagym.data.health.HealthSyncPayloadCodec
import com.valerochka1337.valerochkagym.data.health.HealthDocumentRepository
import com.valerochka1337.valerochkagym.data.health.HealthDocumentInput
import com.valerochka1337.valerochkagym.data.health.HealthDocumentStoreResult
import android.net.Uri
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthReportDetailViewModelTest : RoomDaoTest() {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `detail filters a strict numeric series to its route period and keeps conflicts visible`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val dao = db.healthDao()
            dao.upsertReport(HealthReportEntity("old", 1, 1, false, "CONFIRMED", "LAB", 100, "old"))
            dao.upsertReport(HealthReportEntity("selected", 1, 2, false, "CONFIRMED", "LAB", 200, "selected"))
            dao.upsertReport(HealthReportEntity("revoked", 1, 3, true, "REVOKED", "LAB", 150, "revoked"))
            dao.insertObservations(listOf(
                observation("old-observation", "old", 100, "4.0", "glucose"),
                observation("selected-observation", "selected", 200, "5.0", "glucose"),
                observation("revoked-observation", "revoked", 150, "999.0", "glucose"),
                observation("text", "selected", 200, "positive", "status", type = "TEXT"),
            ))
            dao.upsertConflict(HealthSyncConflictEntity("HEALTH_REPORTS_AND_OBSERVATIONS", "selected", 1, "local", "a", "remote", "b", 3))

            val vm = HealthReportDetailViewModel(
                SavedStateHandle(mapOf(
                    GymRoutes.HEALTH_REPORT_ID_ARG to "selected",
                    GymRoutes.HEALTH_PERIOD_START_ARG to 90L,
                    GymRoutes.HEALTH_PERIOD_END_ARG to 210L,
                )),
                dao,
                HealthRepository(db),
                FakeDocuments(),
            )
            val collector = launch { vm.uiState.collect() }
            val state = vm.uiState.first { it.report?.syncId == "selected" && it.conflicts.isNotEmpty() }
            assertEquals(1, state.conflicts.size)
            assertEquals(1, state.comparableSeries.size)
            assertEquals(listOf(4.0, 5.0), state.comparableSeries.single().points.map { it.value })
            assertTrue("text values never create a trend", state.comparableSeries.none { it.canonicalKey == "status" })
            collector.cancel()
        }

    @Test fun `detail exposes method material unit and source incompatibility instead of merging a trend`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = db.healthDao()
        dao.upsertReport(HealthReportEntity("old", 1, 1, false, "FINAL", "LAB", 100, "old"))
        dao.upsertReport(HealthReportEntity("selected", 1, 2, false, "FINAL", "LAB", 200, "selected"))
        dao.insertObservations(listOf(
            observation("old", "old", 100, "4.0", "glucose").copy(unit = "mmol/L", method = "A", material = "plasma", source = "Lab A"),
            observation("selected", "selected", 200, "5.0", "glucose").copy(unit = "mg/dL", method = "B", material = "serum", source = "Lab B"),
        ))
        val vm = HealthReportDetailViewModel(SavedStateHandle(mapOf(GymRoutes.HEALTH_REPORT_ID_ARG to "selected")), dao, HealthRepository(db), FakeDocuments())
        val c = launch { vm.uiState.collect() }
        val warning = vm.uiState.first { it.incompatibilities.isNotEmpty() }.incompatibilities.single()
        assertEquals("glucose", warning.canonicalKey)
        assertTrue(warning.reason.contains("единицы")); assertTrue(warning.reason.contains("материал")); assertTrue(warning.reason.contains("метод")); assertTrue(warning.reason.contains("источник"))
        c.cancel()
    }

    private fun observation(id: String, report: String, at: Long, value: String, canonical: String, type: String = "NUMBER") =
        HealthObservationEntity(id, report, 1, at, false, at, canonical, type, value, "mmol/L", canonicalKey = canonical)

    @Test fun `delete keeps or removes only linked originals after tombstone`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = db.healthDao(); dao.upsertReport(HealthReportEntity("delete", 1, 1, false, "CONFIRMED", "LAB", 1, "CBC"))
        dao.upsertDocument(HealthDocumentEntity("linked", "delete", "a", "READY", "a.pdf", "application/pdf", 1, null, 1))
        val docs = FakeDocuments(); val vm = HealthReportDetailViewModel(SavedStateHandle(mapOf(GymRoutes.HEALTH_REPORT_ID_ARG to "delete")), dao, HealthRepository(db), docs)
        val collector = launch { vm.uiState.collect() }; vm.uiState.first { it.report?.syncId == "delete" }
        vm.deleteReport(false); vm.uiState.first { it.structuredDeleted || it.deletionError != null }
        assertTrue(dao.report("delete")!!.isTombstone); assertTrue(docs.deleted.isEmpty()); assertEquals(1, tableCount("health_sync_outbox")); collector.cancel()
    }

    @Test fun `explicit original deletion touches only linked READY document`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = db.healthDao(); dao.upsertReport(HealthReportEntity("delete2", 1, 1, false, "CONFIRMED", "LAB", 1, "CBC")); dao.upsertDocument(HealthDocumentEntity("linked", "delete2", "a", "READY", "a.pdf", "application/pdf", 1, null, 1))
        val docs = FakeDocuments(); val vm = HealthReportDetailViewModel(SavedStateHandle(mapOf(GymRoutes.HEALTH_REPORT_ID_ARG to "delete2")), dao, HealthRepository(db), docs); val c = launch { vm.uiState.collect() }
        vm.uiState.first { it.report?.syncId == "delete2" }; vm.deleteReport(true); vm.uiState.first { it.structuredDeleted || it.deletionError != null }
        assertTrue(dao.report("delete2")!!.isTombstone); assertEquals(listOf("linked"), docs.deleted); c.cancel()
    }

    @Test fun `original delete failure follows tombstone once and emits partial success`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = db.healthDao(); dao.upsertReport(HealthReportEntity("partial", 1, 1, false, "CONFIRMED", "LAB", 1, "CBC")); dao.upsertDocument(HealthDocumentEntity("linked", "partial", "a", "READY", "a.pdf", "application/pdf", 1, null, 1))
        val docs = FakeDocuments(fail = true); val vm = HealthReportDetailViewModel(SavedStateHandle(mapOf(GymRoutes.HEALTH_REPORT_ID_ARG to "partial")), dao, HealthRepository(db), docs); val c = launch { vm.uiState.collect() }
        vm.uiState.first { it.report?.syncId == "partial" }; vm.deleteReport(true)
        val event = vm.events.first() as HealthReportDeleteEvent.PartialFailure
        assertEquals("Запись удалена, но оригинал удалить не удалось", event.message); assertTrue(dao.report("partial")!!.isTombstone); assertEquals(1, tableCount("health_sync_outbox"))
        vm.deleteReport(true); testScheduler.advanceUntilIdle(); assertEquals(listOf("linked"), docs.deleted); c.cancel()
    }
    @Test fun `retry keeps report tombstone and retries only failed original metadata`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = db.healthDao(); dao.upsertReport(HealthReportEntity("retry", 1, 1, false, "CONFIRMED", "LAB", 1, "CBC")); dao.upsertDocument(HealthDocumentEntity("linked", "retry", "a", "READY", "a.pdf", "application/pdf", 1, null, 1))
        val docs = FakeDocuments(fail = true); val vm = HealthReportDetailViewModel(SavedStateHandle(mapOf(GymRoutes.HEALTH_REPORT_ID_ARG to "retry")), dao, HealthRepository(db), docs); val c = launch { vm.uiState.collect() }
        vm.uiState.first { it.report?.syncId == "retry" }; vm.deleteReport(true)
        vm.uiState.first { it.pendingOriginalDeletionIds == setOf("linked") }
        docs.fail = false; vm.retryOriginalDeletion()
        vm.uiState.first { it.structuredDeleted && it.pendingOriginalDeletionIds.isEmpty() }
        assertTrue(dao.report("retry")!!.isTombstone); assertEquals(listOf("linked", "linked"), docs.deleted); c.cancel()
    }
    @Test fun `DOCUMENT provenance reports missing original until linked READY document exists`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao=db.healthDao(); dao.upsertReport(HealthReportEntity("document",1,1,false,"CONFIRMED","DOCUMENT",1,"CBC")); dao.insertObservations(listOf(observation("o","document",1,"120","hb")))
        val vm=HealthReportDetailViewModel(SavedStateHandle(mapOf(GymRoutes.HEALTH_REPORT_ID_ARG to "document")),dao,HealthRepository(db),FakeDocuments()); val c=launch { vm.uiState.collect() }
        assertTrue(vm.uiState.first { it.report?.syncId=="document" }.missingOriginal)
        dao.upsertDocument(HealthDocumentEntity("ready","document","a","READY","a.pdf","application/pdf",1,null,1))
        assertFalse(vm.uiState.first { it.hasReadyOriginal }.missingOriginal); c.cancel()
    }

    @Test fun `detail exposes immutable versions under the same stable report identity`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val dao = db.healthDao()
        val v1 = HealthReportEntity("history", 1, 10, false, "CONFIRMED", "LAB", 10, "CBC")
        val v2 = v1.copy(version = 2, updatedAt = 20, status = "CORRECTED", title = "CBC corrected", supersedesVersion = 1)
        val first = observation("h1", "history", 10, "120", "hb")
        val corrected = first.copy(syncId = "h2", updatedAt = 20, rawValue = "125")
        dao.upsertReport(v2)
        dao.insertObservations(listOf(corrected))
        dao.insertReportSnapshot(HealthReportSnapshotEntity("history", 1, 10, false, HealthSyncPayloadCodec.report(v1, listOf(first)), "h1"))
        dao.insertReportSnapshot(HealthReportSnapshotEntity("history", 2, 20, false, HealthSyncPayloadCodec.report(v2, listOf(corrected)), "h2"))
        val vm = HealthReportDetailViewModel(SavedStateHandle(mapOf(GymRoutes.HEALTH_REPORT_ID_ARG to "history")), dao, HealthRepository(db), FakeDocuments())
        val collector = launch { vm.uiState.collect() }
        val state = vm.uiState.first { it.history.size == 2 }
        assertEquals(listOf(2L, 1L), state.history.map { it.version })
        val current = requireNotNull(state.report)
        assertEquals("history", current.syncId)
        assertEquals(2L, current.version)
        collector.cancel()
    }

    private class FakeDocuments(var fail: Boolean = false) : HealthDocumentRepository {
        val deleted = mutableListOf<String>()
        override suspend fun store(input: HealthDocumentInput, source: java.io.InputStream) = HealthDocumentStoreResult.Failure("unused")
        override suspend fun storeUri(input: HealthDocumentInput, uri: Uri) = HealthDocumentStoreResult.Failure("unused")
        override suspend fun hasReadyDocument(documentId: String) = false
        override suspend fun delete(documentId: String): Boolean { deleted += documentId; return !fail }
        override suspend fun recoverInterruptedCopies() = Unit
    }
}
