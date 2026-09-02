package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class WeeklyScheduleRecoverySchedulerTest {
    @Test
    fun `wake replaces queued backoff instead of appending behind it`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val workManager = WorkManager.getInstance(context)
        val scheduler = WorkManagerWeeklyScheduleRecoveryScheduler(workManager)

        scheduler.enqueue()

        val first = workManager.getWorkInfosForUniqueWork(
            WorkManagerWeeklyScheduleRecoveryScheduler.UNIQUE_WORK_NAME,
        ).get().single()
        assertEquals(NetworkType.NOT_REQUIRED, first.constraints.requiredNetworkType)

        scheduler.wake()

        val afterWake = workManager.getWorkInfosForUniqueWork(
            WorkManagerWeeklyScheduleRecoveryScheduler.UNIQUE_WORK_NAME,
        ).get()
        val replacement = afterWake.single()
        // Test WorkManager immediately prunes the cancelled item from a REPLACE operation.
        assertNull(workManager.getWorkInfoById(first.id).get())
        assertNotEquals(first.id, replacement.id)
        // With no Hilt factory in this scheduler-only test the replacement may fail immediately,
        // but it must never remain BLOCKED behind the replaced backoff chain.
        assertNotEquals(WorkInfo.State.BLOCKED, replacement.state)
        assertEquals(NetworkType.NOT_REQUIRED, replacement.constraints.requiredNetworkType)
    }
}
