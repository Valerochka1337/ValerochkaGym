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
    fun `enqueue appends a connected successor to the unique chain`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val workManager = WorkManager.getInstance(context)
        val scheduler = WorkManagerWeeklyScheduleRecoveryScheduler(workManager)

        scheduler.enqueue()
        scheduler.enqueue()

        val work = workManager.getWorkInfosForUniqueWork(
            WorkManagerWeeklyScheduleRecoveryScheduler.UNIQUE_WORK_NAME,
        ).get()
        assertEquals(2, work.size)
        work.forEach { assertEquals(NetworkType.CONNECTED, it.constraints.requiredNetworkType) }
    }
}
