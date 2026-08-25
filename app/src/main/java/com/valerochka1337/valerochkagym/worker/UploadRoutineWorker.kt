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
import com.valerochka1337.valerochkagym.data.google.SheetsRepository
import com.valerochka1337.valerochkagym.data.google.UploadResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Выгружает текущий неизменяемый снимок программы либо её tombstone-строку. Отдельного статуса
 * в Room здесь нет: следующая правка или действие «Выгрузить всё» всегда безопасно ставят
 * актуальную версию повторно, а Sheets дедуплицирует пару UUID + updatedAt.
 */
@HiltWorker
class UploadRoutineWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: SheetsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val syncId = inputData.getString(KEY_ROUTINE_SYNC_ID) ?: return Result.failure()
        val isDeletion = inputData.getBoolean(KEY_IS_DELETION, false)
        val result = if (isDeletion) {
            val updatedAt = inputData.getLong(KEY_UPDATED_AT, MISSING_UPDATED_AT)
            if (updatedAt == MISSING_UPDATED_AT) return Result.failure()
            repository.uploadRoutineDeletion(syncId, updatedAt)
        } else {
            repository.uploadRoutine(syncId)
        }
        return when (result) {
            UploadResult.Success -> Result.success()
            is UploadResult.PermanentFailure -> Result.failure()
            is UploadResult.TransientFailure -> {
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }
        }
    }

    companion object {
        private const val KEY_ROUTINE_SYNC_ID = "routineSyncId"
        private const val KEY_IS_DELETION = "isDeletion"
        private const val KEY_UPDATED_AT = "updatedAt"
        private const val MISSING_UPDATED_AT = Long.MIN_VALUE
        private const val MAX_ATTEMPTS = 5
        private const val BACKOFF_SECONDS = 30L

        fun enqueue(workManager: WorkManager, syncId: String) {
            enqueue(workManager, syncId, isDeletion = false, updatedAt = null)
        }

        fun enqueueDeletion(workManager: WorkManager, syncId: String, updatedAt: Long) {
            enqueue(workManager, syncId, isDeletion = true, updatedAt = updatedAt)
        }

        private fun enqueue(
            workManager: WorkManager,
            syncId: String,
            isDeletion: Boolean,
            updatedAt: Long?,
        ) {
            val input = if (updatedAt == null) {
                workDataOf(KEY_ROUTINE_SYNC_ID to syncId, KEY_IS_DELETION to isDeletion)
            } else {
                workDataOf(
                    KEY_ROUTINE_SYNC_ID to syncId,
                    KEY_IS_DELETION to isDeletion,
                    KEY_UPDATED_AT to updatedAt,
                )
            }
            val request = OneTimeWorkRequestBuilder<UploadRoutineWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setInputData(input)
                .build()
            workManager.enqueueUniqueWork(
                "upload_routine_$syncId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
