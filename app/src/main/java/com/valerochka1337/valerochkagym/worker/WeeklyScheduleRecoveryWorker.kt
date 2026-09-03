package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRecoveryResult
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class WeeklyScheduleRecoveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: WeeklyScheduleRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when (repository.resumePendingOperation()) {
        WeeklyScheduleRecoveryResult.Completed,
        WeeklyScheduleRecoveryResult.NothingPending,
        is WeeklyScheduleRecoveryResult.Paused,
        -> Result.success()
        is WeeklyScheduleRecoveryResult.Retry -> Result.retry()
    }
}
