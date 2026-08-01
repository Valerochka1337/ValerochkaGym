package com.valerochka1337.valerochkagym.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Одна карточка завершённой тренировки в списке истории. [volume] == null — объём отсутствует
 * (силовых подходов не было), строку объёма скрываем.
 */
data class HistoryItemUi(
    val id: String,
    val name: String,
    val date: String,
    val duration: String,
    val volume: String?,
    val uploadStatus: UploadStatus,
)

/**
 * Состояние вкладки «История». [workouts] == null означает «ещё не загружено» (в отличие от
 * загруженного пустого списка), чтобы не мигало пустое состояние.
 */
data class HistoryUiState(
    val workouts: List<HistoryItemUi>? = null,
)

/**
 * Бэкенд вкладки «История»: объединяет список завершённых тренировок с агрегатом объёма
 * (один запрос на все тренировки, без загрузки полных деревьев).
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    workoutDao: WorkoutDao,
) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> =
        combine(
            workoutDao.observeFinishedWorkouts(),
            workoutDao.observeWorkoutVolumes(),
        ) { workouts, volumes ->
            val volumeByWorkout = volumes.associate { it.workoutId to it.volume }
            val items = workouts.map { workout ->
                val duration = (workout.finishedAt ?: workout.startedAt) - workout.startedAt
                HistoryItemUi(
                    id = workout.id,
                    name = workout.name,
                    date = formatWorkoutDate(workout.startedAt),
                    duration = formatDuration(duration),
                    volume = formatVolume(volumeByWorkout[workout.id] ?: 0.0),
                    uploadStatus = workout.uploadStatus,
                )
            }
            HistoryUiState(workouts = items)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryUiState(),
        )
}
