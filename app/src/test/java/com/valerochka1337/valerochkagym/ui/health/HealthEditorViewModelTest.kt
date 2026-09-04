package com.valerochka1337.valerochkagym.ui.health

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.ai.AiApiConfigurationProvider
import com.valerochka1337.valerochkagym.data.ai.AiApiConnection
import com.valerochka1337.valerochkagym.data.ai.AiApiRequestConfiguration
import com.valerochka1337.valerochkagym.data.ai.HealthReportAiDraft
import com.valerochka1337.valerochkagym.data.ai.HealthReportAiReader
import com.valerochka1337.valerochkagym.data.ai.HealthReportAiResult
import com.valerochka1337.valerochkagym.data.health.HealthDocumentInput
import com.valerochka1337.valerochkagym.data.health.HealthDocumentRepository
import com.valerochka1337.valerochkagym.data.health.HealthDocumentStoreResult
import com.valerochka1337.valerochkagym.data.health.HealthRepository
import com.valerochka1337.valerochkagym.domain.health.HealthObservationDraft
import com.valerochka1337.valerochkagym.domain.health.HealthRawValue
import com.valerochka1337.valerochkagym.domain.health.HealthRawValueKind
import com.valerochka1337.valerochkagym.domain.health.HealthReportDraft
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthEditorViewModelTest : RoomDaoTest() {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `AI draft remains editable and only included rows persist on explicit save`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val reader = FakeReader()
            val vm = HealthEditorViewModel(HealthRepository(db), reader, FakeConfiguration(), FailingDocuments(), SavedStateHandle())
            vm.readDocument(Uri.parse("content://report"), false)
            testScheduler.advanceUntilIdle()
            assertEquals(0, tableCount("health_reports"))
            assertEquals(7, vm.uiState.value.observations.first().sourcePage)

            vm.updateObservation(1, vm.uiState.value.observations[1].copy(included = false))
            vm.save()
            testScheduler.advanceUntilIdle()
            assertEquals(null, vm.uiState.value.error)
            val report = db.healthDao().observeLiveReports().first().single()
            assertEquals(1, db.healthDao().observations(report.syncId).size)
            assertEquals(7, db.healthDao().observations(report.syncId).single().sourcePage)
        }

    @Test
    fun `cancelled disclosure makes no write and later explicit save keeps report`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val vm = HealthEditorViewModel(HealthRepository(db), FakeReader(), FakeConfiguration(), FailingDocuments(), SavedStateHandle())
            vm.selectDocument(Uri.parse("content://report"))
            testScheduler.advanceUntilIdle()
            vm.cancelDisclosure()
            assertEquals(0, tableCount("health_reports"))

            vm.title("CBC")
            vm.provenance("LAB")
            vm.updateObservation(0, HealthObservationInput("Hb", "120"))
            vm.save()
            testScheduler.advanceUntilIdle()
            assertEquals(1, db.healthDao().observeLiveReports().first().size)
            assertFalse(vm.uiState.value.saving)
        }

    @Test
    fun `correction prefill preserves all raw kinds metadata dates and stable report identity`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val repository = HealthRepository(db)
            val original = repository.saveConfirmedReport(
                HealthReportDraft("Panel", "LAB", 111, listOf(
                    HealthObservationDraft("n", HealthRawValue.Number(1.2, "1.20"), "u", "r", "m", "mat", "src", "n", 11, 2),
                    HealthObservationDraft("op", HealthRawValue.NumberWithOperator(">", 2.0), observedAt = 12),
                    HealthObservationDraft("range", HealthRawValue.Range(null, null, "1-2"), observedAt = 13),
                    HealthObservationDraft("cat", HealthRawValue.Category("positive"), observedAt = 14),
                    HealthObservationDraft("code", HealthRawValue.Code("A12"), observedAt = 15),
                    HealthObservationDraft("text", HealthRawValue.Text("exact note"), observedAt = 16),
                ), note = "keep"), 100,
            )
            val vm = HealthEditorViewModel(repository, FakeReader(), FakeConfiguration(), FailingDocuments(), SavedStateHandle(mapOf("correctsSyncId" to original.syncId)))
            val prefilled = async { vm.uiState.first { it.observations.size == 6 } }
            testScheduler.advanceUntilIdle(); prefilled.await()
            assertEquals(6, db.healthDao().observations(original.syncId).size)
            assertEquals(listOf("NUMBER", "NUMBER_WITH_OPERATOR", "RANGE", "CATEGORY", "CODE", "TEXT"), db.healthDao().observations(original.syncId).map { it.valueType })
            assertEquals(6, repository.correctionDraft(original.syncId)!!.observations.size)
            assertEquals(listOf("1.20", ">2.0", "1-2", "positive", "A12", "exact note"), vm.uiState.value.observations.map { it.rawValue })
            assertEquals(listOf(HealthRawValueKind.NUMBER, HealthRawValueKind.NUMBER_WITH_OPERATOR, HealthRawValueKind.RANGE, HealthRawValueKind.CATEGORY, HealthRawValueKind.CODE, HealthRawValueKind.TEXT), vm.uiState.value.observations.map { it.valueKind })
            assertEquals(111L, vm.uiState.value.reportedAt)
            assertEquals(2, vm.uiState.value.observations.first().sourcePage)
            assertEquals(original.syncId, vm.uiState.value.correctsSyncId)
            val completed = async { vm.finished.first() }
            vm.save(); testScheduler.advanceUntilIdle(); completed.await()
            assertEquals(null, vm.uiState.value.error)
            assertEquals(1, tableCount("health_reports"))
            val current = db.healthDao().report(original.syncId)!!
            assertEquals(2L, current.version)
            assertEquals(1L, current.supersedesVersion)
            assertEquals(listOf(2L, 1L), db.healthDao().reportSnapshots(original.syncId).map { it.version })
            assertEquals(listOf("CATEGORY", "CODE", "NUMBER", "NUMBER_WITH_OPERATOR", "RANGE", "TEXT"), db.healthDao().observations(original.syncId).map { it.valueType }.sorted())
            assertEquals(2, db.healthDao().observations(original.syncId).single { it.rawName == "n" }.sourcePage)
        }

    @Test
    fun `correction recreation replays one operation with stable observation identities`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val repository = HealthRepository(db)
            val original = repository.saveConfirmedReport(
                HealthReportDraft("Panel", "LAB", 1, listOf(
                    HealthObservationDraft("Hb", HealthRawValue.Number(120.0, "120"), observedAt = 1),
                )), 1,
            )
            val handle = SavedStateHandle(mapOf("correctsSyncId" to original.syncId))
            val first = HealthEditorViewModel(repository, FakeReader(), FakeConfiguration(), FailingDocuments(), handle)
            first.uiState.first { it.observations.single().observationSyncId != null }
            first.save(); first.finished.first()
            val firstObservationId = db.healthDao().observations(original.syncId).single().syncId

            val recreated = HealthEditorViewModel(repository, FakeReader(), FakeConfiguration(), FailingDocuments(), handle)
            recreated.uiState.first { it.observations.single().observationSyncId == firstObservationId }
            recreated.save(); recreated.finished.first()

            assertEquals(listOf(2L, 1L), db.healthDao().reportSnapshots(original.syncId).map { it.version })
            assertEquals(firstObservationId, db.healthDao().observations(original.syncId).single().syncId)
        }

    private class FakeReader : HealthReportAiReader {
        override suspend fun read(uri: Uri, loopbackHttpConsent: Boolean): HealthReportAiResult = HealthReportAiResult.Success(
            HealthReportAiDraft(
                HealthReportDraft("CBC", "LAB", 1, listOf(
                    HealthObservationDraft("Hb", HealthRawValue.Number(120.0, "120"), observedAt = 1),
                    HealthObservationDraft("Note", HealthRawValue.Text("ok"), observedAt = 1),
                )),
                listOf(7, 8),
            ),
        )
    }
    private class FakeConfiguration : AiApiConfigurationProvider {
        override val isConfigured: Flow<Boolean> = flowOf(true)
        override suspend fun connection() = AiApiConnection("https://example.test/v1/", "key")
        override suspend fun requestConfiguration() = AiApiRequestConfiguration(connection(), "model")
    }
    private class FailingDocuments : HealthDocumentRepository {
        override suspend fun store(input: HealthDocumentInput, source: java.io.InputStream) = HealthDocumentStoreResult.Failure("no")
        override suspend fun storeUri(input: HealthDocumentInput, uri: Uri) = HealthDocumentStoreResult.Failure("no")
        override suspend fun hasReadyDocument(documentId: String) = false
        override suspend fun delete(documentId: String) = false
        override suspend fun recoverInterruptedCopies() = Unit
    }
}
