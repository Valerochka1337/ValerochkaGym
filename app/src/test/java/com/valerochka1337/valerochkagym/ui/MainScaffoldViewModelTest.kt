package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import com.valerochka1337.valerochkagym.ui.navigation.MainScaffoldViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [MainScaffoldViewModel]: плашка «тренировка идёт» — что показывается между
 * подходами и во время отдыха.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainScaffoldViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `no active workout means no banner`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = viewModel(active = null)
            collectBanner(viewModel)

            assertNull(viewModel.banner.value)
        }

    @Test
    fun `between sets the banner shows the current set and its position`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = viewModel(active = workoutFull())
            collectBanner(viewModel)

            val banner = viewModel.banner.value!!
            assertEquals("Жим лёжа", banner.exerciseName)
            // Текущий подход — первый невыполненный, второй по счёту из двух.
            assertEquals(2, banner.setNumber)
            assertEquals(2, banner.setsInExercise)
            assertEquals("80×10", banner.setSummary)
            assertNull(banner.rest)
        }

    @Test
    fun `during rest the banner shows the just-completed set with the live countdown`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val engine = RestTimerEngine(backgroundScope) { testScheduler.currentTime }
            val viewModel = viewModel(active = workoutFull(), engine = engine)
            collectBanner(viewModel)

            engine.start(90)

            val banner = viewModel.banner.value!!
            // Во время отдыха показывается только что закрытый подход.
            assertEquals("80×8", banner.setSummary)
            assertEquals(90, banner.rest?.totalSec)
        }

    private fun TestScope.viewModel(
        active: WorkoutFull?,
        engine: RestTimerEngine = RestTimerEngine(backgroundScope) { testScheduler.currentTime },
    ): MainScaffoldViewModel = MainScaffoldViewModel(FakeActiveWorkoutRepository(active), engine)

    private fun TestScope.collectBanner(viewModel: MainScaffoldViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.banner.collect { }
        }
    }

    /** Жим лёжа: первый подход выполнен (80×8), второй — текущий (80×10). */
    private fun workoutFull(): WorkoutFull = WorkoutFull(
        workout = WorkoutEntity(id = "w1", name = "Грудь", startedAt = 0L),
        exercises = listOf(
            WorkoutExerciseWithSets(
                workoutExercise = WorkoutExerciseEntity(id = 1, workoutId = "w1", exerciseId = 1, position = 0),
                exercise = ExerciseEntity(id = 1, name = "Жим лёжа", muscleGroup = MuscleGroup.CHEST, type = ExerciseType.STRENGTH),
                sets = listOf(
                    WorkoutSetEntity(id = 10, workoutExerciseId = 1, setIndex = 0, weightKg = 80.0, reps = 8, isCompleted = true, completedAt = 1_000L),
                    WorkoutSetEntity(id = 11, workoutExerciseId = 1, setIndex = 1, weightKg = 80.0, reps = 10),
                ),
            ),
        ),
    )

    private class FakeActiveWorkoutRepository(active: WorkoutFull?) : ActiveWorkoutRepository {
        private val state = MutableStateFlow(active)
        override fun observeActive(): Flow<WorkoutFull?> = state
        override suspend fun startFromRoutine(routineId: Long): String = "w1"
        override suspend fun startEmpty(): String = "w1"
        override suspend fun getSet(setId: Long): WorkoutSetEntity? = null
        override suspend fun updateSet(set: WorkoutSetEntity) = Unit
        override suspend fun toggleSetCompleted(setId: Long, completed: Boolean) = Unit
        override suspend fun addSet(workoutExerciseId: Long) = Unit
        override suspend fun deleteSet(setId: Long) = Unit
        override suspend fun addExercise(workoutId: String, exerciseId: Long): Long = 0
        override suspend fun deleteExercise(workoutExerciseId: Long) = Unit
        override suspend fun finish(workoutId: String) = Unit
        override suspend fun discard(workoutId: String) = Unit
    }
}
