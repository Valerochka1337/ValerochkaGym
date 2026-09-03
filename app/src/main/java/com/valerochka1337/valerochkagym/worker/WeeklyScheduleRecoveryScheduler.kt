package com.valerochka1337.valerochkagym.worker

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject

interface WeeklyScheduleRecoveryScheduler {
    /** Ensure durable recovery is queued without cancelling a currently running worker. */
    fun enqueue()

    /** Explicit user-driven wake-up that replaces a worker waiting in exponential backoff. */
    fun wake() = enqueue()
}

class WorkManagerWeeklyScheduleRecoveryScheduler @Inject constructor(
    private val workManager: WorkManager,
) : WeeklyScheduleRecoveryScheduler {
    override fun enqueue() = enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE)

    override fun wake() = enqueue(ExistingWorkPolicy.REPLACE)

    private fun enqueue(policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<WeeklyScheduleRecoveryWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "weekly_schedule_recovery"
        private const val BACKOFF_SECONDS = 30L
    }
}
