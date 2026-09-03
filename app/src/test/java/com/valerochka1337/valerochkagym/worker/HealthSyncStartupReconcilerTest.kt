package com.valerochka1337.valerochkagym.worker

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.valerochka1337.valerochkagym.data.settings.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthSyncStartupReconcilerTest {
    @Test fun `startup schedules every effective durable health category and remains idempotent by scheduler identity`() = runBlocking {
        val settings = SettingsRepository(FakeStore()).also {
            it.setHealthSyncEnabled(true)
            it.setHealthSyncCategory(HealthSyncCategory.MEASUREMENTS, true)
            it.setHealthSyncCategory(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, true)
            it.setHealthSyncCategory(HealthSyncCategory.HEALTH_RESTRICTIONS, true)
        }
        val measurements = RecordingMeasurements(); val health = RecordingHealth()
        val reconciler = HealthSyncStartupReconciler(settings, measurements, health, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))

        reconciler.reconcile(); reconciler.reconcile()

        assertEquals(2, measurements.calls)
        assertEquals(listOf(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, HealthSyncCategory.HEALTH_RESTRICTIONS, HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, HealthSyncCategory.HEALTH_RESTRICTIONS), health.pending)
    }

    @Test fun `startup leaves disabled categories entirely unscheduled`() = runBlocking {
        val settings = SettingsRepository(FakeStore())
        val measurements = RecordingMeasurements(); val health = RecordingHealth()
        HealthSyncStartupReconciler(settings, measurements, health, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)).reconcile()
        assertEquals(0, measurements.calls); assertEquals(emptyList<HealthSyncCategory>(), health.pending)
    }

    private class RecordingMeasurements : MeasurementUploadScheduler {
        var calls = 0
        override suspend fun schedule(measurementId: String) = Unit
        override suspend fun retry(measurementId: String) = Unit
        override suspend fun scheduleAllPending(): Int { calls++; return 1 }
    }
    private class RecordingHealth : HealthSyncScheduler {
        val pending = mutableListOf<HealthSyncCategory>()
        override suspend fun schedule(entry: com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity) = Unit
        override suspend fun schedulePending(category: HealthSyncCategory): Int { pending += category; return 1 }
        override suspend fun onCategoryChanged(category: HealthSyncCategory, enabled: Boolean) = Unit
    }
    private class FakeStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }
}
