package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class WeeklyScheduleRecoverySchedulerTest {
    @Test
    fun `enqueue creates unconstrained work so local terminal state can finish offline`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val workManager = WorkManager.getInstance(context)
        val scheduler = WorkManagerWeeklyScheduleRecoveryScheduler(workManager)

        scheduler.enqueue()

        val work = workManager.getWorkInfosForUniqueWork(
            WorkManagerWeeklyScheduleRecoveryScheduler.UNIQUE_WORK_NAME,
        ).get()
        assertEquals(1, work.size)
        work.forEach { assertEquals(NetworkType.NOT_REQUIRED, it.constraints.requiredNetworkType) }
    }
}
