package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.valerochka1337.valerochkagym.data.calendar.CalendarMigrationGate
import com.valerochka1337.valerochkagym.data.google.ScheduleResult
import com.valerochka1337.valerochkagym.data.schedule.WeeklySchedule
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRecoveryResult
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class WeeklyScheduleRecoveryWorkerTest {
  @Test
  fun `transient recovery requests a retry`() = runTest {
    val result = worker(WeeklyScheduleRecoveryResult.Retry("сеть")).doWork()
    assertEquals(ListenableWorker.Result.retry(), result)
  }

  @Test
  fun `paused recovery completes without a busy retry loop`() = runTest {
    val result = worker(WeeklyScheduleRecoveryResult.Paused("аккаунт")).doWork()
    assertEquals(ListenableWorker.Result.success(), result)
  }

  @Test
  fun `completed recovery completes the work`() = runTest {
    val result = worker(WeeklyScheduleRecoveryResult.Completed).doWork()
    assertEquals(ListenableWorker.Result.success(), result)
  }

  @Test
  fun `pending calendar migration never calls the legacy recovery repository`() = runTest {
    val repository = FakeRepository(WeeklyScheduleRecoveryResult.Completed)
    val result = worker(repository, PendingGate).doWork()
    assertEquals(ListenableWorker.Result.success(), result)
    assertEquals(0, repository.resumeCalls)
  }

  private fun worker(result: WeeklyScheduleRecoveryResult): WeeklyScheduleRecoveryWorker =
      worker(FakeRepository(result), ReadyGate)

  private fun worker(
      repository: FakeRepository,
      gate: CalendarMigrationGate,
  ): WeeklyScheduleRecoveryWorker {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return TestListenableWorkerBuilder<WeeklyScheduleRecoveryWorker>(context)
        .setWorkerFactory(
            object : WorkerFactory() {
              override fun createWorker(
                  appContext: Context,
                  workerClassName: String,
                  workerParameters: WorkerParameters,
              ): ListenableWorker =
                  WeeklyScheduleRecoveryWorker(appContext, workerParameters, repository, gate)
            }
        )
        .build()
  }

  private class FakeRepository(
      private val recovery: WeeklyScheduleRecoveryResult,
  ) : WeeklyScheduleRepository {
    var resumeCalls = 0

    override fun observe(): Flow<WeeklySchedule> = flowOf(WeeklySchedule())

    override suspend fun save(schedule: WeeklySchedule): ScheduleResult = ScheduleResult.Success

    override suspend fun clear(): ScheduleResult = ScheduleResult.Success

    override suspend fun resumePendingOperation(): WeeklyScheduleRecoveryResult {
      resumeCalls++
      return recovery
    }
  }

  private object ReadyGate : CalendarMigrationGate {
    override suspend fun ensureReady(): Boolean = true
  }

  private object PendingGate : CalendarMigrationGate {
    override suspend fun ensureReady(): Boolean = false
  }
}
