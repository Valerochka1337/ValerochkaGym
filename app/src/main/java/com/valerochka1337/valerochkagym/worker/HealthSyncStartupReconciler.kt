package com.valerochka1337.valerochkagym.worker

import com.valerochka1337.valerochkagym.data.settings.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconciles durable exact outbox entries after a process death between a committed write and
 * enqueueing WorkManager. It performs no authentication or network I/O itself.
 */
@Singleton
class HealthSyncStartupReconciler @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val measurementScheduler: MeasurementUploadScheduler,
    private val healthScheduler: HealthSyncScheduler,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            try {
                reconcile()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A later process start or category re-enable retries durable pending rows.
            }
        }
    }

    suspend fun reconcile() {
        val sync = settingsRepository.settings.first().healthSync
        if (sync.isEnabled(HealthSyncCategory.MEASUREMENTS)) measurementScheduler.scheduleAllPending()
        if (sync.isEnabled(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS)) {
            healthScheduler.schedulePending(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS)
        }
        if (sync.isEnabled(HealthSyncCategory.HEALTH_RESTRICTIONS)) {
            healthScheduler.schedulePending(HealthSyncCategory.HEALTH_RESTRICTIONS)
        }
    }
}
