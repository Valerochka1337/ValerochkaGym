package com.valerochka1337.valerochkagym.ui.active

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.domain.PreviousSetsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Состояние экрана активной тренировки. [loading] отличает «ещё не загрузили из БД» от
 * «активной тренировки нет» ([workout] == null && !loading). [elapsedSeconds] тикает от
 * startedAt каждую секунду, пока экран подписан. [previousByExercise] — сводка «прошлый: …»
 * по exerciseId (пустая строка = прошлого нет).
 */
data class ActiveWorkoutUiState(
    val loading: Boolean = true,
    val workout: WorkoutFull? = null,
    val elapsedSeconds: Long = 0L,
    val previousByExercise: Map<Long, String> = emptyMap(),
)

/** Навигационные события экрана активной тренировки. */
sealed interface ActiveWorkoutEvent {
    data class NavigateToSummary(val workoutId: String) : ActiveWorkoutEvent
    data object NavigateHome : ActiveWorkoutEvent
}

/**
 * Бэкенд экрана активной тренировки. Старт тренировки происходит на вкладке «Тренировки»
 * (см. WorkoutsViewModel); сюда состояние приходит само через [ActiveWorkoutRepository.observeActive].
 * Шаговые правки значений и завершение/отмена делегируются в репозиторий, состояние
 * перечитывается реактивно.
 */
@HiltViewModel
class ActiveWorkoutViewModel @Inject constructor(
    private val repository: ActiveWorkoutRepository,
    private val previousSetsUseCase: PreviousSetsUseCase,
) : ViewModel() {

    private val loaded = MutableStateFlow(false)
    private val previousSummaries = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val loadingPrevious = mutableSetOf<Long>()

    private val activeWorkout: StateFlow<WorkoutFull?> = repository.observeActive()
        .onEach { workout ->
            loaded.value = true
            ensurePreviousLoaded(workout)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    private val tickerFlow: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }

    val uiState: StateFlow<ActiveWorkoutUiState> = combine(
        activeWorkout,
        tickerFlow,
        previousSummaries,
        loaded,
    ) { workout, nowMillis, previous, isLoaded ->
        ActiveWorkoutUiState(
            loading = !isLoaded,
            workout = workout,
            elapsedSeconds = workout
                ?.let { ((nowMillis - it.workout.startedAt) / 1000).coerceAtLeast(0) }
                ?: 0L,
            previousByExercise = previous,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = ActiveWorkoutUiState(),
    )

    private val _events = Channel<ActiveWorkoutEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // --- Шаговые изменения значений подхода (кнопки ± на карточке текущего подхода). ---

    /** Вес: обычный тап ±2.5, долгое нажатие ±0.5. Не уходит ниже нуля. */
    fun stepWeight(setId: Long, delta: Double) = mutateSet(setId) {
        it.copy(weightKg = ((it.weightKg ?: 0.0) + delta).coerceAtLeast(0.0).round2())
    }

    /** Повторы: ±1, не ниже нуля. */
    fun stepReps(setId: Long, delta: Int) = mutateSet(setId) {
        it.copy(reps = ((it.reps ?: 0) + delta).coerceAtLeast(0))
    }

    /** Длительность: ±15 сек, не ниже нуля. */
    fun stepDuration(setId: Long, delta: Int) = mutateSet(setId) {
        it.copy(durationSec = ((it.durationSec ?: 0) + delta).coerceAtLeast(0))
    }

    /** Скорость: ±0.5, не ниже нуля. */
    fun stepSpeed(setId: Long, delta: Double) = mutateSet(setId) {
        it.copy(speedKmh = ((it.speedKmh ?: 0.0) + delta).coerceAtLeast(0.0).round2())
    }

    /** Наклон: ±0.5, не ниже нуля. */
    fun stepIncline(setId: Long, delta: Double) = mutateSet(setId) {
        it.copy(inclinePct = ((it.inclinePct ?: 0.0) + delta).coerceAtLeast(0.0).round2())
    }

    /** Полное обновление подхода из клавиатурного ввода (NumberField). */
    fun setSetValue(set: WorkoutSetEntity) {
        viewModelScope.launch { repository.updateSet(set) }
    }

    fun completeSet(setId: Long) {
        viewModelScope.launch {
            repository.toggleSetCompleted(setId, true)
            onSetCompleted(setId)
        }
    }

    fun uncompleteSet(setId: Long) {
        viewModelScope.launch { repository.toggleSetCompleted(setId, false) }
    }

    fun addSet(workoutExerciseId: Long) {
        viewModelScope.launch { repository.addSet(workoutExerciseId) }
    }

    fun deleteSet(setId: Long) {
        viewModelScope.launch { repository.deleteSet(setId) }
    }

    /** Добавляет упражнение по id (после выбора в библиотеке-пикере). */
    fun addExerciseById(exerciseId: Long) {
        val workoutId = activeWorkout.value?.workout?.id ?: return
        viewModelScope.launch { repository.addExercise(workoutId, exerciseId) }
    }

    fun deleteExercise(workoutExerciseId: Long) {
        viewModelScope.launch { repository.deleteExercise(workoutExerciseId) }
    }

    fun finish() {
        val workoutId = activeWorkout.value?.workout?.id ?: return
        viewModelScope.launch {
            repository.finish(workoutId)
            _events.send(ActiveWorkoutEvent.NavigateToSummary(workoutId))
        }
    }

    fun discard() {
        val workoutId = activeWorkout.value?.workout?.id ?: return
        viewModelScope.launch {
            repository.discard(workoutId)
            _events.send(ActiveWorkoutEvent.NavigateHome)
        }
    }

    /**
     * Точка подключения таймера отдыха (Стадия 13): вызывается сразу после отметки подхода
     * выполненным. Сейчас пусто — реальный движок таймера подключит следующая стадия.
     */
    private fun onSetCompleted(setId: Long) {
        // Rest-timer engine wired in Stage 13.
    }

    private fun mutateSet(setId: Long, transform: (WorkoutSetEntity) -> WorkoutSetEntity) {
        val set = currentSet(setId) ?: return
        viewModelScope.launch { repository.updateSet(transform(set)) }
    }

    private fun currentSet(setId: Long): WorkoutSetEntity? =
        activeWorkout.value?.exercises?.firstNotNullOfOrNull { exercise ->
            exercise.sets.firstOrNull { it.id == setId }
        }

    private fun ensurePreviousLoaded(workout: WorkoutFull?) {
        val exercises = workout?.exercises ?: return
        for (exercise in exercises) {
            val exerciseId = exercise.exercise.id
            if (previousSummaries.value.containsKey(exerciseId) || exerciseId in loadingPrevious) {
                continue
            }
            loadingPrevious += exerciseId
            viewModelScope.launch {
                val sets = previousSetsUseCase(exerciseId)
                val summary = previousSetsUseCase.formatSummary(sets, exercise.exercise.type)
                previousSummaries.update { it + (exerciseId to summary) }
                loadingPrevious -= exerciseId
            }
        }
    }
}

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** Округление веса/скорости/наклона до сотых, чтобы шаги ±0.5/±2.5 не накапливали дрейф double. */
private fun Double.round2(): Double = (this * 100).roundToInt() / 100.0
