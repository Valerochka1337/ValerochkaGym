package com.valerochka1337.valerochkagym.ui

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.domain.measurements.effectiveWaistHipRatio
import com.valerochka1337.valerochkagym.ui.measurements.MeasurementEditorViewModel
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `editing an uploaded measurement does not schedule a second append`() =
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
            assertEquals(UploadStatus.UPLOADED, dao.updated.single().uploadStatus)
            assertTrue(scheduler.scheduled.isEmpty())
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

    private class FakeBodyMeasurementDao(vararg initial: BodyMeasurementEntity) : BodyMeasurementDao {
        val rows = MutableStateFlow(initial.toList())
        val inserted = mutableListOf<BodyMeasurementEntity>()
        val updated = mutableListOf<BodyMeasurementEntity>()
        val deleted = mutableListOf<String>()

        override suspend fun insert(measurement: BodyMeasurementEntity) {
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
        override fun schedule(measurementId: String) {
            scheduled += measurementId
        }

        override suspend fun retry(measurementId: String) = Unit
        override suspend fun scheduleAllPending(): Int = 0
    }
}
