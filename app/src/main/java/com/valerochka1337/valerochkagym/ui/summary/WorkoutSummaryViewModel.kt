package com.valerochka1337.valerochkagym.ui.summary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.data.sortedWorkoutFull
import com.valerochka1337.valerochkagym.domain.PrResult
import com.valerochka1337.valerochkagym.domain.PreviousSetsUseCase
import com.valerochka1337.valerochkagym.domain.RoutineUpdateUseCase
import com.valerochka1337.valerochkagym.domain.WorkoutStatsUseCase
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Сводка одного упражнения в итогах: имя и краткая строка выполненных подходов. [id] — ключ списка. */
data class ExerciseSummaryUi(
    val id: Long,
    val exerciseId: Long,
    val name: String,
    val variantName: String? = null,
    val variantSyncId: String? = null,
    val setsSummary: String,
)

/**
 * Состояние экрана итогов. [showUpdateRoutineDialog] управляет разовым предложением
 * «обновить программу» — флаг живёт в VM и сбрасывается при применении/отклонении, поэтому
 * диалог показывается один раз.
 *
 * Оговорка: «один раз» — в пределах одного экземпляра VM. После смерти процесса VM создаётся
 * заново, и если расхождение с программой ещё есть, диалог покажется снова — это допустимо.
 */
data class WorkoutSummaryUiState(
    val loading: Boolean = true,
    val workoutName: String = "",
    val durationSeconds: Long = 0L,
    val volumeKg: Double = 0.0,
    val prs: List<PrResult> = emptyList(),
    val exercises: List<ExerciseSummaryUi> = emptyList(),
    val showUpdateRoutineDialog: Boolean = false,
)

/**
 * Бэкенд экрана итогов завершённой тренировки. По workoutId из [SavedStateHandle] грузит
 * тренировку, считает длительность/объём/рекорды и проверяет расхождение с программой.
 */
@HiltViewModel
class WorkoutSummaryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workoutDao: WorkoutDao,
    private val statsUseCase: WorkoutStatsUseCase,
    private val routineUpdateUseCase: RoutineUpdateUseCase,
    private val previousSetsUseCase: PreviousSetsUseCase,
) : ViewModel() {

    private val workoutId: String? = savedStateHandle[GymRoutes.WORKOUT_ID_ARG]

    private val _uiState = MutableStateFlow(WorkoutSummaryUiState())
    val uiState: StateFlow<WorkoutSummaryUiState> = _uiState.asStateFlow()

    /** Загруженная тренировка — источник для applyToRoutine при подтверждении диалога. */
    private var workout: WorkoutFull? = null

    init {
        load()
    }

    private fun load() {
        val id = workoutId ?: run {
            _uiState.update { it.copy(loading = false) }
            return
        }
        viewModelScope.launch {
            val full = workoutDao.getWorkoutFull(id)?.let(::sortedWorkoutFull) ?: run {
                _uiState.update { it.copy(loading = false) }
                return@launch
            }
            workout = full
            val volume = statsUseCase.volume(full)
            val prs = statsUseCase.newPrs(full)
            val diverged = routineUpdateUseCase.hasDiverged(full)
            val duration = ((full.workout.finishedAt ?: full.workout.startedAt) - full.workout.startedAt) / 1000
            val exercises = full.exercises.map { exercise ->
                ExerciseSummaryUi(
                    id = exercise.workoutExercise.id,
                    exerciseId = exercise.exercise.id,
                    name = exercise.exercise.name,
                    variantName = exercise.workoutExercise.variantNameSnapshot,
                    variantSyncId = exercise.workoutExercise.variantSyncId,
                    setsSummary = previousSetsUseCase.formatSummary(
                        exercise.sets.filter { it.isCompleted },
                        exercise.exercise.type,
                    ),
                )
            }
            _uiState.value = WorkoutSummaryUiState(
                loading = false,
                workoutName = full.workout.name,
                durationSeconds = duration.coerceAtLeast(0),
                volumeKg = volume,
                prs = prs,
                exercises = exercises,
                showUpdateRoutineDialog = diverged,
            )
        }
    }

    fun applyRoutineUpdate() {
        val full = workout ?: return
        viewModelScope.launch {
            routineUpdateUseCase.applyToRoutine(full)
            _uiState.update { it.copy(showUpdateRoutineDialog = false) }
        }
    }

    fun dismissRoutineUpdate() {
        _uiState.update { it.copy(showUpdateRoutineDialog = false) }
    }
}
