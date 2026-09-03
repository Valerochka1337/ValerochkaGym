package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.valerochka1337.valerochkagym.data.db.dao.HealthSyncOutboxDao
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.data.google.HealthSheetsRepository
import com.valerochka1337.valerochkagym.data.google.RemoteClearResult
import com.valerochka1337.valerochkagym.data.google.UploadResult
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.data.settings.HealthSyncCategory as SettingsCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class HealthSyncWorkerTest {
    @Test fun `disabled category returns success before repository and leaves exact outbox row`() = runTest {
        val outbox = FakeOutbox(); val repository = FakeRepository(UploadResult.Success)
        val result = worker(SettingsRepository(FakeStore()), outbox, repository).doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(repository.entries.isEmpty()); assertTrue(outbox.acks.isEmpty())
    }
    @Test fun `successful exact outbox upload acknowledges only delivered version`() = runTest {
        val store = FakeStore(); val settings = SettingsRepository(store)
        settings.setHealthSyncEnabled(true); settings.setHealthSyncCategory(SettingsCategory.HEALTH_REPORTS_AND_OBSERVATIONS, true)
        val outbox = FakeOutbox(); val repository = FakeRepository(UploadResult.Success)
        assertEquals(ListenableWorker.Result.success(), worker(settings, outbox, repository).doWork())
        assertEquals(listOf(Triple(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, "r", 2L)), outbox.acks)
    }
    @Test fun `repository suppression after worker preflight retains exact outbox without acknowledgement`() = runTest {
        val store = FakeStore(); val settings = SettingsRepository(store)
        settings.setHealthSyncEnabled(true); settings.setHealthSyncCategory(SettingsCategory.HEALTH_REPORTS_AND_OBSERVATIONS, true)
        val outbox = FakeOutbox(); val repository = FakeRepository(UploadResult.NotAttemptedDisabled)

        assertEquals(ListenableWorker.Result.success(), worker(settings, outbox, repository).doWork())

        assertEquals(1, repository.entries.size)
        assertTrue(outbox.acks.isEmpty())
        assertEquals(1, outbox.pending(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS).size)
    }
    @Test fun `request has exact replace identity connected constraint and exponential backoff`() {
        val request = HealthSyncWorker.request(SettingsCategory.HEALTH_RESTRICTIONS, "r", 2)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertTrue(HealthSyncWorker.TAG_RESTRICTIONS in request.tags)
        assertEquals("health_sync_HEALTH_RESTRICTIONS_r_2", HealthSyncWorker.workName(SettingsCategory.HEALTH_RESTRICTIONS, "r", 2))
    }
    @Test fun `final transient attempt retains exact outbox without acknowledgement`() = runTest {
        val store = FakeStore(); val settings = SettingsRepository(store)
        settings.setHealthSyncEnabled(true); settings.setHealthSyncCategory(SettingsCategory.HEALTH_REPORTS_AND_OBSERVATIONS, true)
        val outbox = FakeOutbox(); val repository = FakeRepository(UploadResult.TransientFailure("offline"))
        val result = worker(settings, outbox, repository, HealthSyncWorker.MAX_ATTEMPTS - 1).doWork()
        assertEquals(ListenableWorker.Result.failure(), result)
        assertTrue(outbox.acks.isEmpty())
        assertEquals(1, outbox.pending(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS).size)
    }
    private fun worker(settings: SettingsRepository, outbox: FakeOutbox, repository: FakeRepository, attempts: Int = 0): HealthSyncWorker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TestListenableWorkerBuilder<HealthSyncWorker>(context).setRunAttemptCount(attempts).setInputData(workDataOf("category" to SettingsCategory.HEALTH_REPORTS_AND_OBSERVATIONS.name, "syncId" to "r", "version" to 2L)).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker = HealthSyncWorker(appContext, workerParameters, settings, outbox, repository)
        }).build()
    }
    private class FakeRepository(private val result: UploadResult) : HealthSheetsRepository {
        val entries = mutableListOf<HealthSyncOutboxEntity>()
        override suspend fun upload(entry: HealthSyncOutboxEntity): UploadResult { entries += entry; return result }
        override suspend fun import(category: SettingsCategory) = 0
        override suspend fun clearAfterConfirmation(category: SettingsCategory) = RemoteClearResult.Success(emptyList())
    }
    private class FakeOutbox : HealthSyncOutboxDao {
        val entry = HealthSyncOutboxEntity(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, "r", 2, "payload", "hash", "r:2", 1)
        val acks = mutableListOf<Triple<String,String,Long>>()
        override suspend fun insert(entry: HealthSyncOutboxEntity) = Unit
        override suspend fun pending(category: String) = listOf(entry)
        override suspend fun entry(category: String, syncId: String, version: Long) = entry.takeIf { it.category == category && it.syncId == syncId && it.version == version }
        override suspend fun acknowledge(category: String, syncId: String, version: Long): Int { acks += Triple(category,syncId,version); return 1 }
    }
    private class FakeStore : DataStore<Preferences> { private val value = MutableStateFlow(emptyPreferences()); override val data: Flow<Preferences> = value; override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(value.value).also { value.value = it } }
}
