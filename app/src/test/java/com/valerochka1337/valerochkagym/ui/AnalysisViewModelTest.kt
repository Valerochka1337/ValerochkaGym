package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.domain.analysis.AnalysisPeriod
import com.valerochka1337.valerochkagym.domain.analysis.AnalyticsEngine
import com.valerochka1337.valerochkagym.domain.analysis.VolumeZone
import com.valerochka1337.valerochkagym.ui.analysis.AnalysisViewModel
import com.valerochka1337.valerochkagym.ui.analysis.WeeklyMetric
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Тесты [AnalysisViewModel] поверх настоящей Room-базы: заодно проверяют SQL-проекцию
 * `observeCompletedSets` — именно она отбирает выполненные подходы завершённых тренировок
 * и подставляет время старта вместо отсутствующего `completedAt`.
 *
 * Математику проверяет `AnalyticsEngineTest`; здесь важна склейка потоков и состояние выбора.
 * Поток `uiState` собран через `stateIn(WhileSubscribed)`, поэтому каждый тест сначала цепляет
 * живого подписчика через [collect].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisViewModelTest : RoomDaoTest() {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = System.currentTimeMillis()
    private val day = 86_400_000L

    private var benchId: Long = 0

    @Before
    fun seedCatalogue() = runTest {
        benchId = db.exerciseDao().insert(
            ExerciseEntity(name = "Жим лёжа", muscleGroup = MuscleGroup.CHEST, type = ExerciseType.STRENGTH),
        )
        db.exerciseMuscleDao().upsertAll(
            listOf(
                ExerciseMuscleEntity(benchId, Muscle.CHEST, 100),
                ExerciseMuscleEntity(benchId, Muscle.TRICEPS, 65),
            ),
        )
    }

    @Test
    fun `an empty database reports no data`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = viewModel()
        collect(viewModel)

        assertFalse(viewModel.uiState.value.report.hasData)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `completed sets of a finished workout reach the report`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            insertSession(daysAgo = 1, sets = 3)
            val viewModel = viewModel()
            collect(viewModel)

            val report = viewModel.uiState.value.report
            assertTrue(report.hasData)
            assertEquals(3.0, report.totalHardSets, 1e-6)
            assertEquals(1, report.sessions)
            assertEquals(3 * 100.0 * 5, report.totalTonnageKg, 1e-6)
            assertEquals("Жим лёжа", report.exercises.single().name)
        }

    @Test
    fun `an unfinished workout is invisible to analytics`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val workoutId = "active"
            insertWorkout(workoutId, startedAt = now - day, finishedAt = null)
            val workoutExerciseId = insertWorkoutExercise(workoutId, benchId)
            insertSet(workoutExerciseId, setIndex = 0, weightKg = 100.0, reps = 5, isCompleted = true)

            val viewModel = viewModel()
            collect(viewModel)

            // Объём незавершённой тренировки ещё меняется — в аналитику он не идёт.
            assertFalse(viewModel.uiState.value.report.hasData)
        }

    @Test
    fun `unchecked sets never reach the report`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val workoutId = "w"
        insertWorkout(workoutId, startedAt = now - day, finishedAt = now - day + 3_600_000)
        val workoutExerciseId = insertWorkoutExercise(workoutId, benchId)
        insertSet(workoutExerciseId, setIndex = 0, weightKg = 100.0, reps = 5, isCompleted = true)
        insertSet(workoutExerciseId, setIndex = 1, weightKg = 100.0, reps = 5, isCompleted = false)

        val viewModel = viewModel()
        collect(viewModel)

        assertEquals(1.0, viewModel.uiState.value.report.totalHardSets, 1e-6)
    }

    @Test
    fun `the muscle map turns into per-muscle load`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        insertSession(daysAgo = 1, sets = 4)
        val viewModel = viewModel()
        collect(viewModel)

        val loads = viewModel.uiState.value.report.muscleLoads.associateBy { it.muscle }
        assertEquals(4.0, loads.getValue(Muscle.CHEST).totalSets, 1e-6)
        assertEquals(4.0, loads.getValue(Muscle.TRICEPS).totalSets, 1e-6)
        assertEquals(VolumeZone.LOW, loads.getValue(Muscle.CALVES).zone)
    }

    @Test
    fun `switching the period recomputes the report and clears point selection`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            insertSession(daysAgo = 1, sets = 2)
            insertSession(daysAgo = 100, sets = 5)
            val viewModel = viewModel()
            collect(viewModel)
            viewModel.onWeekSelected(0)

            val withSelectedWeek = viewModel.uiState.first { it.selectedWeekIndex == 0 }
            assertEquals(2.0, withSelectedWeek.report.totalHardSets, 1e-6)

            viewModel.onPeriodSelected(AnalysisPeriod.ALL_TIME)

            val state = viewModel.uiState.first {
                it.period == AnalysisPeriod.ALL_TIME && it.selectedWeekIndex == null
            }
            assertEquals(AnalysisPeriod.ALL_TIME, state.period)
            assertEquals(7.0, state.report.totalHardSets, 1e-6)
            assertNull("выбор точки привязан к длине серии", state.selectedWeekIndex)
        }

    @Test
    fun `switching to a custom range recomputes the report and clears point selection`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            insertSession(daysAgo = 1, sets = 2)
            insertSession(daysAgo = 20, sets = 5)
            val viewModel = viewModel()
            collect(viewModel)
            viewModel.onWeekSelected(0)
            viewModel.uiState.first { it.selectedWeekIndex == 0 }

            val end = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().minusDays(14)
            val start = end.minusDays(6)
            viewModel.onCustomRangeSelected(start, end)

            val customPeriod = AnalysisPeriod.Custom(start, end)
            val state = viewModel.uiState.first {
                it.period == customPeriod && it.selectedWeekIndex == null
            }
            assertEquals(customPeriod, state.period)
            assertEquals(5.0, state.report.totalHardSets, 1e-6)
            assertNull("выбор точки привязан к длине новой серии", state.selectedWeekIndex)
        }

    @Test
    fun `tapping the same muscle twice clears the selection`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            insertSession(daysAgo = 1, sets = 1)
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onMuscleClicked(Muscle.CHEST)
            val selected = viewModel.uiState.first { it.selectedMuscle == Muscle.CHEST }
            assertEquals(Muscle.CHEST, selected.selectedMuscle)
            assertEquals(Muscle.CHEST, selected.selectedMuscleLoad?.muscle)

            viewModel.onMuscleClicked(Muscle.CHEST)
            val cleared = viewModel.uiState.first { it.selectedMuscle == null }
            assertNull(cleared.selectedMuscle)
        }

    @Test
    fun `the progress card falls back to the most frequent exercise`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            insertSession(daysAgo = 1, sets = 2)
            val viewModel = viewModel()
            collect(viewModel)

            val state = viewModel.uiState.value
            assertNull(state.selectedExerciseId)
            assertEquals("Жим лёжа", state.shownExercise?.name) // карточка не пустует до первого тапа
        }

    @Test
    fun `metric toggle is kept in state`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        insertSession(daysAgo = 1, sets = 1)
        val viewModel = viewModel()
        collect(viewModel)

        viewModel.onWeeklyMetricSelected(WeeklyMetric.TONNAGE)

        val state = viewModel.uiState.first { it.weeklyMetric == WeeklyMetric.TONNAGE }
        assertEquals(WeeklyMetric.TONNAGE, state.weeklyMetric)
    }

    // region helpers

    private fun viewModel() = AnalysisViewModel(
        workoutDao = db.workoutDao(),
        exerciseMuscleDao = db.exerciseMuscleDao(),
        engine = AnalyticsEngine(),
        // Тестовый диспетчер вместо Dispatchers.Default — пересчёт остаётся на виртуальном времени.
        computeDispatcher = mainDispatcherRule.testDispatcher,
    )

    /**
     * Цепляет живого подписчика и ждёт первый посчитанный отчёт.
     *
     * Room отдаёт Flow-запросы со своего исполнителя, то есть первая эмиссия приходит из другого
     * потока и не попадает в виртуальное время `runTest`. Поэтому её нужно именно дождаться
     * (`loading = false` бывает только после реальной эмиссии). Каскад `combine` + `stateIn` не обещает,
     * что публичный `uiState.value` обновится в той же строке, поэтому после событий тесты ждут
     * нужную эмиссию через `first`.
     */
    private suspend fun TestScope.collect(viewModel: AnalysisViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        viewModel.uiState.first { !it.loading }
    }

    /** Завершённая тренировка из [sets] отмеченных подходов 100 кг × 5. */
    private suspend fun insertSession(daysAgo: Long, sets: Int) {
        val startedAt = now - daysAgo * day
        val workoutId = "w-$daysAgo"
        insertWorkout(workoutId, startedAt = startedAt, finishedAt = startedAt + 3_600_000)
        val workoutExerciseId = insertWorkoutExercise(workoutId, benchId)
        repeat(sets) { index ->
            db.workoutDao().insertSet(
                WorkoutSetEntity(
                    workoutExerciseId = workoutExerciseId,
                    setIndex = index,
                    weightKg = 100.0,
                    reps = 5,
                    isCompleted = true,
                    completedAt = startedAt + index * 60_000L,
                ),
            )
        }
    }

    // endregion
}
