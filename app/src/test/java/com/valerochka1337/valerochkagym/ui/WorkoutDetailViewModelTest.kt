package com.valerochka1337.valerochkagym.ui

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.AnalyticsSetRow
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutVolume
import com.valerochka1337.valerochkagym.domain.PreviousSetsUseCase
import com.valerochka1337.valerochkagym.domain.WorkoutStatsUseCase
import com.valerochka1337.valerochkagym.ui.history.WorkoutDetailViewModel
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import com.valerochka1337.valerochkagym.worker.UploadScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [WorkoutDetailViewModel]. The one-shot load plus the live upload-status stream
 * layered on top of it is the risky part: the status arrives through [WorkoutDao.observeWorkout]
 * backed here by a [MutableStateFlow] the test can push new entities into.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `the workout tree is loaded sorted by position and set index`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val dao = FakeWorkoutDao(fullWorkout())
            val viewModel = viewModel(dao)

            val state = viewModel.uiState.value
            assertFalse(state.loading)
            assertEquals("Грудь", state.name)
            // Упражнения пришли в обратном порядке позиций — экран сортирует.
            assertEquals(listOf("Жим лёжа", "Разводка"), state.exercises.map { it.name })
            // Подходы пришли в обратном порядке setIndex — тоже сортируются, нумерация с 1.
            assertEquals(listOf("80×8", "80×10"), state.exercises.first().sets.map { it.summary })
            assertEquals(listOf(1, 2), state.exercises.first().sets.map { it.number })
            assertEquals("1 ч 00 мин", state.duration)
            // Объём: только выполненные силовые подходы, 80×8 + 80×10 = 1 440.
            assertEquals("1 440 кг", state.volume)
        }

    @Test
    fun `a missing id or workout just clears the loading flag`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = viewModel(FakeWorkoutDao(full = null))

            assertFalse(viewModel.uiState.value.loading)
            assertTrue(viewModel.uiState.value.exercises.isEmpty())
        }

    @Test
    fun `upload status keeps updating after the initial load`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val dao = FakeWorkoutDao(fullWorkout())
            val viewModel = viewModel(dao)
            assertEquals(UploadStatus.PENDING, viewModel.uiState.value.uploadStatus)

            dao.workoutStream.value = dao.full!!.workout.copy(
                uploadStatus = UploadStatus.FAILED,
                uploadError = "Нет доступа к таблице",
            )

            assertEquals(UploadStatus.FAILED, viewModel.uiState.value.uploadStatus)
            assertEquals("Нет доступа к таблице", viewModel.uiState.value.uploadError)
        }

    @Test
    fun `retry delegates to the upload scheduler`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val scheduler = FakeUploadScheduler()
            val viewModel = viewModel(FakeWorkoutDao(fullWorkout()), scheduler)

            viewModel.retryUpload()

            assertEquals(listOf("w1"), scheduler.retriedIds)
        }

    @Test
    fun `delete removes the workout and emits the navigation event`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val dao = FakeWorkoutDao(fullWorkout())
            val viewModel = viewModel(dao)
            val events = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.deleteEvents.collect { events += it }
            }

            viewModel.delete()

            assertEquals(listOf("w1"), dao.deletedWorkouts)
            assertEquals(1, events.size)
        }

    private fun TestScope.viewModel(
        dao: FakeWorkoutDao,
        scheduler: UploadScheduler = FakeUploadScheduler(),
    ): WorkoutDetailViewModel = WorkoutDetailViewModel(
        savedStateHandle = SavedStateHandle(
            if (dao.full != null) mapOf(GymRoutes.WORKOUT_ID_ARG to "w1") else emptyMap(),
        ),
        workoutDao = dao,
        statsUseCase = WorkoutStatsUseCase(dao),
        previousSetsUseCase = PreviousSetsUseCase(dao),
        uploadScheduler = scheduler,
    )

    /** Тренировка «Грудь»: упражнения и подходы намеренно в обратном порядке для проверки сортировки. */
    private fun fullWorkout(): WorkoutFull = WorkoutFull(
        workout = WorkoutEntity(
            id = "w1",
            name = "Грудь",
            startedAt = 0L,
            finishedAt = 60L * 60_000,
        ),
        exercises = listOf(
            WorkoutExerciseWithSets(
                workoutExercise = WorkoutExerciseEntity(id = 2, workoutId = "w1", exerciseId = 2, position = 1),
                exercise = ExerciseEntity(id = 2, name = "Разводка", muscleGroup = MuscleGroup.CHEST, type = ExerciseType.STRENGTH),
                sets = emptyList(),
            ),
            WorkoutExerciseWithSets(
                workoutExercise = WorkoutExerciseEntity(id = 1, workoutId = "w1", exerciseId = 1, position = 0),
                exercise = ExerciseEntity(id = 1, name = "Жим лёжа", muscleGroup = MuscleGroup.CHEST, type = ExerciseType.STRENGTH),
                sets = listOf(
                    WorkoutSetEntity(id = 11, workoutExerciseId = 1, setIndex = 1, weightKg = 80.0, reps = 10, isCompleted = true),
                    WorkoutSetEntity(id = 10, workoutExerciseId = 1, setIndex = 0, weightKg = 80.0, reps = 8, isCompleted = true),
                ),
            ),
        ),
    )

    private class FakeWorkoutDao(val full: WorkoutFull?) : WorkoutDao {
        val workoutStream = MutableStateFlow(full?.workout)
        val deletedWorkouts = mutableListOf<String>()

        override suspend fun getWorkoutFull(id: String): WorkoutFull? = full
        override fun observeWorkout(id: String): Flow<WorkoutEntity?> = workoutStream
        override suspend fun deleteWorkout(id: String) {
            deletedWorkouts += id
        }

        override suspend fun insertWorkout(workout: WorkoutEntity) = Unit
        override suspend fun updateWorkout(workout: WorkoutEntity) = Unit
        override suspend fun insertWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long = 0
        override suspend fun updateWorkoutExercise(workoutExercise: WorkoutExerciseEntity) = Unit
        override suspend fun insertSet(set: WorkoutSetEntity): Long = 0
        override suspend fun insertSets(sets: List<WorkoutSetEntity>): List<Long> = emptyList()
        override suspend fun updateSet(set: WorkoutSetEntity) = Unit
        override suspend fun setSetCompleted(setId: Long, completed: Boolean, completedAt: Long?) = Unit
        override suspend fun getSet(setId: Long): WorkoutSetEntity? = null
        override suspend fun getSetsForWorkoutExercise(workoutExerciseId: Long): List<WorkoutSetEntity> = emptyList()
        override suspend fun getWorkoutExercises(workoutId: String): List<WorkoutExerciseEntity> = emptyList()
        override suspend fun setFinishedAt(id: String, finishedAt: Long) = Unit
        override fun observeActiveWorkout(): Flow<WorkoutFull?> = flowOf(null)
        override suspend fun getActiveWorkoutId(): String? = null
        override fun observeFinishedWorkouts(): Flow<List<WorkoutEntity>> = flowOf(emptyList())
        override fun observeWorkoutVolumes(): Flow<List<WorkoutVolume>> = flowOf(emptyList())
        override fun observeCompletedSets(): Flow<List<AnalyticsSetRow>> = flowOf(emptyList())
        override suspend fun lastCompletedSetsForExercise(exerciseId: Long): List<WorkoutSetEntity> = emptyList()
        override suspend fun maxCompletedWeight(exerciseId: Long, excludeWorkoutId: String): Double? = null
        override suspend fun setUploadStatus(workoutId: String, status: UploadStatus, error: String?) = Unit
        override suspend fun getFinishedNotUploaded(): List<String> = emptyList()
        override suspend fun getExistingWorkoutIds(): List<String> = emptyList()
        override suspend fun deleteSet(id: Long) = Unit
        override suspend fun deleteWorkoutExercise(id: Long) = Unit
    }

    private class FakeUploadScheduler : UploadScheduler {
        val retriedIds = mutableListOf<String>()
        override fun schedule(workoutId: String) = Unit
        override suspend fun retry(workoutId: String) {
            retriedIds += workoutId
        }
        override suspend fun scheduleAllPending(): Int = 0
    }
}
