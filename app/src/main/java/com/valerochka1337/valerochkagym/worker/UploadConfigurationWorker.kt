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
import com.valerochka1337.valerochkagym.data.db.dao.ConfigurationTombstoneDao
import com.valerochka1337.valerochkagym.data.db.entity.ConfigurationTombstoneKind
import com.valerochka1337.valerochkagym.data.google.ConfigurationSheetsRepository
import com.valerochka1337.valerochkagym.data.google.UploadResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class UploadConfigurationWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: ConfigurationSheetsRepository,
    private val tombstoneDao: ConfigurationTombstoneDao? = null,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val syncId = inputData.getString(KEY_SYNC_ID) ?: return Result.failure()
    val result =
        when (inputData.getString(KEY_KIND)) {
          KIND_EXERCISE -> repository.uploadExercise(syncId)
          KIND_GYM -> repository.uploadGym(syncId)
          KIND_GYM_DELETION -> {
            val updatedAt = inputData.getLong(KEY_UPDATED_AT, MISSING_UPDATED_AT)
            if (updatedAt == MISSING_UPDATED_AT) return Result.failure()
            repository.uploadGymDeletion(syncId, updatedAt)
          }
          else -> return Result.failure()
        }
    return when (result) {
      UploadResult.Success -> {
        if (inputData.getString(KEY_KIND) == KIND_GYM_DELETION) {
          val updatedAt = inputData.getLong(KEY_UPDATED_AT, MISSING_UPDATED_AT)
          tombstoneDao?.delete(ConfigurationTombstoneKind.GYM, syncId, updatedAt)
        }
        Result.success()
      }
      is UploadResult.PermanentFailure -> Result.failure()
      is UploadResult.TransientFailure -> {
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
      }
    }
  }

  companion object {
    private const val KEY_SYNC_ID = "syncId"
    private const val KEY_KIND = "kind"
    private const val KEY_UPDATED_AT = "updatedAt"
    private const val KIND_EXERCISE = "exercise"
    private const val KIND_GYM = "gym"
    private const val KIND_GYM_DELETION = "gym_deletion"
    private const val MISSING_UPDATED_AT = Long.MIN_VALUE
    private const val MAX_ATTEMPTS = 5
    private const val BACKOFF_SECONDS = 30L

    fun enqueueExercise(workManager: WorkManager, syncId: String) =
        enqueue(workManager, KIND_EXERCISE, syncId, null)

    fun enqueueGym(workManager: WorkManager, syncId: String) =
        enqueue(workManager, KIND_GYM, syncId, null)

    fun enqueueGymDeletion(workManager: WorkManager, syncId: String, updatedAt: Long) =
        enqueue(workManager, KIND_GYM_DELETION, syncId, updatedAt)

    private fun enqueue(
        workManager: WorkManager,
        kind: String,
        syncId: String,
        updatedAt: Long?,
    ) {
      val input =
          workDataOf(
              KEY_KIND to kind,
              KEY_SYNC_ID to syncId,
              KEY_UPDATED_AT to (updatedAt ?: MISSING_UPDATED_AT),
          )
      val request =
          OneTimeWorkRequestBuilder<UploadConfigurationWorker>()
              .setConstraints(
                  Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
              )
              .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
              .setInputData(input)
              .build()
      workManager.enqueueUniqueWork(
          "upload_${kind}_$syncId",
          ExistingWorkPolicy.REPLACE,
          request,
      )
    }
  }
}
