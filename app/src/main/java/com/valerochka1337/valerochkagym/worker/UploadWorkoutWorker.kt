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
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.google.SheetsRepository
import com.valerochka1337.valerochkagym.data.google.UploadResult
import com.valerochka1337.valerochkagym.data.settings.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Фоновая выгрузка одной тренировки в Google Sheets.
 *
 * Делегирует работу [SheetsRepository.uploadWorkout] и переводит [UploadResult] в результат работы:
 *  - [UploadResult.Success] → [Result.success];
 *  - [UploadResult.PermanentFailure] → [Result.failure] (статус уже FAILED, ретраи бессмысленны);
 *  - [UploadResult.TransientFailure] → [Result.retry], пока [runAttemptCount] < [MAX_ATTEMPTS];
 *    на последней попытке выставляет статус FAILED и завершается [Result.failure].
 */
@HiltWorker
class UploadWorkoutWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: SheetsRepository,
    private val workoutDao: WorkoutDao,
    private val settingsRepository: SettingsRepository? = null,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val workoutId = inputData.getString(KEY_WORKOUT_ID) ?: return Result.failure()
        if (settingsRepository != null && !settingsRepository.settings.first().healthSync
                .isEnabled(HealthSyncCategory.WORKOUTS_AND_CONFIGURATION)
        ) return Result.success()
        return when (val result = repository.uploadWorkout(workoutId)) {
            is UploadResult.Success -> Result.success()
            UploadResult.NotAttemptedDisabled -> Result.success()
            is UploadResult.PermanentFailure -> Result.failure()
            is UploadResult.TransientFailure -> {
                if (runAttemptCount < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    workoutDao.setUploadStatus(workoutId, UploadStatus.FAILED, result.error)
                    Result.failure()
                }
            }
        }
    }

    companion object {
        const val WORKOUTS_AND_CONFIGURATION_TAG = "sync_workouts_and_configuration"
        private const val KEY_WORKOUT_ID = "workoutId"
        private const val MAX_ATTEMPTS = 5
        private const val BACKOFF_SECONDS = 30L

        /**
         * Ставит выгрузку тренировки [workoutId] в очередь как уникальную работу
         * `upload_<workoutId>` с политикой [ExistingWorkPolicy.REPLACE] (перезапуск отменяет
         * предыдущую попытку той же тренировки). Требует сети, экспоненциальный backoff.
         */
        fun enqueue(workManager: WorkManager, workoutId: String) {
            val request = OneTimeWorkRequestBuilder<UploadWorkoutWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_WORKOUT_ID to workoutId))
                .addTag(WORKOUTS_AND_CONFIGURATION_TAG)
                .build()
            workManager.enqueueUniqueWork("upload_$workoutId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
