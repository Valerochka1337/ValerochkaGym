package com.valerochka1337.valerochkagym.ui

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.ai.InBodyReportAiReader
import com.valerochka1337.valerochkagym.data.ai.InBodyReportAiResult
import com.valerochka1337.valerochkagym.data.ai.InBodyReportDraft
import com.valerochka1337.valerochkagym.data.ai.MODEL_UNAVAILABLE_MESSAGE
import com.valerochka1337.valerochkagym.data.ai.AiApiConfigurationProvider
import com.valerochka1337.valerochkagym.data.ai.AiApiConnection
import com.valerochka1337.valerochkagym.data.ai.AiApiRequestConfiguration
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.MeasurementDocumentEntity
import com.valerochka1337.valerochkagym.data.measurements.MeasurementDocumentInput
import com.valerochka1337.valerochkagym.data.measurements.MeasurementDocumentRepository
import com.valerochka1337.valerochkagym.data.measurements.MeasurementDocumentStoreResult
import com.valerochka1337.valerochkagym.data.measurements.NoOpMeasurementDocumentRepository
import com.valerochka1337.valerochkagym.data.measurements.PendingInBodyCaptureRegistry
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegment
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegmentValues
import com.valerochka1337.valerochkagym.domain.measurements.effectiveWaistHipRatio
import com.valerochka1337.valerochkagym.ui.measurements.MeasurementEditorViewModel
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MeasurementEditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `saving a new measurement calculates WHR and schedules its upload`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val dao = FakeBodyMeasurementDao()
            val scheduler = FakeMeasurementUploadScheduler()
            val viewModel = MeasurementEditorViewModel(SavedStateHandle(), dao, scheduler)

            viewModel.setWaistCm("72")
            viewModel.setHipsCm("96")
            assertTrue(viewModel.uiState.value.canSave)
            assertEquals(0.75, viewModel.uiState.value.effectiveWaistHipRatio!!, 1e-6)

            viewModel.save()
            testScheduler.advanceUntilIdle()

            val stored = dao.inserted.single()
            assertNull(stored.waistHipRatio)
            assertEquals(0.75, stored.effectiveWaistHipRatio()!!, 1e-6)
            assertEquals(UploadStatus.PENDING, stored.uploadStatus)
            assertEquals(listOf(stored.id), scheduler.scheduled)
        }

    @Test
    fun `successful InBody scan fills only its draft and preserves manual circumferences`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val dao = FakeBodyMeasurementDao()
            val reader = FakeInBodyReportAiReader(
                InBodyReportAiResult.Success(
                    InBodyReportDraft(
                        measuredDate = LocalDate.of(2026, 7, 24),
                        measuredTime = LocalTime.of(18, 6),
                        weightKg = 59.5,
                        bodyFatMassKg = 14.8,
                        inBodyScore = 74,
                        segments = mapOf(
                            InBodySegment.LEFT_ARM to InBodySegmentValues(leanMassKg = 1.99, fatPercentage = 88.8),
                        ),
                    ),
                ),
            )
            val viewModel = MeasurementEditorViewModel(
                SavedStateHandle(),
                dao,
                FakeMeasurementUploadScheduler(),
                reader,
                FakeAiApiConfigurationProvider(configured = true),
            )
            testScheduler.advanceUntilIdle()
            viewModel.setWaistCm("72")
            viewModel.setChestCm("95")
            viewModel.setHipsCm("96")
            viewModel.setProteinKg("8.7")

            viewModel.scanInBody(Uri.parse("content://picker/inbody.jpg"))
            testScheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isScanningInBody)
            assertNull(state.inBodyScanError)
            assertEquals("59.5", state.weightKg)
            assertEquals("14.8", state.bodyFatMassKg)
            assertEquals("74", state.inBodyScore)
            assertEquals("1.99", state.segments.getValue(InBodySegment.LEFT_ARM).leanMassKg)
            assertEquals("72", state.waistCm)
            assertEquals("95", state.chestCm)
            assertEquals("96", state.hipsCm)
            assertEquals("8.7", state.proteinKg)
            assertEquals(
                LocalDate.of(2026, 7, 24),
                Instant.ofEpochMilli(state.measuredAt).atZone(ZoneId.systemDefault()).toLocalDate(),
            )
        }

    @Test
    fun `failed InBody scan preserves draft and allows another attempt`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val reader = FakeInBodyReportAiReader(
                InBodyReportAiResult.Failure("Не удалось прочитать лист"),
                InBodyReportAiResult.Success(InBodyReportDraft(weightKg = 60.0)),
            )
            val viewModel = MeasurementEditorViewModel(
                SavedStateHandle(),
                FakeBodyMeasurementDao(),
                FakeMeasurementUploadScheduler(),
                reader,
                FakeAiApiConfigurationProvider(configured = true),
            )
            testScheduler.advanceUntilIdle()
            viewModel.setWeightKg("70")
            viewModel.setWaistCm("72")

            viewModel.scanInBody(Uri.parse("content://picker/first.jpg"))
            testScheduler.advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isScanningInBody)
            assertEquals("Не удалось прочитать лист", viewModel.uiState.value.inBodyScanError)
            assertEquals("70", viewModel.uiState.value.weightKg)
            assertEquals("72", viewModel.uiState.value.waistCm)

            viewModel.scanInBody(Uri.parse("content://picker/second.jpg"))
            testScheduler.advanceUntilIdle()

            assertEquals("60.0", viewModel.uiState.value.weightKg)
            assertNull(viewModel.uiState.value.inBodyScanError)
            assertEquals(2, reader.uris.size)
        }

    @Test
    fun `failed InBody scan marks an unavailable selected model for settings navigation`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = MeasurementEditorViewModel(
                SavedStateHandle(),
                FakeBodyMeasurementDao(),
                FakeMeasurementUploadScheduler(),
                FakeInBodyReportAiReader(
                    InBodyReportAiResult.Failure(
                        message = MODEL_UNAVAILABLE_MESSAGE,
                        modelUnavailable = true,
                    ),
                ),
                FakeAiApiConfigurationProvider(configured = true),
            )
            testScheduler.advanceUntilIdle()

            viewModel.scanInBody(Uri.parse("content://picker/inbody.jpg"))
            testScheduler.advanceUntilIdle()

            assertTrue(viewModel.uiState.value.inBodyScanModelUnavailable)
        }

    @Test
    fun `saving a scanned report schedules one measurement upload`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val dao = FakeBodyMeasurementDao()
            val scheduler = FakeMeasurementUploadScheduler()
            val viewModel = MeasurementEditorViewModel(
                SavedStateHandle(),
                dao,
                scheduler,
                FakeInBodyReportAiReader(
                    InBodyReportAiResult.Success(
                        InBodyReportDraft(
                            weightKg = 59.5,
                            bodyFatMassKg = 14.8,
                            segments = mapOf(
                                InBodySegment.RIGHT_LEG to InBodySegmentValues(
                                    leanMassKg = 7.39,
                                    leanPercentage = 107.5,
                                    fatMassKg = 2.4,
                                    fatPercentage = 85.6,
                                ),
                            ),
                        ),
                    ),
                ),
                FakeAiApiConfigurationProvider(configured = true),
            )
            testScheduler.advanceUntilIdle()

            viewModel.scanInBody(Uri.parse("content://picker/inbody.jpg"))
            testScheduler.advanceUntilIdle()
            viewModel.save()
            testScheduler.advanceUntilIdle()

            val stored = dao.inserted.single()
            assertEquals(14.8, stored.bodyFatMassKg!!, 1e-6)
            assertEquals(7.39, stored.rightLegLeanMassKg!!, 1e-6)
            assertEquals(85.6, stored.rightLegFatPercentage!!, 1e-6)
            assertEquals(listOf(stored.id), scheduler.scheduled)
        }

    @Test
    fun `saving an AI report with a full-report value stores it locally`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val dao = FakeBodyMeasurementDao()
            val viewModel = MeasurementEditorViewModel(
                SavedStateHandle(),
                dao,
                FakeMeasurementUploadScheduler(),
                FakeInBodyReportAiReader(InBodyReportAiResult.Success(InBodyReportDraft(inBodyScore = 74))),
                FakeAiApiConfigurationProvider(configured = true),
            )
            testScheduler.advanceUntilIdle()

            viewModel.scanInBody(Uri.parse("content://picker/inbody.jpg"))
            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.uiState.value.canSave)

            viewModel.save()
            testScheduler.advanceUntilIdle()

            assertEquals(74, dao.inserted.single().inBodyScore)
        }

    @Test
    fun `failed measurement persistence keeps the draft and exposes a retryable error`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = MeasurementEditorViewModel(
                SavedStateHandle(),
                FakeBodyMeasurementDao(failOnInsert = true),
                FakeMeasurementUploadScheduler(),
            )
            viewModel.setInBodyScore("74")

            viewModel.save()
            testScheduler.advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isSaving)
            assertEquals("Не удалось сохранить замер — попробуйте ещё раз", viewModel.uiState.value.saveError)
            assertEquals("74", viewModel.uiState.value.inBodyScore)
        }

    @Test
    fun `editing an uploaded measurement creates a pending next snapshot`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val existing = BodyMeasurementEntity(
                id = "uploaded",
                measuredAt = 1_000,
                weightKg = 70.0,
                uploadStatus = UploadStatus.UPLOADED,
            )
            val dao = FakeBodyMeasurementDao(existing)
            val scheduler = FakeMeasurementUploadScheduler()
            val handle = SavedStateHandle(mapOf(GymRoutes.MEASUREMENT_ID_ARG to existing.id))
            val viewModel = MeasurementEditorViewModel(handle, dao, scheduler)
            testScheduler.advanceUntilIdle()

            viewModel.setWeightKg("69.5")
            viewModel.save()
            testScheduler.advanceUntilIdle()

            assertEquals(69.5, dao.updated.single().weightKg!!, 1e-6)
            assertEquals(UploadStatus.PENDING, dao.updated.single().uploadStatus)
            assertEquals(listOf("uploaded"), scheduler.scheduled)
        }

    @Test fun `editing loaded conditions keeps every flag and normalizes the updated note`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val existing = BodyMeasurementEntity(
                id = "conditions", measuredAt = 1_000, weightKg = 70.0,
                afterMeal = true, afterWorkout = true, unusualHydration = true, conditionNote = "утро",
            )
            val dao = FakeBodyMeasurementDao(existing)
            val vm = MeasurementEditorViewModel(
                SavedStateHandle(mapOf(GymRoutes.MEASUREMENT_ID_ARG to "conditions")),
                dao,
                FakeMeasurementUploadScheduler(),
            )
            testScheduler.advanceUntilIdle()
            assertTrue(vm.uiState.value.afterMeal)
            assertTrue(vm.uiState.value.afterWorkout)
            assertTrue(vm.uiState.value.unusualHydration)
            assertEquals("утро", vm.uiState.value.conditionNote)

            vm.setAfterWorkout(false)
            vm.setConditionNote("  после сна  ")
            vm.save()
            testScheduler.advanceUntilIdle()

            dao.updated.single().also {
                assertTrue(it.afterMeal)
                assertFalse(it.afterWorkout)
                assertTrue(it.unusualHydration)
                assertEquals("после сна", it.conditionNote)
            }
        }

    @Test
    fun `deleting an existing measurement removes only its local record`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val existing = BodyMeasurementEntity(id = "m1", measuredAt = 1_000, weightKg = 70.0)
            val dao = FakeBodyMeasurementDao(existing)
            val handle = SavedStateHandle(mapOf(GymRoutes.MEASUREMENT_ID_ARG to existing.id))
            val viewModel = MeasurementEditorViewModel(handle, dao, FakeMeasurementUploadScheduler())
            testScheduler.advanceUntilIdle()

            viewModel.delete()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("m1"), dao.deleted)
            assertFalse(dao.rows.value.any { it.id == "m1" })
        }

    @Test fun `conditions round trip through editor save and blank note becomes null`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val dao = FakeBodyMeasurementDao()
            val viewModel = MeasurementEditorViewModel(SavedStateHandle(), dao, FakeMeasurementUploadScheduler())
            viewModel.setWeightKg("70")
            viewModel.setAfterMeal(true)
            viewModel.setAfterWorkout(true)
            viewModel.setUnusualHydration(true)
            viewModel.setConditionNote("   ")

            viewModel.save()
            testScheduler.advanceUntilIdle()

            dao.inserted.single().also {
                assertTrue(it.afterMeal)
                assertTrue(it.afterWorkout)
                assertTrue(it.unusualHydration)
                assertNull(it.conditionNote)
            }
        }

    @Test fun `local original opt in retries copy without another measurement write or upload`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val dao = FakeBodyMeasurementDao()
            val scheduler = FakeMeasurementUploadScheduler()
            val documents = FakeMeasurementDocuments(
                MeasurementDocumentStoreResult.Failure("disk"),
                MeasurementDocumentStoreResult.Ready("doc", "hash", 1),
            )
            val viewModel = MeasurementEditorViewModel(
                SavedStateHandle(), dao, scheduler,
                FakeInBodyReportAiReader(InBodyReportAiResult.Success(InBodyReportDraft(weightKg = 70.0))),
                FakeAiApiConfigurationProvider(configured = true),
                measurementDocumentRepository = documents,
            )
            testScheduler.advanceUntilIdle()
            viewModel.scanInBody(Uri.parse("content://picker/original.jpg"))
            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.uiState.value.originalAvailable)
            viewModel.setRetainOriginal(true)

            viewModel.save()
            testScheduler.advanceUntilIdle()
            assertEquals("Замер сохранён, оригинал не удалось сохранить", viewModel.uiState.value.saveError)
            assertEquals(1, dao.inserted.size)
            assertEquals(1, scheduler.scheduled.size)

            viewModel.save()
            testScheduler.advanceUntilIdle()
            assertEquals(2, documents.storeUris.size)
            assertEquals(1, dao.inserted.size)
            assertEquals(1, scheduler.scheduled.size)
        }

    @Test fun `editing after original copy failure saves a new measurement revision before retry`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val dao = FakeBodyMeasurementDao()
            val scheduler = FakeMeasurementUploadScheduler()
            val documents = FakeMeasurementDocuments(
                MeasurementDocumentStoreResult.Failure("disk"),
                MeasurementDocumentStoreResult.Ready("doc", "hash", 1),
            )
            val viewModel = MeasurementEditorViewModel(
                SavedStateHandle(), dao, scheduler,
                FakeInBodyReportAiReader(InBodyReportAiResult.Success(InBodyReportDraft(weightKg = 70.0))),
                FakeAiApiConfigurationProvider(configured = true),
                measurementDocumentRepository = documents,
            )
            testScheduler.advanceUntilIdle()
            viewModel.scanInBody(Uri.parse("content://picker/original.jpg"))
            testScheduler.advanceUntilIdle()
            viewModel.setRetainOriginal(true)
            viewModel.save()
            testScheduler.advanceUntilIdle()

            viewModel.setWeightKg("71")
            viewModel.save()
            testScheduler.advanceUntilIdle()

            assertEquals(70.0, dao.inserted.single().weightKg!!, 1e-6)
            assertEquals(71.0, dao.updated.single().weightKg!!, 1e-6)
            assertEquals(2, scheduler.scheduled.size)
            assertEquals(2, documents.storeUris.size)
        }

    @Test fun `turning original retention off after copy failure finishes without another write`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val dao = FakeBodyMeasurementDao()
            val scheduler = FakeMeasurementUploadScheduler()
            val documents = FakeMeasurementDocuments(MeasurementDocumentStoreResult.Failure("disk"))
            val viewModel = MeasurementEditorViewModel(
                SavedStateHandle(), dao, scheduler,
                FakeInBodyReportAiReader(InBodyReportAiResult.Success(InBodyReportDraft(weightKg = 70.0))),
                FakeAiApiConfigurationProvider(configured = true),
                measurementDocumentRepository = documents,
            )
            testScheduler.advanceUntilIdle()
            viewModel.scanInBody(Uri.parse("content://picker/original.jpg"))
            testScheduler.advanceUntilIdle()
            viewModel.setRetainOriginal(true)
            viewModel.save()
            testScheduler.advanceUntilIdle()

            viewModel.setRetainOriginal(false)
            testScheduler.advanceUntilIdle()

            assertEquals(1, dao.inserted.size)
            assertEquals(1, scheduler.scheduled.size)
            assertEquals(1, documents.storeUris.size)
        }

    @Test fun `confirmed InBody request keeps the exact disclosed configuration`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val disclosed = AiApiRequestConfiguration(AiApiConnection("https://disclosed.example/v1/", "secret-a"), "model-a")
            val changed = AiApiRequestConfiguration(AiApiConnection("https://changed.example/v1/", "secret-b"), "model-b")
            val provider = FakeAiApiConfigurationProvider(configured = true, configuration = disclosed)
            val reader = FakeInBodyReportAiReader(InBodyReportAiResult.Success(InBodyReportDraft(weightKg = 70.0)))
            val viewModel = MeasurementEditorViewModel(
                SavedStateHandle(), FakeBodyMeasurementDao(), FakeMeasurementUploadScheduler(), reader, provider,
            )
            testScheduler.advanceUntilIdle()

            viewModel.requestInBodyConsent(Uri.parse("content://picker/original.jpg"))
            testScheduler.advanceUntilIdle()
            assertEquals("disclosed.example", viewModel.uiState.value.scanDisclosure?.host)
            provider.configuration = changed
            viewModel.confirmInBodyConsent()
            testScheduler.advanceUntilIdle()

            assertEquals(disclosed, reader.disclosedConfigurations.single())
        }

    @Test fun `scan opt out never stores original and camera sources are cleaned on failure replacement and discard`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val documents = FakeMeasurementDocuments()
            val reader = FakeInBodyReportAiReader(
                InBodyReportAiResult.Failure("bad"),
                InBodyReportAiResult.Success(InBodyReportDraft(weightKg = 70.0)),
                InBodyReportAiResult.Success(InBodyReportDraft(weightKg = 69.0)),
            )
            val viewModel = MeasurementEditorViewModel(
                SavedStateHandle(), FakeBodyMeasurementDao(), FakeMeasurementUploadScheduler(), reader,
                FakeAiApiConfigurationProvider(configured = true), measurementDocumentRepository = documents,
            )
            testScheduler.advanceUntilIdle()
            val failed = File.createTempFile("inbody-failed", ".jpg").also { it.writeText("x") }
            viewModel.scanInBody(Uri.parse("content://camera/failed"), failed)
            testScheduler.advanceUntilIdle()
            assertFalse(failed.exists())
            val first = File.createTempFile("inbody-first", ".jpg").also { it.writeText("x") }
            viewModel.scanInBody(Uri.parse("content://camera/first"), first)
            testScheduler.advanceUntilIdle()
            val second = File.createTempFile("inbody-second", ".jpg").also { it.writeText("x") }
            viewModel.scanInBody(Uri.parse("content://camera/second"), second)
            testScheduler.advanceUntilIdle()
            assertFalse(first.exists())
            viewModel.setRetainOriginal(false)
            viewModel.save()
            testScheduler.advanceUntilIdle()
            assertTrue(documents.storeUris.isEmpty())
            assertFalse(second.exists())
        }

    @Test fun `camera capture token survives recreation without saving a path or URI`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val handle = SavedStateHandle()
            val first = MeasurementEditorViewModel(handle, FakeBodyMeasurementDao(), FakeMeasurementUploadScheduler())
            val token = first.beginCameraCapture()

            val recreated = MeasurementEditorViewModel(handle, FakeBodyMeasurementDao(), FakeMeasurementUploadScheduler())

            assertEquals(token, recreated.uiState.value.pendingCameraToken)
            assertEquals(token, recreated.consumeCameraCapture())
            assertNull(recreated.uiState.value.pendingCameraToken)
            assertFalse(token.contains("content:"))
            assertFalse(token.contains('/'))
        }

    @Test fun `consuming or abandoning camera capture clears its durable marker and private file`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val registry = PendingInBodyCaptureRegistry(context)
            val vm = MeasurementEditorViewModel(
                SavedStateHandle(), FakeBodyMeasurementDao(), FakeMeasurementUploadScheduler(),
                applicationContext = context, captureRegistry = registry,
            )
            val directory = File(context.cacheDir, "inbody_imports").apply { mkdirs() }
            val consumed = vm.beginCameraCapture()
            File(directory, consumed).writeText("private")
            assertEquals(consumed, vm.consumeCameraCapture())
            registry.recoverCameraImports(directory)
            assertFalse(File(directory, consumed).exists())

            val abandoned = vm.beginCameraCapture()
            File(directory, abandoned).writeText("private")
            vm.clearCameraCapture()
            assertFalse(File(directory, abandoned).exists())
        }

    @Test fun `unchanged existing save creates no snapshot upload or duplicate write`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val existing = BodyMeasurementEntity(id = "same", measuredAt = 1_000, weightKg = 70.0)
            val dao = FakeBodyMeasurementDao(existing)
            val scheduler = FakeMeasurementUploadScheduler()
            val vm = MeasurementEditorViewModel(
                SavedStateHandle(mapOf(GymRoutes.MEASUREMENT_ID_ARG to existing.id)), dao, scheduler,
            )
            testScheduler.advanceUntilIdle()

            vm.save()
            testScheduler.advanceUntilIdle()

            assertTrue(dao.inserted.isEmpty())
            assertTrue(dao.updated.isEmpty())
            assertTrue(scheduler.scheduled.isEmpty())
        }

    @Test fun `unchanged existing save with opted original stores only local original`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val existing = BodyMeasurementEntity(id = "same", measuredAt = 1_000, weightKg = 70.0)
            val dao = FakeBodyMeasurementDao(existing)
            val scheduler = FakeMeasurementUploadScheduler()
            val documents = FakeMeasurementDocuments(MeasurementDocumentStoreResult.Ready("doc", "hash", 1))
            val vm = MeasurementEditorViewModel(
                SavedStateHandle(mapOf(GymRoutes.MEASUREMENT_ID_ARG to existing.id)), dao, scheduler,
                FakeInBodyReportAiReader(InBodyReportAiResult.Success(InBodyReportDraft(weightKg = 70.0))),
                FakeAiApiConfigurationProvider(configured = true),
                measurementDocumentRepository = documents,
            )
            testScheduler.advanceUntilIdle()
            vm.scanInBody(Uri.parse("content://picker/original.jpg"))
            testScheduler.advanceUntilIdle()
            vm.setRetainOriginal(true)

            vm.save()
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(Uri.parse("content://picker/original.jpg")), documents.storeUris)
            assertTrue(dao.updated.isEmpty())
            assertTrue(scheduler.scheduled.isEmpty())
        }

    @Test fun `cancelling InBody consent and clearing view model remove owned camera temporary files`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val configuration = AiApiRequestConfiguration(AiApiConnection("https://safe.example", "key"), "model")
            val reader = FakeInBodyReportAiReader(InBodyReportAiResult.Success(InBodyReportDraft(weightKg = 70.0)))
            val vm = MeasurementEditorViewModel(
                SavedStateHandle(), FakeBodyMeasurementDao(), FakeMeasurementUploadScheduler(),
                reader,
                FakeAiApiConfigurationProvider(configured = true, configuration = configuration),
            )
            testScheduler.advanceUntilIdle()
            val cancelled = File.createTempFile("inbody-consent", ".jpg").also { it.writeText("private") }
            vm.requestInBodyConsent(Uri.parse("content://camera/cancel"), cancelled)
            testScheduler.advanceUntilIdle()
            vm.cancelInBodyConsent()
            assertFalse(cancelled.exists())
            assertTrue(reader.uris.isEmpty())

            val cleared = File.createTempFile("inbody-cleared", ".jpg").also { it.writeText("private") }
            vm.scanInBody(Uri.parse("content://camera/clear"), cleared)
            testScheduler.advanceUntilIdle()
            MeasurementEditorViewModel::class.java.getDeclaredMethod("onCleared").apply {
                isAccessible = true
                invoke(vm)
            }
            assertFalse(cleared.exists())
        }

    @Test fun `ready original count is rechecked after successful and failed removal`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val existing = BodyMeasurementEntity(id = "ready", measuredAt = 1_000, weightKg = 70.0)
            val documents = FakeMeasurementDocuments().apply {
                ready += readyDocument("first", "ready")
                ready += readyDocument("second", "ready")
            }
            val vm = MeasurementEditorViewModel(
                SavedStateHandle(mapOf(GymRoutes.MEASUREMENT_ID_ARG to existing.id)),
                FakeBodyMeasurementDao(existing), FakeMeasurementUploadScheduler(),
                measurementDocumentRepository = documents,
            )
            testScheduler.advanceUntilIdle()
            assertEquals(2, vm.uiState.value.readyOriginalCount)

            vm.removeReadyOriginals()
            testScheduler.advanceUntilIdle()
            assertEquals(0, vm.uiState.value.readyOriginalCount)

            documents.ready += readyDocument("third", "ready")
            documents.deleteFailure = true
            vm.removeReadyOriginals()
            testScheduler.advanceUntilIdle()
            assertEquals(1, vm.uiState.value.readyOriginalCount)
            assertEquals("Не удалось удалить локальные оригиналы", vm.uiState.value.saveError)
        }

    private class FakeInBodyReportAiReader(vararg initial: InBodyReportAiResult) : InBodyReportAiReader {
        private val results = ArrayDeque(initial.toList())
        val uris = mutableListOf<Uri>()
        val disclosedConfigurations = mutableListOf<AiApiRequestConfiguration>()

        override suspend fun read(uri: Uri): InBodyReportAiResult {
            uris += uri
            return results.removeFirst()
        }

        override suspend fun read(
            uri: Uri,
            configuration: AiApiRequestConfiguration,
            loopbackHttpConsent: Boolean,
        ): InBodyReportAiResult {
            disclosedConfigurations += configuration
            return read(uri)
        }
    }

    private class FakeAiApiConfigurationProvider(
        configured: Boolean,
        var configuration: AiApiRequestConfiguration? = null,
    ) : AiApiConfigurationProvider {
        override val isConfigured: Flow<Boolean> = flowOf(configured)
        override suspend fun connection() = null
        override suspend fun requestConfiguration() = configuration
    }

    private class FakeBodyMeasurementDao(
        vararg initial: BodyMeasurementEntity,
        private val failOnInsert: Boolean = false,
    ) : BodyMeasurementDao {
        val rows = MutableStateFlow(initial.toList())
        val inserted = mutableListOf<BodyMeasurementEntity>()
        val updated = mutableListOf<BodyMeasurementEntity>()
        val deleted = mutableListOf<String>()

        override suspend fun insert(measurement: BodyMeasurementEntity) {
            if (failOnInsert) error("Room write failed")
            inserted += measurement
            rows.value = rows.value + measurement
        }

        override suspend fun update(measurement: BodyMeasurementEntity) {
            updated += measurement
            rows.value = rows.value.map { if (it.id == measurement.id) measurement else it }
        }

        override fun observeAll(): Flow<List<BodyMeasurementEntity>> = rows

        override suspend fun getById(id: String): BodyMeasurementEntity? = rows.value.find { it.id == id }

        override suspend fun setUploadStatus(measurementId: String, status: UploadStatus, error: String?) = Unit

        override suspend fun getNotUploaded(): List<String> = emptyList()

        override suspend fun delete(id: String) {
            deleted += id
            rows.value = rows.value.filterNot { it.id == id }
        }
    }

    private class FakeMeasurementUploadScheduler : MeasurementUploadScheduler {
        val scheduled = mutableListOf<String>()
        override suspend fun schedule(measurementId: String) {
            scheduled += measurementId
        }

        override suspend fun retry(measurementId: String) = Unit
        override suspend fun scheduleAllPending(): Int = 0
    }

    private class FakeMeasurementDocuments(
        vararg results: MeasurementDocumentStoreResult,
    ) : MeasurementDocumentRepository by NoOpMeasurementDocumentRepository {
        private val queued = ArrayDeque(results.toList())
        val storeUris = mutableListOf<Uri>()
        val ready = mutableListOf<MeasurementDocumentEntity>()
        var deleteFailure = false
        override suspend fun storeUri(input: MeasurementDocumentInput, uri: Uri): MeasurementDocumentStoreResult {
            storeUris += uri
            return (queued.removeFirstOrNull() ?: MeasurementDocumentStoreResult.Ready("doc", "hash", 1)).also { result ->
                if (result is MeasurementDocumentStoreResult.Ready) ready += document(result.documentId, input.measurementId)
            }
        }
        override suspend fun readyForMeasurement(measurementId: String): List<MeasurementDocumentEntity> =
            ready.filter { it.measurementId == measurementId }
        override suspend fun deleteAll(measurementId: String): Int {
            if (deleteFailure) error("disk")
            val count = ready.count { it.measurementId == measurementId }
            ready.removeAll { it.measurementId == measurementId }
            return count
        }

        private fun document(id: String, measurementId: String) = MeasurementDocumentEntity(
            id = id, measurementId = measurementId, sha256 = "hash-$id", state = "READY",
            displayName = "$id.jpg", mimeType = "image/jpeg", byteSize = 1, createdAt = 1,
        )
    }

    private fun readyDocument(id: String, measurementId: String) = MeasurementDocumentEntity(
        id = id, measurementId = measurementId, sha256 = "hash-$id", state = "READY",
        displayName = "$id.jpg", mimeType = "image/jpeg", byteSize = 1, createdAt = 1,
    )
}
