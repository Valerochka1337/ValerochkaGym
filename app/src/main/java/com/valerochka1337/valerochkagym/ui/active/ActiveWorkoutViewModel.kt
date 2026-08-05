package com.valerochka1337.valerochkagym.ui.active

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.domain.CompleteSetUseCase
import com.valerochka1337.valerochkagym.domain.PreviousSetsUseCase
import com.valerochka1337.valerochkagym.domain.WorkoutSetMutator
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import com.valerochka1337.valerochkagym.service.RestTimerState
import com.valerochka1337.valerochkagym.worker.UploadScheduler
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
import kotlin.time.Duration.Companion.milliseconds

/**
 * Состояние экрана активной тренировки. [loading] отличает «ещё не загрузили из БД» от
 * «активной тренировки нет» ([workout] == null && !loading). [elapsedSeconds] тикает от
 * startedAt каждую секунду, пока экран подписан. [previousByExercise] — сводка «прошлый: …»
 * по exerciseId (пустая строка = прошлого нет).
 */
data class ActiveWorkoutUiState(
    val loading: Boolean = true,
    val workout: WorkoutFull? = null,
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
 *
 * Правки подхода и закрытие подхода уходят в процессные [WorkoutSetMutator] и [CompleteSetUseCase]:
 * ровно те же операции доступны с кнопок уведомления в шторке, и писатель должен быть один на
 * процесс (иначе вернутся lost update'ы на быстрых тапах).
 */
@HiltViewModel
class ActiveWorkoutViewModel @Inject constructor(
    private val repository: ActiveWorkoutRepository,
    private val previousSetsUseCase: PreviousSetsUseCase,
    private val setMutator: WorkoutSetMutator,
    private val completeSetUseCase: CompleteSetUseCase,
    private val restTimerEngine: RestTimerEngine,
    private val uploadScheduler: UploadScheduler,
) : ViewModel() {

    /** Состояние таймера отдыха (null = неактивен) — пилюля на экране подписана прямо на движок. */
    val restTimer: StateFlow<RestTimerState?> = restTimerEngine.state

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
            delay(1000.milliseconds)
        }
    }

    val uiState: StateFlow<ActiveWorkoutUiState> = combine(
        activeWorkout,
        previousSummaries,
        loaded,
    ) { workout, previous, isLoaded ->
        ActiveWorkoutUiState(
            loading = !isLoaded,
            workout = workout,
            previousByExercise = previous,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = ActiveWorkoutUiState(),
    )

    /**
     * Секунды с начала тренировки — отдельный поток, чтобы посекундный тик не перерисовывал
     * весь [uiState] (его собирает только шапка экрана).
     */
    val elapsedSeconds: StateFlow<Long> = combine(activeWorkout, tickerFlow) { workout, nowMillis ->
        workout?.let { ((nowMillis - it.workout.startedAt) / 1000).coerceAtLeast(0) } ?: 0L
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = 0L,
    )

    private val _events = Channel<ActiveWorkoutEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // --- Шаговые изменения значений подхода (кнопки ± на карточке текущего подхода). ---

    /** Вес: обычный тап ±2.5, долгое нажатие ±0.5. Не уходит ниже нуля. */
    fun stepWeight(setId: Long, delta: Double) = setMutator.stepWeight(setId, delta)

    /** Повторы: ±1, не ниже нуля. */
    fun stepReps(setId: Long, delta: Int) = setMutator.stepReps(setId, delta)

    /** Длительность: ±15 сек, не ниже нуля. */
    fun stepDuration(setId: Long, delta: Int) = setMutator.stepDuration(setId, delta)

    /** Скорость: ±0.5, не ниже нуля. */
    fun stepSpeed(setId: Long, delta: Double) = setMutator.stepSpeed(setId, delta)

    /** Наклон: ±0.5, не ниже нуля. */
    fun stepIncline(setId: Long, delta: Double) = setMutator.stepIncline(setId, delta)

    // --- Клавиатурный ввод (NumberField): правит одно поле поверх свежего состояния подхода. ---

    fun setWeight(setId: Long, raw: String) = setMutator.setWeight(setId, raw)

    fun setReps(setId: Long, raw: String) = setMutator.setReps(setId, raw)

    fun setDuration(setId: Long, raw: String) = setMutator.setDuration(setId, raw)

    fun setSpeed(setId: Long, raw: String) = setMutator.setSpeed(setId, raw)

    fun setIncline(setId: Long, raw: String) = setMutator.setIncline(setId, raw)

    /** Отмечает подход выполненным и запускает отдых (та же операция, что кнопка в уведомлении). */
    fun completeSet(setId: Long) {
        viewModelScope.launch { completeSetUseCase(setId) }
    }

    /** Прибавить/убавить время текущего отдыха (кнопки ±15 на пилюле). */
    fun addRestSeconds(delta: Int) = restTimerEngine.addSeconds(delta)

    /** Пропустить отдых (тап по центру пилюли). */
    fun skipRest() = restTimerEngine.skip()

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

    /** Сохраняет итоговый порядок упражнений после отпускания drag-handle. */
    fun reorderExercises(orderedWorkoutExerciseIds: List<Long>) {
        val workoutId = activeWorkout.value?.workout?.id ?: return
        viewModelScope.launch {
            repository.reorderExercises(workoutId, orderedWorkoutExerciseIds)
        }
    }

    fun finish() {
        val workoutId = activeWorkout.value?.workout?.id ?: return
        viewModelScope.launch {
            repository.finish(workoutId)
            uploadScheduler.schedule(workoutId)
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
