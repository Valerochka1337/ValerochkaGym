package com.valerochka1337.valerochkagym.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.sortedWorkoutFull
import com.valerochka1337.valerochkagym.domain.PreviousSetsUseCase
import com.valerochka1337.valerochkagym.domain.WorkoutStatsUseCase
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Одна строка подхода в деталях: «1 · 80×8», флаг выполнения — для галочки и приглушения. */
data class DetailSetUi(
    val number: Int,
    val summary: String,
    val completed: Boolean,
)

/** Упражнение в деталях: имя, мышечная группа и строки подходов. */
data class DetailExerciseUi(
    val name: String,
    val muscleGroup: String,
    val sets: List<DetailSetUi>,
)

/**
 * Состояние детального экрана тренировки. Следуем паттерну экрана итогов: [loading] вместо
 * nullable-полей, т.к. это загрузка одного объекта, а не списка.
 */
data class WorkoutDetailUiState(
    val loading: Boolean = true,
    val name: String = "",
    val dateTime: String = "",
    val duration: String = "",
    val volume: String? = null,
    val uploadStatus: UploadStatus = UploadStatus.PENDING,
    val uploadError: String? = null,
    val note: String = "",
    val exercises: List<DetailExerciseUi> = emptyList(),
)

/**
 * Бэкенд детального экрана тренировки. По workoutId из [SavedStateHandle] грузит дерево тренировки,
 * считает длительность/объём и раскладывает упражнения с подходами. Удаление шлёт событие
 * [deleteEvents], по которому экран возвращается назад.
 */
@HiltViewModel
class WorkoutDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val workoutDao: WorkoutDao,
    private val statsUseCase: WorkoutStatsUseCase,
    private val previousSetsUseCase: PreviousSetsUseCase,
) : ViewModel() {

    private val workoutId: String? = savedStateHandle[GymRoutes.WORKOUT_ID_ARG]

    private val _uiState = MutableStateFlow(WorkoutDetailUiState())
    val uiState: StateFlow<WorkoutDetailUiState> = _uiState.asStateFlow()

    private val _deleteEvents = Channel<Unit>(Channel.BUFFERED)

    /** Событие «тренировка удалена» — экран возвращается на список истории. */
    val deleteEvents = _deleteEvents.receiveAsFlow()

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
            val duration = (full.workout.finishedAt ?: full.workout.startedAt) - full.workout.startedAt
            val exercises = full.exercises.map { exercise ->
                DetailExerciseUi(
                    name = exercise.exercise.name,
                    muscleGroup = exercise.exercise.muscleGroup.displayName(),
                    sets = exercise.sets.mapIndexed { index, set ->
                        DetailSetUi(
                            number = index + 1,
                            summary = previousSetsUseCase.formatSummary(listOf(set), exercise.exercise.type),
                            completed = set.isCompleted,
                        )
                    },
                )
            }
            _uiState.value = WorkoutDetailUiState(
                loading = false,
                name = full.workout.name,
                dateTime = formatWorkoutDateTime(full.workout.startedAt),
                duration = formatDuration(duration),
                volume = formatVolume(statsUseCase.volume(full)),
                uploadStatus = full.workout.uploadStatus,
                uploadError = full.workout.uploadError,
                note = full.workout.note,
                exercises = exercises,
            )
        }
    }

    fun delete() {
        val id = workoutId ?: return
        viewModelScope.launch {
            workoutDao.deleteWorkout(id)
            _deleteEvents.send(Unit)
        }
    }
}
