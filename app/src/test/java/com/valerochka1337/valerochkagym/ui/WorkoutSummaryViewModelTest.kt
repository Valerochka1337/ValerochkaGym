package com.valerochka1337.valerochkagym.ui

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.AnalyticsSetRow
import com.valerochka1337.valerochkagym.data.db.relation.RoutineExerciseWithExercise
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithCount
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.domain.PreviousSetsUseCase
import com.valerochka1337.valerochkagym.domain.RoutineUpdateUseCase
import com.valerochka1337.valerochkagym.domain.WorkoutStatsUseCase
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.ui.summary.WorkoutSummaryViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [WorkoutSummaryViewModel]: сортировка дерева, сводка по выполненным подходам
 * и разовое предложение «обновить программу».
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutSummaryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `summary shows duration volume and completed sets in domain order`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = viewModel(fullWorkout())

            val state = viewModel.uiState.value
            assertFalse(state.loading)
            assertEquals("Грудь", state.workoutName)
            assertEquals(45L * 60, state.durationSeconds)
            // Только выполненные силовые подходы: 80×8 + 80×10 + 20×12.
            assertEquals(1_680.0, state.volumeKg, 1e-9)
            // Упражнения отсортированы по позиции; в сводке только выполненные подходы по setIndex.
            assertEquals(listOf("Жим лёжа", "Разводка"), state.exercises.map { it.name })
            assertEquals("80×8, 80×10", state.exercises.first().setsSummary)
        }

    @Test
    fun `a missing workout only clears the loading flag`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = viewModel(full = null)

            assertFalse(viewModel.uiState.value.loading)
            assertTrue(viewModel.uiState.value.exercises.isEmpty())
        }

    @Test
    fun `the update-routine dialog appears when the workout diverged from its routine`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            // Программа без упражнений, тренировка с выполненным подходом — расхождение.
            val viewModel = viewModel(
                fullWorkout(routineId = 7L),
                routineDao = FakeRoutineDao(routineWithExercises(7L)),
            )

            assertTrue(viewModel.uiState.value.showUpdateRoutineDialog)
        }

    @Test
    fun `dismissing the dialog hides it without touching the routine`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val routineDao = FakeRoutineDao(routineWithExercises(7L))
            val viewModel = viewModel(fullWorkout(routineId = 7L), routineDao = routineDao)

            viewModel.dismissRoutineUpdate()

            assertFalse(viewModel.uiState.value.showUpdateRoutineDialog)
            assertTrue(routineDao.replacedExercises.isEmpty())
        }

    @Test
    fun `applying the update rewrites the routine with the performed sets`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val routineDao = FakeRoutineDao(routineWithExercises(7L))
            val viewModel = viewModel(fullWorkout(routineId = 7L), routineDao = routineDao)

            viewModel.applyRoutineUpdate()

            assertFalse(viewModel.uiState.value.showUpdateRoutineDialog)
            val replaced = routineDao.replacedExercises
            // Оба упражнения с выполненными подходами, по позиции; plannedSets — фактические.
            assertEquals(listOf(1L, 2L), replaced.map { it.exerciseId })
            assertEquals(
                listOf(PlannedSet(weightKg = 80.0, reps = 8), PlannedSet(weightKg = 80.0, reps = 10)),
                replaced.first().plannedSets,
            )
        }

    private fun viewModel(
        full: WorkoutFull?,
        routineDao: RoutineDao = FakeRoutineDao(null),
    ): WorkoutSummaryViewModel {
        val workoutDao = FakeWorkoutDao(full)
        return WorkoutSummaryViewModel(
            savedStateHandle = SavedStateHandle(
                if (full != null) mapOf(GymRoutes.WORKOUT_ID_ARG to full.workout.id) else emptyMap(),
            ),
            workoutDao = workoutDao,
            statsUseCase = WorkoutStatsUseCase(workoutDao),
            routineUpdateUseCase = RoutineUpdateUseCase(routineDao),
            previousSetsUseCase = PreviousSetsUseCase(workoutDao),
        )
    }

    /** Тренировка «Грудь» в обратном порядке позиций/индексов; невыполненный подход в сводку не попадает. */
    private fun fullWorkout(routineId: Long? = null): WorkoutFull = WorkoutFull(
        workout = WorkoutEntity(
            id = "w1",
            routineId = routineId,
            name = "Грудь",
            startedAt = 0L,
            finishedAt = 45L * 60_000,
        ),
        exercises = listOf(
            WorkoutExerciseWithSets(
                workoutExercise = WorkoutExerciseEntity(id = 2, workoutId = "w1", exerciseId = 2, position = 1),
                exercise = ExerciseEntity(id = 2, name = "Разводка", muscleGroup = MuscleGroup.CHEST, type = ExerciseType.STRENGTH),
                sets = listOf(
                    WorkoutSetEntity(id = 20, workoutExerciseId = 2, setIndex = 0, weightKg = 20.0, reps = 12, isCompleted = true),
                    WorkoutSetEntity(id = 21, workoutExerciseId = 2, setIndex = 1, weightKg = 20.0, reps = 12, isCompleted = false),
                ),
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

    private fun routineWithExercises(
        id: Long,
        exercises: List<RoutineExerciseWithExercise> = emptyList(),
    ): RoutineWithExercises =
        RoutineWithExercises(routine = RoutineEntity(id = id, name = "Программа"), exercises = exercises)

    private class FakeWorkoutDao(private val full: WorkoutFull?) : WorkoutDao {
        override suspend fun getWorkoutFull(id: String): WorkoutFull? = full
        override suspend fun maxCompletedWeight(exerciseId: Long, excludeWorkoutId: String): Double? = null

        override suspend fun insertWorkout(workout: WorkoutEntity) = Unit
        override suspend fun insertWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long = 0
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
        override fun observeCompletedSets(): Flow<List<AnalyticsSetRow>> = flowOf(emptyList())
        override suspend fun lastCompletedSetsForExercise(exerciseId: Long): List<WorkoutSetEntity> = emptyList()
        override suspend fun setUploadStatus(workoutId: String, status: UploadStatus, error: String?) = Unit
        override fun observeWorkout(id: String): Flow<WorkoutEntity?> = flowOf(null)
        override suspend fun getFinishedNotUploaded(): List<String> = emptyList()
        override suspend fun getExistingWorkoutIds(): List<String> = emptyList()
        override suspend fun deleteWorkout(id: String) = Unit
        override suspend fun deleteSet(id: Long) = Unit
        override suspend fun deleteWorkoutExercise(id: Long) = Unit
    }

    private class FakeRoutineDao(private val routine: RoutineWithExercises?) : RoutineDao {
        val replacedExercises = mutableListOf<RoutineExerciseEntity>()

        override suspend fun getRoutineWithExercises(id: Long): RoutineWithExercises? = routine
        override suspend fun replaceRoutineExercises(routineId: Long, list: List<RoutineExerciseEntity>) {
            replacedExercises += list
        }

        override fun observeRoutinesWithCount(): Flow<List<RoutineWithCount>> = flowOf(emptyList())
        override fun observeRoutinesFull(): Flow<List<RoutineWithExercises>> = flowOf(emptyList())
        override suspend fun getRoutineName(id: Long): String? = routine?.routine?.name
        override suspend fun upsertRoutine(routine: RoutineEntity): Long = 0
        override suspend fun deleteRoutine(id: Long) = Unit
        override suspend fun insertRoutineExercises(routineExercises: List<RoutineExerciseEntity>): List<Long> = emptyList()
        override suspend fun deleteRoutineExercises(routineId: Long) = Unit
    }
}
