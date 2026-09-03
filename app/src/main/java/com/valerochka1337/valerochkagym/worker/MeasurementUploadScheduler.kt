package com.valerochka1337.valerochkagym.worker

import androidx.work.WorkManager
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.dao.HealthSyncOutboxDao
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory as OutboxCategory
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.measurements.MeasurementRepository
import com.valerochka1337.valerochkagym.data.settings.HealthSyncCategory as SettingCategory
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Шов между экраном замеров и WorkManager — аналог [UploadScheduler] для тренировок. */
interface MeasurementUploadScheduler {

    /** Новая либо ещё не выгруженная запись: статус уже PENDING, достаточно поставить воркер. */
    suspend fun schedule(measurementId: String)

    /** Явный повтор ошибки: очищает видимую причину и возвращает запись в PENDING. */
    suspend fun retry(measurementId: String)

    /** Ставит в очередь все PENDING/FAILED замеры для «Выгрузить всё». */
    suspend fun scheduleAllPending(): Int

    /** Cancels only this category; local snapshots and the durable outbox stay intact. */
    suspend fun onCategoryChanged(enabled: Boolean) = Unit
}

class WorkManagerMeasurementUploadScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val bodyMeasurementDao: BodyMeasurementDao,
    private val outboxDao: HealthSyncOutboxDao,
    private val measurementRepository: MeasurementRepository? = null,
    private val settingsRepository: SettingsRepository? = null,
) : MeasurementUploadScheduler {

    override suspend fun schedule(measurementId: String) {
        if (!isMeasurementsSyncEnabled()) return
        pendingFor(measurementId).forEach { entry ->
            UploadMeasurementWorker.enqueue(workManager, entry.syncId, entry.version)
        }
    }

    override suspend fun retry(measurementId: String) {
        if (!isMeasurementsSyncEnabled()) return
        measurementRepository?.retry(measurementId)
            ?: bodyMeasurementDao.setUploadStatus(measurementId, UploadStatus.PENDING, null)
        schedule(measurementId)
    }

    override suspend fun scheduleAllPending(): Int {
        if (!isMeasurementsSyncEnabled()) return 0
        val entries = outboxDao.pending(OutboxCategory.MEASUREMENTS)
        entries.forEach { entry ->
            UploadMeasurementWorker.enqueue(workManager, entry.syncId, entry.version)
        }
        return entries.size
    }

    override suspend fun onCategoryChanged(enabled: Boolean) {
        if (enabled) scheduleAllPending()
        else workManager.cancelAllWorkByTag(UploadMeasurementWorker.MEASUREMENTS_TAG)
    }

    private suspend fun pendingFor(measurementId: String) =
        outboxDao.pending(OutboxCategory.MEASUREMENTS).filter { it.syncId == measurementId }

    /** Fresh setting read keeps a just-disabled category from enqueuing stale UI work. */
    private suspend fun isMeasurementsSyncEnabled(): Boolean =
        settingsRepository?.settings?.first()?.healthSync?.isEnabled(SettingCategory.MEASUREMENTS) ?: true
}
