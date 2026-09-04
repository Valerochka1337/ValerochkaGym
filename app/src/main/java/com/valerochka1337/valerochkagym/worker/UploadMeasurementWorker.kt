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
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.google.SheetsRepository
import com.valerochka1337.valerochkagym.data.google.UploadResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Выгружает один замер тела. Поведение зеркально выгрузке тренировки: сеть обязательна, уникальная
 * задача на UUID и экспоненциальный backoff. При последней временной ошибке запись становится
 * FAILED, поэтому причину можно показать в истории замеров.
 */
@HiltWorker
class UploadMeasurementWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: SheetsRepository,
    private val bodyMeasurementDao: BodyMeasurementDao,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val measurementId = inputData.getString(KEY_MEASUREMENT_ID) ?: return Result.failure()
    return when (val result = repository.uploadMeasurement(measurementId)) {
      UploadResult.Success -> Result.success()
      is UploadResult.PermanentFailure -> Result.failure()
      is UploadResult.TransientFailure -> {
        if (runAttemptCount < MAX_ATTEMPTS) {
          Result.retry()
        } else {
          bodyMeasurementDao.setUploadStatus(measurementId, UploadStatus.FAILED, result.error)
          Result.failure()
        }
      }
    }
  }

  companion object {
    private const val KEY_MEASUREMENT_ID = "measurementId"
    private const val MAX_ATTEMPTS = 5
    private const val BACKOFF_SECONDS = 30L

    /**
     * Ставит замер в отдельную уникальную работу, чтобы повторы одного UUID не дублировали строку.
     */
    fun enqueue(workManager: WorkManager, measurementId: String) {
      val request =
          OneTimeWorkRequestBuilder<UploadMeasurementWorker>()
              .setConstraints(
                  Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
              )
              .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
              .setInputData(workDataOf(KEY_MEASUREMENT_ID to measurementId))
              .build()
      workManager.enqueueUniqueWork(
          "upload_measurement_$measurementId",
          ExistingWorkPolicy.REPLACE,
          request,
      )
    }
  }
}
