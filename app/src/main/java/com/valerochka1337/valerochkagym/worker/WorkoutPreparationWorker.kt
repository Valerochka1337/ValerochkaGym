package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.valerochka1337.valerochkagym.data.ai.WorkoutPreparationRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@HiltWorker
class WorkoutPreparationWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: WorkoutPreparationRepository,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val id = inputData.getString("requestId") ?: return Result.failure()
    if (!repository.step(id)) return Result.success()
    if (runAttemptCount < 8) return Result.retry()
    repository.pausePending(id)
    return Result.success()
  }
}

@Singleton
class WorkoutPreparationScheduler
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val repository: WorkoutPreparationRepository,
) {
  suspend fun start() {
    repository.current
        .map {
          it?.takeIf { row -> row.state in WorkoutPreparationRepository.activeStates }?.requestId
        }
        .distinctUntilChanged()
        .collect { if (it != null) enqueue(it) }
  }

  fun enqueue(id: String) {
    WorkManager.getInstance(context)
        .enqueueUniqueWork(
            "workout_preparation_$id",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<WorkoutPreparationWorker>()
                .setInputData(workDataOf("requestId" to id))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
  }
}
