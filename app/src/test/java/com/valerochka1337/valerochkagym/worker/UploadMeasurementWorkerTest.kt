package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.dao.HealthSyncOutboxDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.google.SheetsRepository
import com.valerochka1337.valerochkagym.data.google.UploadResult
import com.valerochka1337.valerochkagym.data.settings.HealthSyncCategory as SettingCategory
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class UploadMeasurementWorkerTest {
    private lateinit var workManager: WorkManager

    @Before fun setUpWorkManager() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `successful measurement upload completes the work`() = runTest {
        val repository = FakeSheetsRepository(UploadResult.Success)

        val result = worker(repository, FakeBodyMeasurementDao()).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf("m1"), repository.measurementIds)
    }

    @Test
    fun `transient measurement error retries before the final attempt`() = runTest {
        val dao = FakeBodyMeasurementDao()

        val result = worker(
            FakeSheetsRepository(UploadResult.TransientFailure("Нет сети")),
            dao,
            runAttemptCount = 4,
        ).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertTrue(dao.statusUpdates.isEmpty())
    }

    @Test
    fun `last transient measurement error is visible as failed status`() = runTest {
        val dao = FakeBodyMeasurementDao()

        val result = worker(
            FakeSheetsRepository(UploadResult.TransientFailure("Нет сети")),
            dao,
            runAttemptCount = 5,
        ).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(listOf(Triple("m1", UploadStatus.FAILED, "Нет сети")), dao.statusUpdates)
    }

    @Test
    fun `two exact revisions acknowledge only their own immutable outbox rows`() = runTest {
        val outbox = FakeOutbox(entry(1), entry(2))
        val repository = FakeSheetsRepository(UploadResult.Success)

        assertEquals(ListenableWorker.Result.success(), worker(repository, FakeBodyMeasurementDao(), outbox = outbox, version = 1).doWork())
        assertEquals(listOf(2L), outbox.pending(HealthSyncCategory.MEASUREMENTS).map { it.version })
        assertEquals(ListenableWorker.Result.success(), worker(repository, FakeBodyMeasurementDao(), outbox = outbox, version = 2).doWork())

        assertTrue(outbox.pending(HealthSyncCategory.MEASUREMENTS).isEmpty())
        assertEquals(listOf(1L, 2L), repository.snapshots.map { it.version })
    }

    @Test
    fun `disabled persisted legacy work does no auth or network and keeps exact outbox`() = runTest {
        val outbox = FakeOutbox(entry(1), entry(2))
        val settings = SettingsRepository(Store()).also {
            it.setHealthSyncEnabled(true)
            it.setHealthSyncCategory(SettingCategory.MEASUREMENTS, false)
        }
        val repository = FakeSheetsRepository(UploadResult.Success)

        assertEquals(ListenableWorker.Result.success(), worker(repository, FakeBodyMeasurementDao(), outbox = outbox, settings = settings).doWork())
        assertTrue(repository.measurementIds.isEmpty())
        assertTrue(repository.snapshots.isEmpty())
        assertEquals(listOf(1L, 2L), outbox.pending(HealthSyncCategory.MEASUREMENTS).map { it.version })
    }

    @Test
    fun `legacy UUID work fans out every pending exact revision without direct upload`() = runTest {
        val outbox = FakeOutbox(entry(1), entry(2))
        val repository = FakeSheetsRepository(UploadResult.Success)

        assertEquals(ListenableWorker.Result.success(), worker(repository, FakeBodyMeasurementDao(), outbox = outbox).doWork())

        assertTrue(repository.measurementIds.isEmpty())
        assertTrue(repository.snapshots.isEmpty())
        assertEquals(listOf(1L, 2L), outbox.pending(HealthSyncCategory.MEASUREMENTS).map { it.version })
        assertEquals(1, workManager.getWorkInfosForUniqueWork("MEASUREMENTS:m1:1").get().size)
        assertEquals(1, workManager.getWorkInfosForUniqueWork("MEASUREMENTS:m1:2").get().size)
    }

    @Test
    fun `tombstone exact work acknowledges deletion version without falling back to live row`() = runTest {
        val outbox = FakeOutbox(entry(3, "tombstone"))
        val repository = FakeSheetsRepository(UploadResult.Success)

        assertEquals(ListenableWorker.Result.success(), worker(repository, FakeBodyMeasurementDao(), outbox = outbox, version = 3).doWork())
        assertEquals(listOf("tombstone"), repository.snapshots.map { it.canonicalPayload })
        assertTrue(outbox.pending(HealthSyncCategory.MEASUREMENTS).isEmpty())
    }

    @Test
    fun `lost response retry retains the exact immutable outbox row`() = runTest {
        val outbox = FakeOutbox(entry(2))
        val repository = FakeSheetsRepository(UploadResult.TransientFailure("Нет сети"))

        assertEquals(ListenableWorker.Result.retry(), worker(repository, FakeBodyMeasurementDao(), outbox = outbox, version = 2).doWork())
        assertEquals(listOf(2L), outbox.pending(HealthSyncCategory.MEASUREMENTS).map { it.version })
        assertEquals(listOf(2L), repository.snapshots.map { it.version })
    }

    private fun worker(
        repository: SheetsRepository,
        dao: BodyMeasurementDao,
        runAttemptCount: Int = 0,
        outbox: HealthSyncOutboxDao? = null,
        settings: SettingsRepository? = null,
        version: Long? = null,
    ): UploadMeasurementWorker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TestListenableWorkerBuilder<UploadMeasurementWorker>(context)
            .setInputData(workDataOf("measurementId" to "m1", "version" to (version ?: -1L)))
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = UploadMeasurementWorker(appContext, workerParameters, repository, dao, outbox, settings)
            })
            .build() as UploadMeasurementWorker
    }

    private class FakeSheetsRepository(private val result: UploadResult) : SheetsRepository {
        val measurementIds = mutableListOf<String>()
        val snapshots = mutableListOf<HealthSyncOutboxEntity>()
        override suspend fun uploadWorkout(workoutId: String): UploadResult = result

        override suspend fun uploadRoutine(routineSyncId: String): UploadResult = result

        override suspend fun uploadRoutineDeletion(routineSyncId: String, updatedAt: Long): UploadResult = result

        override suspend fun uploadMeasurement(measurementId: String): UploadResult {
            measurementIds += measurementId
            return result
        }
        override suspend fun uploadMeasurementSnapshot(snapshot: HealthSyncOutboxEntity): UploadResult {
            snapshots += snapshot
            return result
        }
    }

    private class FakeBodyMeasurementDao : BodyMeasurementDao {
        val statusUpdates = mutableListOf<Triple<String, UploadStatus, String?>>()
        override suspend fun insert(measurement: BodyMeasurementEntity) = Unit
        override suspend fun update(measurement: BodyMeasurementEntity) = Unit
        override fun observeAll(): Flow<List<BodyMeasurementEntity>> = flowOf(emptyList())
        override suspend fun getById(id: String): BodyMeasurementEntity? = null

        override suspend fun setUploadStatus(measurementId: String, status: UploadStatus, error: String?) {
            statusUpdates += Triple(measurementId, status, error)
        }

        override suspend fun getNotUploaded(): List<String> = emptyList()
        override suspend fun delete(id: String) = Unit
    }

    private fun entry(version: Long, payload: String = "payload-$version") = HealthSyncOutboxEntity(
        HealthSyncCategory.MEASUREMENTS, "m1", version, payload, "hash-$version", "m1:$version", version,
    )

    private class FakeOutbox(vararg initial: HealthSyncOutboxEntity) : HealthSyncOutboxDao {
        private val entries = initial.toMutableList()
        override suspend fun insert(entry: HealthSyncOutboxEntity) { entries += entry }
        override suspend fun pending(category: String) = entries.filter { it.category == category }.sortedBy { it.version }
        override suspend fun entry(category: String, syncId: String, version: Long) = entries.firstOrNull {
            it.category == category && it.syncId == syncId && it.version == version
        }
        override suspend fun acknowledge(category: String, syncId: String, version: Long) =
            if (entries.removeAll { it.category == category && it.syncId == syncId && it.version == version }) 1 else 0
    }

    private class Store : DataStore<Preferences> {
        private val values = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(values.value).also { values.value = it }
    }
}
