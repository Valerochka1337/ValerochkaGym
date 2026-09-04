package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.dao.HealthSyncOutboxDao
import com.valerochka1337.valerochkagym.data.db.dao.HealthDao
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.google.SheetsRepository
import com.valerochka1337.valerochkagym.data.google.UploadResult
import com.valerochka1337.valerochkagym.data.settings.HealthSyncCategory as SettingsCategory
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Выгружает один замер тела. Поведение зеркально выгрузке тренировки: сеть обязательна,
 * уникальная задача на UUID и экспоненциальный backoff. При последней временной ошибке запись
 * становится FAILED, поэтому причину можно показать в истории замеров.
 */
@HiltWorker
class UploadMeasurementWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: SheetsRepository,
    private val bodyMeasurementDao: BodyMeasurementDao,
    private val outboxDao: HealthSyncOutboxDao? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val healthDao: HealthDao? = null,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Suppress persisted legacy work before any credential lookup or network-capable repository call.
        if (settingsRepository != null && !settingsRepository.settings.first().healthSync.isEnabled(SettingsCategory.MEASUREMENTS)) {
            return Result.success()
        }
        val measurementId = inputData.getString(KEY_MEASUREMENT_ID) ?: return Result.failure()
        val version = inputData.getLong(KEY_VERSION, -1).takeIf { it > 0 }
        if (version == null) {
            // v9 persisted work was keyed only by UUID. Do not pick an arbitrary latest entry:
            // fan it out to every immutable pending revision, then let exact workers ACK each one.
            val pending = outboxDao?.pending(HealthSyncCategory.MEASUREMENTS)
                ?.filter { it.syncId == measurementId }
                .orEmpty()
            if (pending.isNotEmpty()) {
                val workManager = WorkManager.getInstance(applicationContext)
                pending.forEach { entry -> enqueue(workManager, entry.syncId, entry.version) }
                return Result.success()
            }
        }
        val snapshot = version?.let { outboxDao?.entry(HealthSyncCategory.MEASUREMENTS, measurementId, it) }
        // Exact work is idempotent: a stale request with an already ACKed row does nothing.
        if (version != null && snapshot == null) return Result.success()
        val result = if (snapshot == null) repository.uploadMeasurement(measurementId)
        else repository.uploadMeasurementSnapshot(snapshot)
        return when (result) {
            UploadResult.Success -> {
                snapshot?.let { outboxDao?.acknowledge(it.category, it.syncId, it.version) }
                Result.success()
            }
            UploadResult.NotAttemptedDisabled -> Result.success()
            is UploadResult.PermanentFailure -> Result.failure()
            is UploadResult.TransientFailure -> {
                if (runAttemptCount < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    // A late v1 failure must never overwrite the visible state of an already
                    // saved v2 projection. Legacy work has no immutable version and retains
                    // its historical behaviour.
                    if (version == null || healthDao?.measurementSnapshots(measurementId)?.maxOfOrNull { it.version } == version) {
                        bodyMeasurementDao.setUploadStatus(measurementId, UploadStatus.FAILED, result.error)
                    }
                    Result.failure()
                }
            }
        }
    }

    companion object {
        private const val KEY_MEASUREMENT_ID = "measurementId"
        private const val KEY_VERSION = "version"
        private const val MAX_ATTEMPTS = 5
        private const val BACKOFF_SECONDS = 30L
        const val MEASUREMENTS_TAG = "health_sync_measurements"

        /** Ставит замер в отдельную уникальную работу, чтобы повторы одного UUID не дублировали строку. */
        fun enqueue(workManager: WorkManager, measurementId: String, version: Long? = null) {
            val request = OneTimeWorkRequestBuilder<UploadMeasurementWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(MEASUREMENTS_TAG)
                .setInputData(workDataOf(KEY_MEASUREMENT_ID to measurementId, KEY_VERSION to (version ?: -1)))
                .build()
            workManager.enqueueUniqueWork(
                if (version == null) "upload_measurement_$measurementId"
                else "${HealthSyncCategory.MEASUREMENTS}:$measurementId:$version",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
