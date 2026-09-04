package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.dao.HealthSyncOutboxDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
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
class MeasurementUploadSchedulerTest {

    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `schedule enqueues every pending immutable revision for a measurement`() = runTest {
        val dao = FakeBodyMeasurementDao()
        val scheduler = WorkManagerMeasurementUploadScheduler(workManager, dao, FakeOutbox(entry("m1", 1), entry("m1", 2)))

        scheduler.schedule("m1")

        assertEquals(1, workManager.getWorkInfosForUniqueWork("MEASUREMENTS:m1:1").get().size)
        assertEquals(1, workManager.getWorkInfosForUniqueWork("MEASUREMENTS:m1:2").get().size)
        assertTrue(dao.statusUpdates.isEmpty())
    }

    @Test
    fun `category enable schedules migration outbox despite uploaded live projection`() = runTest {
        val dao = FakeBodyMeasurementDao()
        val scheduler = WorkManagerMeasurementUploadScheduler(workManager, dao, FakeOutbox(entry("migrated", 1)))

        scheduler.onCategoryChanged(true)

        assertEquals(1, workManager.getWorkInfosForUniqueWork("MEASUREMENTS:migrated:1").get().size)
        assertTrue(dao.statusUpdates.isEmpty())
    }

    @Test
    fun `disable retains pending snapshots and reenable restores every exact request`() = runTest {
        val outbox = FakeOutbox(entry("retained", 1), entry("retained", 2))
        val scheduler = WorkManagerMeasurementUploadScheduler(workManager, FakeBodyMeasurementDao(), outbox)

        scheduler.onCategoryChanged(false)
        assertEquals(listOf(1L, 2L), outbox.pending(HealthSyncCategory.MEASUREMENTS).map { it.version })
        scheduler.onCategoryChanged(true)

        assertEquals(1, workManager.getWorkInfosForUniqueWork("MEASUREMENTS:retained:1").get().size)
        assertEquals(1, workManager.getWorkInfosForUniqueWork("MEASUREMENTS:retained:2").get().size)
    }

    @Test
    fun `retry and export all use immutable outbox entries not live upload status`() = runTest {
        val dao = FakeBodyMeasurementDao(notUploaded = listOf("ignored-live-row"))
        val scheduler = WorkManagerMeasurementUploadScheduler(
            workManager, dao, FakeOutbox(entry("m1", 1), entry("m2", 1), entry("m2", 2)),
        )

        scheduler.retry("m1")
        val count = scheduler.scheduleAllPending()

        assertEquals(3, count)
        assertEquals(
            listOf("m1" to UploadStatus.PENDING),
            dao.statusUpdates,
        )
        listOf("MEASUREMENTS:m1:1", "MEASUREMENTS:m2:1", "MEASUREMENTS:m2:2").forEach { name ->
            assertEquals(1, workManager.getWorkInfosForUniqueWork(name).get().size)
        }
    }

    @Test
    fun `disabled measurements category retains durable outbox and enqueues no work`() = runTest {
        val settings = SettingsRepository(FakeDataStore(emptyPreferences()))
        val outbox = FakeOutbox(entry("m1", 1))
        val scheduler = WorkManagerMeasurementUploadScheduler(
            workManager, FakeBodyMeasurementDao(), outbox, settingsRepository = settings,
        )

        scheduler.schedule("m1")
        scheduler.retry("m1")
        val count = scheduler.scheduleAllPending()

        assertEquals(0, count)
        assertTrue(workManager.getWorkInfosForUniqueWork("MEASUREMENTS:m1:1").get().isEmpty())
        assertEquals(1, outbox.pending(HealthSyncCategory.MEASUREMENTS).size)
    }

    private fun entry(id: String, version: Long) = HealthSyncOutboxEntity(
        HealthSyncCategory.MEASUREMENTS, id, version, "payload-$id-$version", "hash", "$id:$version", version,
    )

    private class FakeBodyMeasurementDao(
        private val notUploaded: List<String> = emptyList(),
    ) : BodyMeasurementDao {
        val statusUpdates = mutableListOf<Pair<String, UploadStatus>>()
        override suspend fun insert(measurement: BodyMeasurementEntity) = Unit
        override suspend fun update(measurement: BodyMeasurementEntity) = Unit
        override fun observeAll(): Flow<List<BodyMeasurementEntity>> = flowOf(emptyList())
        override suspend fun getById(id: String): BodyMeasurementEntity? = null

        override suspend fun setUploadStatus(measurementId: String, status: UploadStatus, error: String?) {
            statusUpdates += measurementId to status
        }

        override suspend fun getNotUploaded(): List<String> = notUploaded
        override suspend fun delete(id: String) = Unit
    }

    private class FakeOutbox(vararg values: HealthSyncOutboxEntity) : HealthSyncOutboxDao {
        private val entries = values.toMutableList()
        override suspend fun insert(entry: HealthSyncOutboxEntity) { entries += entry }
        override suspend fun pending(category: String) = entries.filter { it.category == category }
        override suspend fun entry(category: String, syncId: String, version: Long) = entries.firstOrNull {
            it.category == category && it.syncId == syncId && it.version == version
        }
        override suspend fun acknowledge(category: String, syncId: String, version: Long) =
            if (entries.removeAll { it.category == category && it.syncId == syncId && it.version == version }) 1 else 0
    }

    private class FakeDataStore(prefs: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(prefs)
        override val data = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            state.value = transform(state.value)
            return state.value
        }
    }
}
