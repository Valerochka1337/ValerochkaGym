package com.valerochka1337.valerochkagym.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.domain.analysis.AnalysisPeriod
import com.valerochka1337.valerochkagym.domain.analysis.AnalyticsEngine
import com.valerochka1337.valerochkagym.domain.analysis.AnalyticsInput
import com.valerochka1337.valerochkagym.domain.analysis.AnalyticsReport
import com.valerochka1337.valerochkagym.domain.analysis.ExerciseProgress
import com.valerochka1337.valerochkagym.domain.analysis.MuscleLoadSummary
import com.valerochka1337.valerochkagym.di.ComputeDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Что откладывать по вертикали на недельном графике. */
enum class WeeklyMetric { SETS, TONNAGE }

/**
 * Состояние вкладки. [report] пересчитывается только при смене данных или периода; выбор мышцы
 * и упражнения живут отдельными полями, чтобы тап по карте не пересчитывал аналитику.
 */
data class AnalysisUiState(
    val loading: Boolean = true,
    val report: AnalyticsReport = AnalyticsReport.empty(AnalysisPeriod.LAST_7_DAYS),
    val period: AnalysisPeriod = AnalysisPeriod.LAST_7_DAYS,
    val selectedMuscle: Muscle? = null,
    val selectedExerciseId: Long? = null,
    val weeklyMetric: WeeklyMetric = WeeklyMetric.SETS,
    val selectedWeekIndex: Int? = null,
    val selectedSessionIndex: Int? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Подробности выбранной мышцы — источник чисел под картой тела. */
    val selectedMuscleLoad: MuscleLoadSummary?
        get() = selectedMuscle?.let { muscle -> report.muscleLoads.firstOrNull { it.muscle == muscle } }

    /**
     * Показываемое упражнение: выбранное или самое частое. Без фоллбэка карточка прогресса была
     * бы пустой до первого тапа, хотя данные уже есть.
     */
    val shownExercise: ExerciseProgress?
        get() = report.exercises.firstOrNull { it.exerciseId == selectedExerciseId }
            ?: report.exercises.firstOrNull()
}

/**
 * Бэкенд вкладки «Анализы»: собирает вход аналитики из трёх реактивных источников (выполненные
 * подходы, завершённые тренировки, карта мышц) и отдаёт готовый отчёт [AnalyticsEngine].
 *
 * Вся математика — в движке, здесь только склейка потоков и состояние выбора. «Сейчас» берётся
 * в момент пересчёта: вкладка живёт недолго, а сутки во время просмотра не меняются.
 */
@HiltViewModel
class AnalysisViewModel @Inject constructor(
    private val workoutDao: WorkoutDao,
    private val exerciseMuscleDao: ExerciseMuscleDao,
    private val engine: AnalyticsEngine,
    @param:ComputeDispatcher private val computeDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    // Намеренно не SavedStateHandle и не DataStore: новый экран всегда начинает с последних 7 дней.
    private val period = MutableStateFlow<AnalysisPeriod>(AnalysisPeriod.LAST_7_DAYS)
    private val selectedMuscle = MutableStateFlow<Muscle?>(null)
    private val selectedExerciseId = MutableStateFlow<Long?>(null)
    private val weeklyMetric = MutableStateFlow(WeeklyMetric.SETS)
    private val selectedWeekIndex = MutableStateFlow<Int?>(null)
    private val selectedSessionIndex = MutableStateFlow<Int?>(null)

    private val selection = combine(
        selectedMuscle,
        selectedExerciseId,
        weeklyMetric,
        selectedWeekIndex,
        selectedSessionIndex,
    ) { muscle, exerciseId, metric, weekIndex, sessionIndex ->
        Selection(muscle, exerciseId, metric, weekIndex, sessionIndex)
    }

    private val reportFlow = combine(
        workoutDao.observeCompletedSets(),
        workoutDao.observeFinishedWorkouts(),
        exerciseMuscleDao.observeAll(),
        period,
    ) { sets, workouts, muscleRows, selectedPeriod ->
        val muscleMap = muscleRows
            .groupBy { it.exerciseId }
            .mapValues { (_, rows) -> rows.map { MuscleLoad(it.muscle, it.contribution) } }
        engine.analyze(
            AnalyticsInput(
                sets = sets,
                workouts = workouts,
                muscleMap = muscleMap,
                nowMillis = System.currentTimeMillis(),
                zone = zone,
            ),
            selectedPeriod,
        )
    }
        // Пересчёт отчёта — O(история × подходы), уводим с Main: combine-трансформа иначе
        // исполнялась бы на Dispatchers.Main.immediate через stateIn(viewModelScope).
        .flowOn(computeDispatcher)

    val uiState: StateFlow<AnalysisUiState> =
        combine(reportFlow, selection) { report, current ->
            AnalysisUiState(
                loading = false,
                report = report,
                period = report.period,
                selectedMuscle = current.muscle,
                selectedExerciseId = current.exerciseId,
                weeklyMetric = current.metric,
                selectedWeekIndex = current.weekIndex,
                selectedSessionIndex = current.sessionIndex,
                zone = zone,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AnalysisUiState(),
        )

    fun onPeriodSelected(value: AnalysisPeriod) {
        period.value = value
        // Индексы точек привязаны к длине серии — при смене окна они теряют смысл.
        selectedWeekIndex.value = null
        selectedSessionIndex.value = null
    }

    fun onCustomRangeSelected(start: LocalDate, endInclusive: LocalDate) {
        onPeriodSelected(AnalysisPeriod.Custom(start, endInclusive))
    }

    /** Повторный тап по той же мышце снимает выбор — как в фильтрах библиотеки. */
    fun onMuscleClicked(muscle: Muscle?) {
        selectedMuscle.value = if (muscle == null || selectedMuscle.value == muscle) null else muscle
    }

    fun onExerciseSelected(exerciseId: Long) {
        selectedExerciseId.value = exerciseId
        selectedSessionIndex.value = null
    }

    fun onWeeklyMetricSelected(value: WeeklyMetric) {
        weeklyMetric.value = value
        selectedWeekIndex.value = null
    }

    fun onWeekSelected(index: Int?) {
        selectedWeekIndex.value = index
    }

    fun onSessionSelected(index: Int?) {
        selectedSessionIndex.value = index
    }

    private data class Selection(
        val muscle: Muscle?,
        val exerciseId: Long?,
        val metric: WeeklyMetric,
        val weekIndex: Int?,
        val sessionIndex: Int?,
    )
}
