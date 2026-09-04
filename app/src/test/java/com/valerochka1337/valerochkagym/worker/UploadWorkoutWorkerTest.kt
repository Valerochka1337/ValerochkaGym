package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.AnalyticsSetRow
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.data.google.SheetsRepository
import com.valerochka1337.valerochkagym.data.google.UploadResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [UploadWorkoutWorker]: маппинг [UploadResult] → [ListenableWorker.Result] и
 * граница попыток — на последней транзиентной ошибке статус становится FAILED навсегда. Воркер
 * собирается [TestListenableWorkerBuilder]-ом с фабрикой, подставляющей фейки вместо Hilt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class UploadWorkoutWorkerTest {

  @Test
  fun `missing workout id fails immediately without touching the repository`() = runTest {
    val repository = FakeSheetsRepository(UploadResult.Success)

    val result = worker(repository, FakeWorkoutDao(), inputWorkoutId = null).doWork()

    assertEquals(ListenableWorker.Result.failure(), result)
    assertTrue(repository.uploadedIds.isEmpty())
  }

  @Test
  fun `successful upload completes the work`() = runTest {
    val repository = FakeSheetsRepository(UploadResult.Success)

    val result = worker(repository, FakeWorkoutDao()).doWork()

    assertEquals(ListenableWorker.Result.success(), result)
    assertEquals(listOf("w1"), repository.uploadedIds)
  }

  @Test
  fun `permanent failure gives up without retries`() = runTest {
    val dao = FakeWorkoutDao()
    val repository = FakeSheetsRepository(UploadResult.PermanentFailure("Нет доступа"))

    val result = worker(repository, dao).doWork()

    assertEquals(ListenableWorker.Result.failure(), result)
    // Статус FAILED выставляет сам репозиторий — воркер его не дублирует.
    assertTrue(dao.statusUpdates.isEmpty())
  }

  @Test
  fun `transient failure retries while attempts remain`() = runTest {
    val dao = FakeWorkoutDao()
    val repository = FakeSheetsRepository(UploadResult.TransientFailure("HTTP 503"))

    val result = worker(repository, dao, runAttemptCount = 4).doWork()

    assertEquals(ListenableWorker.Result.retry(), result)
    assertTrue(dao.statusUpdates.isEmpty())
  }

  @Test
  fun `the last transient attempt marks the workout failed and stops`() = runTest {
    val dao = FakeWorkoutDao()
    val repository = FakeSheetsRepository(UploadResult.TransientFailure("HTTP 503"))

    val result = worker(repository, dao, runAttemptCount = 5).doWork()

    assertEquals(ListenableWorker.Result.failure(), result)
    assertEquals(
        listOf(Triple("w1", UploadStatus.FAILED, "HTTP 503")),
        dao.statusUpdates,
    )
  }

  private fun worker(
      repository: SheetsRepository,
      dao: WorkoutDao,
      inputWorkoutId: String? = "w1",
      runAttemptCount: Int = 0,
  ): UploadWorkoutWorker {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return TestListenableWorkerBuilder<UploadWorkoutWorker>(context)
        .setInputData(
            if (inputWorkoutId != null) workDataOf("workoutId" to inputWorkoutId) else workDataOf(),
        )
        .setRunAttemptCount(runAttemptCount)
        .setWorkerFactory(
            object : WorkerFactory() {
              override fun createWorker(
                  appContext: Context,
                  workerClassName: String,
                  workerParameters: WorkerParameters,
              ): ListenableWorker =
                  UploadWorkoutWorker(appContext, workerParameters, repository, dao)
            }
        )
        .build()
  }

  private class FakeSheetsRepository(private val result: UploadResult) : SheetsRepository {
    val uploadedIds = mutableListOf<String>()

    override suspend fun uploadWorkout(workoutId: String): UploadResult {
      uploadedIds += workoutId
      return result
    }

    override suspend fun uploadRoutine(routineSyncId: String): UploadResult = result

    override suspend fun uploadRoutineDeletion(
        routineSyncId: String,
        updatedAt: Long,
    ): UploadResult = result

    override suspend fun uploadMeasurement(measurementId: String): UploadResult = result
  }

  private class FakeWorkoutDao : WorkoutDao {
    override fun observeFinishedExerciseHistory() =
        flowOf(
            emptyList<com.valerochka1337.valerochkagym.data.db.relation.ExerciseWorkoutHistoryRow>()
        )

    val statusUpdates = mutableListOf<Triple<String, UploadStatus, String?>>()

    override suspend fun setUploadStatus(workoutId: String, status: UploadStatus, error: String?) {
      statusUpdates += Triple(workoutId, status, error)
    }

    override suspend fun insertWorkout(workout: WorkoutEntity) = Unit

    override suspend fun insertWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long = 0

    override suspend fun insertSet(set: WorkoutSetEntity): Long = 0

    override suspend fun insertSets(sets: List<WorkoutSetEntity>): List<Long> = emptyList()

    override suspend fun updateSet(set: WorkoutSetEntity) = Unit

    override suspend fun updateWorkoutExercises(exercises: List<WorkoutExerciseEntity>) = Unit

    override suspend fun setSetCompleted(setId: Long, completed: Boolean, completedAt: Long?) = Unit

    override suspend fun getSet(setId: Long): WorkoutSetEntity? = null

    override suspend fun getSetsForWorkoutExercise(
        workoutExerciseId: Long
    ): List<WorkoutSetEntity> = emptyList()

    override suspend fun getWorkoutExercises(workoutId: String): List<WorkoutExerciseEntity> =
        emptyList()

    override suspend fun setFinishedAt(id: String, finishedAt: Long) = Unit

    override fun observeActiveWorkout(): Flow<WorkoutFull?> = flowOf(null)

    override suspend fun getActiveWorkoutId(): String? = null

    override fun observeFinishedWorkouts(): Flow<List<WorkoutEntity>> = flowOf(emptyList())

    override fun observeCompletedSets(): Flow<List<AnalyticsSetRow>> = flowOf(emptyList())

    override suspend fun getWorkoutFull(id: String): WorkoutFull? = null

    override suspend fun lastCompletedSetsForExercise(exerciseId: Long): List<WorkoutSetEntity> =
        emptyList()

    override suspend fun maxCompletedWeight(exerciseId: Long, excludeWorkoutId: String): Double? =
        null

    override fun observeWorkout(id: String): Flow<WorkoutEntity?> = flowOf(null)

    override suspend fun getFinishedNotUploaded(): List<String> = emptyList()

    override suspend fun getExistingWorkoutIds(): List<String> = emptyList()

    override suspend fun deleteWorkout(id: String) = Unit

    override suspend fun deleteSet(id: Long) = Unit

    override suspend fun deleteWorkoutExercise(id: Long) = Unit
  }
}
