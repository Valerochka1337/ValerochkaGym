package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.AnalyticsSetRow
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [WorkManagerUploadScheduler] над тестовым [WorkManager]: сброс статусов,
 * возвращаемое количество и уникальные имена работ `upload_<id>`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class UploadSchedulerTest {

    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `schedule enqueues a unique work item without touching statuses`() = runTest {
        val dao = FakeWorkoutDao()
        val scheduler = WorkManagerUploadScheduler(workManager, dao)

        scheduler.schedule("w1")

        assertEquals(1, workManager.getWorkInfosForUniqueWork("upload_w1").get().size)
        assertTrue(dao.statusUpdates.isEmpty())
    }

    @Test
    fun `retry resets the status to pending and enqueues`() = runTest {
        val dao = FakeWorkoutDao()
        val scheduler = WorkManagerUploadScheduler(workManager, dao)

        scheduler.retry("w2")

        assertEquals(listOf("w2" to UploadStatus.PENDING), dao.statusUpdates)
        assertEquals(1, workManager.getWorkInfosForUniqueWork("upload_w2").get().size)
    }

    @Test
    fun `scheduleAllPending enqueues every finished not-uploaded workout and returns the count`() =
        runTest {
            val dao = FakeWorkoutDao(finishedNotUploaded = listOf("a", "b", "c"))
            val scheduler = WorkManagerUploadScheduler(workManager, dao)

            val count = scheduler.scheduleAllPending()

            assertEquals(3, count)
            assertEquals(
                listOf("a" to UploadStatus.PENDING, "b" to UploadStatus.PENDING, "c" to UploadStatus.PENDING),
                dao.statusUpdates,
            )
            listOf("a", "b", "c").forEach { id ->
                assertEquals(1, workManager.getWorkInfosForUniqueWork("upload_$id").get().size)
            }
        }

    @Test
    fun `scheduleAllPending with nothing to upload returns zero`() = runTest {
        val dao = FakeWorkoutDao()
        val scheduler = WorkManagerUploadScheduler(workManager, dao)

        assertEquals(0, scheduler.scheduleAllPending())
        assertTrue(dao.statusUpdates.isEmpty())
    }

    private class FakeWorkoutDao(
        private val finishedNotUploaded: List<String> = emptyList(),
    ) : WorkoutDao {
        val statusUpdates = mutableListOf<Pair<String, UploadStatus>>()

        override suspend fun getFinishedNotUploaded(): List<String> = finishedNotUploaded

        override suspend fun setUploadStatus(workoutId: String, status: UploadStatus, error: String?) {
            statusUpdates += workoutId to status
        }

        override suspend fun insertWorkout(workout: WorkoutEntity) = Unit
        override suspend fun insertWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long = 0
        override suspend fun insertSet(set: WorkoutSetEntity): Long = 0
        override suspend fun insertSets(sets: List<WorkoutSetEntity>): List<Long> = emptyList()
        override suspend fun updateSet(set: WorkoutSetEntity) = Unit
        override suspend fun updateWorkoutExercises(exercises: List<WorkoutExerciseEntity>) = Unit
        override suspend fun setSetCompleted(setId: Long, completed: Boolean, completedAt: Long?) = Unit
        override suspend fun getSet(setId: Long): WorkoutSetEntity? = null
        override suspend fun getSetsForWorkoutExercise(workoutExerciseId: Long): List<WorkoutSetEntity> = emptyList()
        override suspend fun getWorkoutExercises(workoutId: String): List<WorkoutExerciseEntity> = emptyList()
        override suspend fun setFinishedAt(id: String, finishedAt: Long) = Unit
        override fun observeActiveWorkout(): Flow<WorkoutFull?> = flowOf(null)
        override suspend fun getActiveWorkoutId(): String? = null
        override fun observeFinishedWorkouts(): Flow<List<WorkoutEntity>> = flowOf(emptyList())
        override fun observeCompletedSets(): Flow<List<AnalyticsSetRow>> = flowOf(emptyList())
        override suspend fun getWorkoutFull(id: String): WorkoutFull? = null
        override suspend fun lastCompletedSetsForExercise(exerciseId: Long): List<WorkoutSetEntity> = emptyList()
        override suspend fun maxCompletedWeight(exerciseId: Long, excludeWorkoutId: String): Double? = null
        override fun observeWorkout(id: String): Flow<WorkoutEntity?> = flowOf(null)
        override suspend fun getExistingWorkoutIds(): List<String> = emptyList()
        override suspend fun deleteWorkout(id: String) = Unit
        override suspend fun deleteSet(id: Long) = Unit
        override suspend fun deleteWorkoutExercise(id: Long) = Unit
    }
}
