package com.valerochka1337.valerochkagym.ui

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.AnalyticsSetRow
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.domain.ExerciseStatisticsCalculator
import com.valerochka1337.valerochkagym.ui.exercise.ExerciseDetailViewModel
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `exercise detail exposes profile muscles and statistics`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val exerciseDao = FakeExerciseDao()
            val muscleDao = FakeExerciseMuscleDao()
            val workoutDao = FakeWorkoutDao()
            val viewModel = viewModel(exerciseDao, muscleDao, workoutDao)
            val collector = backgroundScope.launch(mainDispatcherRule.testDispatcher) { viewModel.uiState.collect {} }

            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.loading)
            assertEquals("Жим лёжа", state.exercise?.name)
            assertEquals(listOf(Muscle.CHEST, Muscle.TRICEPS), state.loads.map { it.muscle })
            assertEquals("80×8", state.statistics?.lastSummary)
            assertEquals(1, state.statistics?.points?.size)
            collector.cancel()
        }

    @Test
    fun `saving editor updates custom exercise and muscle map`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val exerciseDao = FakeExerciseDao(isCustom = true)
            val muscleDao = FakeExerciseMuscleDao()
            val viewModel = viewModel(exerciseDao, muscleDao, FakeWorkoutDao())
            val collector = backgroundScope.launch(mainDispatcherRule.testDispatcher) { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.openEditor()
            assertNotNull(viewModel.editor.value)
            viewModel.saveEditor(
                name = "Новый жим",
                type = ExerciseType.TIMED,
                loads = listOf(MuscleLoad(Muscle.FRONT_DELTS, 100)),
            )
            advanceUntilIdle()

            assertEquals("Новый жим", exerciseDao.items.value.single().name)
            assertEquals(ExerciseType.TIMED, exerciseDao.items.value.single().type)
            assertEquals(listOf(Muscle.FRONT_DELTS), muscleDao.items.value.map { it.muscle })
            assertEquals(null, viewModel.editor.value)
            collector.cancel()
        }

    private fun viewModel(
        exerciseDao: ExerciseDao,
        muscleDao: ExerciseMuscleDao,
        workoutDao: WorkoutDao,
    ) = ExerciseDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf(GymRoutes.EXERCISE_ID_ARG to EXERCISE_ID)),
        exerciseDao = exerciseDao,
        exerciseMuscleDao = muscleDao,
        workoutDao = workoutDao,
        statisticsCalculator = ExerciseStatisticsCalculator(),
        computeDispatcher = mainDispatcherRule.testDispatcher,
    )

    private class FakeExerciseDao(isCustom: Boolean = false) : ExerciseDao {
        val items = MutableStateFlow(
            listOf(
                ExerciseEntity(
                    id = EXERCISE_ID,
                    name = "Жим лёжа",
                    muscleGroup = MuscleGroup.CHEST,
                    type = ExerciseType.STRENGTH,
                    isCustom = isCustom,
                ),
            ),
        )

        override fun getAll(): Flow<List<ExerciseEntity>> = items
        override suspend fun insert(exercise: ExerciseEntity): Long = exercise.id
        override suspend fun update(exercise: ExerciseEntity) {
            items.value = items.value.map { if (it.id == exercise.id) exercise else it }
        }
        override suspend fun insertAll(exercises: List<ExerciseEntity>) = Unit
        override suspend fun count(): Int = items.value.size
        override suspend fun getById(id: Long): ExerciseEntity? = items.value.firstOrNull { it.id == id }
        override suspend fun getAllOnce(): List<ExerciseEntity> = items.value
    }

    private class FakeExerciseMuscleDao : ExerciseMuscleDao {
        val items = MutableStateFlow(
            listOf(
                ExerciseMuscleEntity(EXERCISE_ID, Muscle.TRICEPS, 65),
                ExerciseMuscleEntity(EXERCISE_ID, Muscle.CHEST, 100),
            ),
        )

        override fun observeAll(): Flow<List<ExerciseMuscleEntity>> = items
        override suspend fun getForExercise(exerciseId: Long): List<ExerciseMuscleEntity> =
            items.value.filter { it.exerciseId == exerciseId }
        override suspend fun getMappedExerciseIds(): List<Long> = items.value.map { it.exerciseId }.distinct()
        override suspend fun upsertAll(rows: List<ExerciseMuscleEntity>) {
            items.value = items.value + rows
        }
        override suspend fun deleteForExercise(exerciseId: Long) {
            items.value = items.value.filterNot { it.exerciseId == exerciseId }
        }
    }

    private class FakeWorkoutDao : WorkoutDao {
        private val completed = MutableStateFlow(
            listOf(
                AnalyticsSetRow(
                    workoutId = "w1",
                    exerciseId = EXERCISE_ID,
                    exerciseName = "Жим лёжа",
                    exerciseType = ExerciseType.STRENGTH,
                    weightKg = 80.0,
                    reps = 8,
                    durationSec = null,
                    speedKmh = null,
                    inclinePct = null,
                    completedAt = 1_000,
                ),
            ),
        )

        override fun observeCompletedSets(): Flow<List<AnalyticsSetRow>> = completed
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
        override suspend fun getWorkoutFull(id: String): WorkoutFull? = null
        override suspend fun lastCompletedSetsForExercise(exerciseId: Long): List<WorkoutSetEntity> = emptyList()
        override suspend fun maxCompletedWeight(exerciseId: Long, excludeWorkoutId: String): Double? = null
        override suspend fun setUploadStatus(workoutId: String, status: UploadStatus, error: String?) = Unit
        override fun observeWorkout(id: String): Flow<WorkoutEntity?> = flowOf(null)
        override suspend fun getFinishedNotUploaded(): List<String> = emptyList()
        override suspend fun getExistingWorkoutIds(): List<String> = emptyList()
        override suspend fun deleteWorkout(id: String) = Unit
        override suspend fun deleteSet(id: Long) = Unit
        override suspend fun deleteWorkoutExercise(id: Long) = Unit
    }

    private companion object {
        const val EXERCISE_ID = 7L
    }
}
