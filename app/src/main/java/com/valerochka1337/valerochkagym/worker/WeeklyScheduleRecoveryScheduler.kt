package com.valerochka1337.valerochkagym.worker

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject

interface WeeklyScheduleRecoveryScheduler { fun enqueue() }

class WorkManagerWeeklyScheduleRecoveryScheduler @Inject constructor(
    private val workManager: WorkManager,
) : WeeklyScheduleRecoveryScheduler {
    override fun enqueue() {
        val request = OneTimeWorkRequestBuilder<WeeklyScheduleRecoveryWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "weekly_schedule_recovery"
        private const val BACKOFF_SECONDS = 30L
    }
}
