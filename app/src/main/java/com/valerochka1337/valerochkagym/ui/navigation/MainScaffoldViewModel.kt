package com.valerochka1337.valerochkagym.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.domain.currentFocus
import com.valerochka1337.valerochkagym.domain.formatSet
import com.valerochka1337.valerochkagym.domain.lastCompletedFocus
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import com.valerochka1337.valerochkagym.service.RestTimerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Содержимое плашки «тренировка идёт» на вкладках. null — активной тренировки нет, плашки не будет.
 *
 * [exerciseName]/[setSummary] описывают подход, который сейчас важен: во время отдыха это только что
 * закрытый подход (его же правит кнопка «Изменить» в уведомлении), иначе — текущий. [setNumber] и
 * [setsInExercise] всегда про текущий подход, поэтому во время отдыха они уже показывают следующий.
 */
data class SessionBannerState(
    val exerciseName: String?,
    val setSummary: String?,
    val setNumber: Int?,
    val setsInExercise: Int?,
    val rest: RestTimerState?,
)

/**
 * Бэкенд оболочки приложения: плашка «вернуться к тренировке» на вкладках. Подписана прямо на
 * [RestTimerEngine], поэтому отсчёт в плашке живой — собственный тикер не нужен, движок и так
 * эмитит раз в секунду.
 */
@HiltViewModel
class MainScaffoldViewModel @Inject constructor(
    repository: ActiveWorkoutRepository,
    restTimerEngine: RestTimerEngine,
) : ViewModel() {

    val banner: StateFlow<SessionBannerState?> =
        combine(repository.observeActive(), restTimerEngine.state) { workout, rest ->
            if (workout == null) return@combine null
            val next = workout.currentFocus()
            val shown = if (rest != null) workout.lastCompletedFocus() ?: next else next
            SessionBannerState(
                exerciseName = shown?.exerciseName,
                setSummary = shown?.let { formatSet(it.set, it.type) },
                setNumber = next?.setNumber,
                setsInExercise = next?.setsInExercise,
                rest = rest,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)
}

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
