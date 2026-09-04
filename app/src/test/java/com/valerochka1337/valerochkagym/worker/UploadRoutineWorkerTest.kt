package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.valerochka1337.valerochkagym.data.google.SheetsRepository
import com.valerochka1337.valerochkagym.data.google.UploadResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class UploadRoutineWorkerTest {

  @Test
  fun `uploading a routine snapshot completes the work`() = runTest {
    val repository = FakeSheetsRepository(snapshotResult = UploadResult.Success)

    val result = worker(repository).doWork()

    assertEquals(ListenableWorker.Result.success(), result)
    assertEquals(listOf("routine-1"), repository.snapshotIds)
    assertTrue(repository.deletions.isEmpty())
  }

  @Test
  fun `uploading a routine deletion sends the tombstone version`() = runTest {
    val repository = FakeSheetsRepository(deletionResult = UploadResult.Success)

    val result = worker(repository, deletionUpdatedAt = 123L).doWork()

    assertEquals(ListenableWorker.Result.success(), result)
    assertEquals(listOf("routine-1" to 123L), repository.deletions)
    assertTrue(repository.snapshotIds.isEmpty())
  }

  @Test
  fun `a transient routine error retries before the final attempt`() = runTest {
    val result =
        worker(
                FakeSheetsRepository(snapshotResult = UploadResult.TransientFailure("Нет сети")),
                runAttemptCount = 4,
            )
            .doWork()

    assertEquals(ListenableWorker.Result.retry(), result)
  }

  @Test
  fun `a final transient routine error fails without a local status`() = runTest {
    val result =
        worker(
                FakeSheetsRepository(snapshotResult = UploadResult.TransientFailure("Нет сети")),
                runAttemptCount = 5,
            )
            .doWork()

    assertEquals(ListenableWorker.Result.failure(), result)
  }

  private fun worker(
      repository: SheetsRepository,
      deletionUpdatedAt: Long? = null,
      runAttemptCount: Int = 0,
  ): UploadRoutineWorker {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val input =
        if (deletionUpdatedAt == null) {
          workDataOf("routineSyncId" to "routine-1")
        } else {
          workDataOf(
              "routineSyncId" to "routine-1",
              "isDeletion" to true,
              "updatedAt" to deletionUpdatedAt,
          )
        }
    return TestListenableWorkerBuilder<UploadRoutineWorker>(context)
        .setInputData(input)
        .setRunAttemptCount(runAttemptCount)
        .setWorkerFactory(
            object : WorkerFactory() {
              override fun createWorker(
                  appContext: Context,
                  workerClassName: String,
                  workerParameters: WorkerParameters,
              ): ListenableWorker = UploadRoutineWorker(appContext, workerParameters, repository)
            }
        )
        .build()
  }

  private class FakeSheetsRepository(
      private val snapshotResult: UploadResult = UploadResult.Success,
      private val deletionResult: UploadResult = snapshotResult,
  ) : SheetsRepository {
    val snapshotIds = mutableListOf<String>()
    val deletions = mutableListOf<Pair<String, Long>>()

    override suspend fun uploadWorkout(workoutId: String): UploadResult = UploadResult.Success

    override suspend fun uploadMeasurement(measurementId: String): UploadResult =
        UploadResult.Success

    override suspend fun uploadRoutine(routineSyncId: String): UploadResult {
      snapshotIds += routineSyncId
      return snapshotResult
    }

    override suspend fun uploadRoutineDeletion(
        routineSyncId: String,
        updatedAt: Long,
    ): UploadResult {
      deletions += routineSyncId to updatedAt
      return deletionResult
    }
  }
}
