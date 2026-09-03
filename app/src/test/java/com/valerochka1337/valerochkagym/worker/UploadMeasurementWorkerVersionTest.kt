package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.data.db.entity.MeasurementSnapshotEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.google.SheetsRepository
import com.valerochka1337.valerochkagym.data.google.UploadResult
import com.valerochka1337.valerochkagym.data.measurements.MeasurementRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UploadMeasurementWorkerVersionTest : RoomDaoTest() {
    @Test fun `delayed v1 failure does not mark current v2 projection failed`() = runTest {
        val v1 = BodyMeasurementEntity("m", 1, weightKg = 70.0, uploadStatus = UploadStatus.PENDING)
        val v2 = v1.copy(weightKg = 69.0, uploadStatus = UploadStatus.PENDING, uploadError = null)
        val v1Payload = MeasurementRepository.canonicalPayload(v1)
        val v2Payload = MeasurementRepository.canonicalPayload(v2)
        db.bodyMeasurementDao().insert(v2)
        db.healthDao().insertMeasurementSnapshot(MeasurementSnapshotEntity("m", 1, 1, false, v1Payload, MeasurementRepository.sha256(v1Payload)))
        db.healthDao().insertMeasurementSnapshot(MeasurementSnapshotEntity("m", 2, 2, false, v2Payload, MeasurementRepository.sha256(v2Payload)))
        val v1Outbox = HealthSyncOutboxEntity(HealthSyncCategory.MEASUREMENTS, "m", 1, v1Payload, MeasurementRepository.sha256(v1Payload), "m:1", 1)
        val outbox = SingleOutbox(v1Outbox)

        val result = worker(v1Outbox, outbox).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(v2, db.bodyMeasurementDao().getById("m"))
        assertEquals(listOf(2L, 1L), db.healthDao().measurementSnapshots("m").map { it.version })
        assertEquals(v1Outbox, outbox.entry(HealthSyncCategory.MEASUREMENTS, "m", 1))
    }

    private fun worker(entry: HealthSyncOutboxEntity, outbox: SingleOutbox): UploadMeasurementWorker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TestListenableWorkerBuilder<UploadMeasurementWorker>(context)
            .setRunAttemptCount(5)
            .setInputData(workDataOf("measurementId" to "m", "version" to 1L))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    UploadMeasurementWorker(
                        appContext, workerParameters, TransientRepository(), db.bodyMeasurementDao(),
                        outbox, null, db.healthDao(),
                    )
            })
            .build() as UploadMeasurementWorker
    }

    private class TransientRepository : SheetsRepository {
        override suspend fun uploadWorkout(workoutId: String) = UploadResult.TransientFailure("offline")
        override suspend fun uploadMeasurement(measurementId: String) = UploadResult.TransientFailure("offline")
        override suspend fun uploadRoutine(routineSyncId: String) = UploadResult.TransientFailure("offline")
        override suspend fun uploadRoutineDeletion(routineSyncId: String, updatedAt: Long) = UploadResult.TransientFailure("offline")
        override suspend fun uploadMeasurementSnapshot(snapshot: HealthSyncOutboxEntity) = UploadResult.TransientFailure("offline")
    }
    private class SingleOutbox(private val value: HealthSyncOutboxEntity) : com.valerochka1337.valerochkagym.data.db.dao.HealthSyncOutboxDao {
        override suspend fun insert(entry: HealthSyncOutboxEntity) = Unit
        override suspend fun pending(category: String) = listOf(value).filter { it.category == category }
        override suspend fun entry(category: String, syncId: String, version: Long) = value.takeIf { it.category == category && it.syncId == syncId && it.version == version }
        override suspend fun acknowledge(category: String, syncId: String, version: Long) = 0
    }
}
