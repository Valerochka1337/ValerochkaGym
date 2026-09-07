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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
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
    fun `reading blocks duplicate reads and saves until the document result returns`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val reader = SuspendingReader()
            val vm = HealthEditorViewModel(HealthRepository(db), reader, FakeConfiguration(), FailingDocuments(), SavedStateHandle())
            vm.title("CBC")
            vm.provenance("LAB")
            vm.updateObservation(0, HealthObservationInput("Hb", "120"))

            vm.readDocument(Uri.parse("content://first"), false)
            testScheduler.runCurrent()
            vm.readDocument(Uri.parse("content://second"), false)
            vm.save()
            testScheduler.runCurrent()

            assertTrue(vm.uiState.value.reading)
            assertEquals(1, reader.calls)
            assertEquals(0, tableCount("health_reports"))
            reader.result.complete(FakeReader().result)
            testScheduler.advanceUntilIdle()
            assertFalse(vm.uiState.value.reading)
        }

    @Test
    fun `replacement cancellation and failure preserve the previous original URI`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val reader = MutableResultReader()
            val vm = HealthEditorViewModel(HealthRepository(db), reader, FakeConfiguration(), FailingDocuments(), SavedStateHandle())
            val originalUri = Uri.parse("content://original")
            vm.readDocument(originalUri, false)
            testScheduler.advanceUntilIdle()

            vm.selectDocument(Uri.parse("content://replacement"))
            testScheduler.advanceUntilIdle()
            vm.cancelDisclosure()
            assertEquals(originalUri, vm.uiState.value.pendingUri)
            assertEquals("CBC", vm.uiState.value.title)

            reader.result = HealthReportAiResult.Failure("Не удалось прочитать")
            vm.readDocument(Uri.parse("content://failed"), false)
            testScheduler.advanceUntilIdle()
            assertEquals(originalUri, vm.uiState.value.pendingUri)
            assertEquals("CBC", vm.uiState.value.title)
        }

    @Test
    fun `saving an original uses the URI from the last successful document read`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val originalUri = Uri.parse("content://original")
            val failedReplacementUri = Uri.parse("content://failed-replacement")
            val documentsAfterFailure = RecordingDocuments()
            val failedReader = MutableResultReader()
            val failedVm = HealthEditorViewModel(
                HealthRepository(db), failedReader, FakeConfiguration(), documentsAfterFailure, SavedStateHandle(),
            )
            failedVm.readDocument(originalUri, false)
            testScheduler.advanceUntilIdle()
            failedVm.retainOriginal(true)
            failedVm.selectDocument(Uri.parse("content://cancelled-replacement"))
            testScheduler.advanceUntilIdle()
            failedVm.cancelDisclosure()
            failedReader.result = HealthReportAiResult.Failure("Не удалось прочитать")
            failedVm.readDocument(failedReplacementUri, false)
            testScheduler.advanceUntilIdle()
            val failedSave = async { failedVm.finished.first() }
            failedVm.save()
            testScheduler.advanceUntilIdle()
            failedSave.await()
            assertEquals(listOf(originalUri), documentsAfterFailure.storedUris)

            val replacementUri = Uri.parse("content://successful-replacement")
            val documentsAfterSuccess = RecordingDocuments()
            val successVm = HealthEditorViewModel(
                HealthRepository(db), MutableResultReader(), FakeConfiguration(), documentsAfterSuccess, SavedStateHandle(),
            )
            successVm.readDocument(originalUri, false)
            testScheduler.advanceUntilIdle()
            successVm.retainOriginal(true)
            successVm.selectDocument(replacementUri)
            testScheduler.advanceUntilIdle()
            successVm.confirmDisclosure()
            testScheduler.advanceUntilIdle()
            val successfulSave = async { successVm.finished.first() }
            successVm.save()
            testScheduler.advanceUntilIdle()
            successfulSave.await()
            assertEquals(listOf(replacementUri), documentsAfterSuccess.storedUris)
        }

    @Test
    fun `cancelled reading clears the reading state`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val vm = HealthEditorViewModel(
                HealthRepository(db),
                CancellingReader(),
                FakeConfiguration(),
                FailingDocuments(),
                SavedStateHandle(),
            )
            vm.readDocument(Uri.parse("content://cancelled"), false)
            testScheduler.advanceUntilIdle()

            assertFalse(vm.uiState.value.reading)
            assertEquals(null, vm.uiState.value.pendingUri)
        }

    @Test
    fun `public HTTP disclosure freezes its recipient and waits for confirmation`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val reader = RecordingReader()
            val configuration = AiApiRequestConfiguration(
                AiApiConnection("http://medical.example/v1/", "key"),
                "frozen-model",
            )
            val configurationProvider = FakeConfiguration(configuration)
            val vm = HealthEditorViewModel(
                HealthRepository(db),
                reader,
                configurationProvider,
                FailingDocuments(),
                SavedStateHandle(),
            )

            vm.selectDocument(Uri.parse("content://report"))
            testScheduler.advanceUntilIdle()

            val disclosure = requireNotNull(vm.uiState.value.disclosure)
            assertEquals("medical.example", disclosure.host)
            assertEquals("frozen-model", disclosure.model)
            assertTrue(disclosure.httpWarning)
            assertFalse(disclosure.loopbackWarning)
            assertEquals(0, reader.calls)
            vm.save()
            testScheduler.advanceUntilIdle()
            assertEquals(0, tableCount("health_reports"))

            configurationProvider.configuration = AiApiRequestConfiguration(
                AiApiConnection("https://replacement.example/v1/", "replacement-key"),
                "replacement-model",
            )
            vm.confirmDisclosure()
            testScheduler.advanceUntilIdle()
            assertEquals(1, reader.calls)
            assertEquals(configuration, reader.disclosedConfiguration)
        }

    @Test
    fun `cancelling a disclosure does not start a document read`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val reader = RecordingReader()
            val vm = HealthEditorViewModel(
                HealthRepository(db), reader, FakeConfiguration(), FailingDocuments(), SavedStateHandle(),
            )

            vm.selectDocument(Uri.parse("content://report"))
            testScheduler.advanceUntilIdle()
            assertEquals(0, reader.calls)
            vm.cancelDisclosure()
            assertEquals(0, reader.calls)
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
                ), note = "keep", originalExpected = true), 100,
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
            assertTrue(vm.uiState.value.retainOriginal)
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
            assertTrue(current.originalExpected)
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
        val result = HealthReportAiResult.Success(
            HealthReportAiDraft(
                HealthReportDraft("CBC", "LAB", 1, listOf(
                    HealthObservationDraft("Hb", HealthRawValue.Number(120.0, "120"), observedAt = 1),
                    HealthObservationDraft("Note", HealthRawValue.Text("ok"), observedAt = 1),
                )),
                listOf(7, 8),
            ),
        )

        override suspend fun read(uri: Uri, loopbackHttpConsent: Boolean): HealthReportAiResult = result
    }

    private class SuspendingReader : HealthReportAiReader {
        var calls = 0
        val result = CompletableDeferred<HealthReportAiResult>()

        override suspend fun read(uri: Uri, loopbackHttpConsent: Boolean): HealthReportAiResult {
            calls++
            return result.await()
        }
    }

    private class MutableResultReader : HealthReportAiReader {
        var result: HealthReportAiResult = FakeReader().result

        override suspend fun read(uri: Uri, loopbackHttpConsent: Boolean): HealthReportAiResult = result
    }

    private class CancellingReader : HealthReportAiReader {
        override suspend fun read(uri: Uri, loopbackHttpConsent: Boolean): HealthReportAiResult {
            throw CancellationException()
        }
    }

    private class RecordingReader : HealthReportAiReader {
        var calls = 0
        var disclosedConfiguration: AiApiRequestConfiguration? = null

        override suspend fun read(uri: Uri, loopbackHttpConsent: Boolean): HealthReportAiResult {
            calls++
            return FakeReader().result
        }

        override suspend fun read(
            uri: Uri,
            configuration: AiApiRequestConfiguration,
            loopbackHttpConsent: Boolean,
        ): HealthReportAiResult {
            calls++
            disclosedConfiguration = configuration
            return FakeReader().result
        }
    }

    private class FakeConfiguration(
        var configuration: AiApiRequestConfiguration = AiApiRequestConfiguration(
            AiApiConnection("https://example.test/v1/", "key"),
            "model",
        ),
    ) : AiApiConfigurationProvider {
        override val isConfigured: Flow<Boolean> = flowOf(true)
        override suspend fun connection() = configuration.connection
        override suspend fun requestConfiguration() = configuration
    }
    private class FailingDocuments : HealthDocumentRepository {
        override suspend fun store(input: HealthDocumentInput, source: java.io.InputStream) = HealthDocumentStoreResult.Failure("no")
        override suspend fun storeUri(input: HealthDocumentInput, uri: Uri) = HealthDocumentStoreResult.Failure("no")
        override suspend fun hasReadyDocument(documentId: String) = false
        override suspend fun delete(documentId: String) = false
        override suspend fun recoverInterruptedCopies() = Unit
    }

    private class RecordingDocuments : HealthDocumentRepository {
        val storedUris = mutableListOf<Uri>()

        override suspend fun store(input: HealthDocumentInput, source: java.io.InputStream): HealthDocumentStoreResult =
            HealthDocumentStoreResult.Ready("unused", "unused", 0)

        override suspend fun storeUri(input: HealthDocumentInput, uri: Uri): HealthDocumentStoreResult {
            storedUris += uri
            return HealthDocumentStoreResult.Ready("document", "hash", 1)
        }

        override suspend fun hasReadyDocument(documentId: String) = false
        override suspend fun delete(documentId: String) = false
        override suspend fun recoverInterruptedCopies() = Unit
    }
}
