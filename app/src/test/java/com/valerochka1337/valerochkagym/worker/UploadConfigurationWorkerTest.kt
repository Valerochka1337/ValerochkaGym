package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.valerochka1337.valerochkagym.data.google.ConfigurationSheetsRepository
import com.valerochka1337.valerochkagym.data.google.UploadResult
import com.valerochka1337.valerochkagym.data.settings.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
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
class UploadConfigurationWorkerTest {
    @Test
    fun `disabled workouts category keeps configuration tombstone untouched`() = runTest {
        val settings = SettingsRepository(Store()).also {
            it.setHealthSyncEnabled(true)
            it.setHealthSyncCategory(HealthSyncCategory.WORKOUTS_AND_CONFIGURATION, false)
        }
        val repository = Repository()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<UploadConfigurationWorker>(context)
            .setInputData(workDataOf("syncId" to "gym", "kind" to "gym_deletion", "updatedAt" to 2L))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, parameters: WorkerParameters): ListenableWorker =
                    UploadConfigurationWorker(context, parameters, repository, settingsRepository = settings)
            }).build() as UploadConfigurationWorker

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertTrue(repository.calls.isEmpty())
    }

    private class Repository : ConfigurationSheetsRepository {
        val calls = mutableListOf<String>()
        override suspend fun uploadExercise(syncId: String) = UploadResult.Success.also { calls += "exercise" }
        override suspend fun uploadGym(syncId: String) = UploadResult.Success.also { calls += "gym" }
        override suspend fun uploadGymDeletion(syncId: String, updatedAt: Long) = UploadResult.Success.also { calls += "delete" }
        override suspend fun uploadRoutineGyms(routineSyncId: String) = UploadResult.Success
        override suspend fun uploadRoutineGymsDeletion(routineSyncId: String, updatedAt: Long) = UploadResult.Success
    }

    private class Store : DataStore<Preferences> {
        private val values = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(values.value).also { values.value = it }
    }
}
