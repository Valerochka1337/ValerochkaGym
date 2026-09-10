package com.valerochka1337.valerochkagym.ui.active

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutUnavailableException
import com.valerochka1337.valerochkagym.domain.CompleteSetUseCase
import com.valerochka1337.valerochkagym.domain.CompletedSetEditResult
import com.valerochka1337.valerochkagym.domain.CompletedSetNumbers
import com.valerochka1337.valerochkagym.domain.PreviousSetsUseCase
import com.valerochka1337.valerochkagym.domain.RoutineGymConflictException
import com.valerochka1337.valerochkagym.domain.WorkoutSetMutator
import com.valerochka1337.valerochkagym.domain.currentFocus
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import com.valerochka1337.valerochkagym.service.RestTimerState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateConnectionState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateDevice
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateMonitor
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateReading
import com.valerochka1337.valerochkagym.worker.UploadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
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

/**
 * Состояние экрана активной тренировки. [loading] отличает «ещё не загрузили из БД» от «активной
 * тренировки нет» ([workout] == null && !loading). elapsedSeconds тикает от startedAt каждую
 * секунду, пока экран подписан. [previousByExercise] — сводка «прошлый: …» по exerciseId (пустая
 * строка = прошлого нет).
 */
data class ActiveWorkoutUiState(
    val loading: Boolean = true,
    val workout: WorkoutFull? = null,
    val previousByExercise: Map<Long, String> = emptyMap(),
    val completedSetEdit: CompletedSetEditDraft? = null,
)

/** Immutable, saveable numeric draft for a completed set. Submission feedback is process-local. */
data class CompletedSetEditDraft(
    val setId: Long,
    val type: ExerciseType,
    val token: Long,
    val weightKg: String = "",
    val reps: String = "",
    val durationSec: String = "",
    val speedKmh: String = "",
    val inclinePct: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

/** Навигационные события экрана активной тренировки. */
sealed interface ActiveWorkoutEvent {
  data class NavigateToSummary(val workoutId: String) : ActiveWorkoutEvent

  data object NavigateHome : ActiveWorkoutEvent

  data class ShowMessage(val message: String) : ActiveWorkoutEvent
}

/**
 * Бэкенд экрана активной тренировки. Старт тренировки происходит на вкладке «Тренировки» (см.
 * WorkoutsViewModel); сюда состояние приходит само через [ActiveWorkoutRepository.observeActive].
 * Шаговые правки значений и завершение/отмена делегируются в репозиторий, состояние перечитывается
 * реактивно.
 *
 * Правки подхода и закрытие подхода уходят в процессные [WorkoutSetMutator] и [CompleteSetUseCase]:
 * ровно те же операции доступны с кнопок уведомления в шторке, и писатель должен быть один на
 * процесс (иначе вернутся lost update'ы на быстрых тапах).
 */
@HiltViewModel
class ActiveWorkoutViewModel
@Inject
constructor(
    private val repository: ActiveWorkoutRepository,
    private val previousSetsUseCase: PreviousSetsUseCase,
    private val setMutator: WorkoutSetMutator,
    private val completeSetUseCase: CompleteSetUseCase,
    private val restTimerEngine: RestTimerEngine,
    private val uploadScheduler: UploadScheduler,
    private val heartRateMonitor: HeartRateMonitor,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

  /** Состояние таймера отдыха (null = неактивен) — пилюля на экране подписана прямо на движок. */
  val restTimer: StateFlow<RestTimerState?> = restTimerEngine.state

  /** Live-канал не хранится в Room: он принадлежит только текущей тренировке. */
  val heartRateState: StateFlow<HeartRateConnectionState> = heartRateMonitor.state
  val heartRateReading: StateFlow<HeartRateReading?> = heartRateMonitor.reading

  private val loaded = MutableStateFlow(false)
  private val previousSummaries = MutableStateFlow<Map<Long, String>>(emptyMap())
  private val loadingPrevious = mutableSetOf<Long>()
  private val completedSetEdit = MutableStateFlow(savedCompletedSetEdit())
  private var restoredDraftNeedsValidation = completedSetEdit.value != null
  private var nextCompletedSetEditToken =
      savedStateHandle.get<Long>(COMPLETED_SET_EDIT_EPOCH) ?: completedSetEdit.value?.token ?: 0L

  private val activeWorkout: StateFlow<WorkoutFull?> =
      repository
          .observeActive()
          .onEach { workout ->
            loaded.value = true
            ensurePreviousLoaded(workout)
            validateRestoredCompletedSetEdit(workout)
          }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

  private val tickerFlow: Flow<Long> = flow {
    while (true) {
      emit(System.currentTimeMillis())
      delay(1000.milliseconds)
    }
  }

  val uiState: StateFlow<ActiveWorkoutUiState> =
      combine(
              activeWorkout,
              previousSummaries,
              loaded,
              completedSetEdit,
          ) { workout, previous, isLoaded, edit ->
            ActiveWorkoutUiState(
                loading = !isLoaded,
                workout = workout,
                previousByExercise = previous,
                completedSetEdit = edit,
            )
          }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
              initialValue = ActiveWorkoutUiState(),
          )

  /**
   * Секунды с начала тренировки — отдельный поток, чтобы посекундный тик не перерисовывал весь
   * [uiState] (его собирает только шапка экрана).
   */
  val elapsedSeconds: StateFlow<Long> =
      combine(activeWorkout, tickerFlow) { workout, nowMillis ->
            workout?.let { ((nowMillis - it.workout.startedAt) / 1000).coerceAtLeast(0) } ?: 0L
          }
          .stateIn(
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

  /** Начать пользовательский сценарий подключения BLE Heart Rate Service. */
  fun scanHeartRate() = heartRateMonitor.scan()

  fun connectHeartRate(device: HeartRateDevice) = heartRateMonitor.connect(device)

  fun cancelHeartRateSelection() = heartRateMonitor.stop()

  fun uncompleteSet(setId: Long) {
    viewModelScope.launch { repository.toggleSetCompleted(setId, false) }
  }

  /**
   * Opens the type-specific editor only for a completed set still present in the loaded snapshot.
   */
  fun openCompletedSetEdit(setId: Long, type: ExerciseType) {
    val matching =
        activeWorkout.value
            ?.exercises
            ?.firstOrNull { exercise ->
              exercise.exercise.type == type &&
                  exercise.sets.any { it.id == setId && it.isCompleted }
            }
            ?.sets
            ?.firstOrNull { it.id == setId && it.isCompleted }
    if (matching == null) {
      _events.trySend(ActiveWorkoutEvent.ShowMessage(COMPLETED_SET_UNAVAILABLE_MESSAGE))
      return
    }
    val token = ++nextCompletedSetEditToken
    savedStateHandle[COMPLETED_SET_EDIT_EPOCH] = token
    updateCompletedSetEdit(
        CompletedSetEditDraft(
            setId = matching.id,
            type = type,
            token = token,
            weightKg = matching.weightKg.toDraftText(),
            reps = matching.reps.toDraftText(),
            durationSec = matching.durationSec.toDraftText(),
            speedKmh = matching.speedKmh.toDraftText(),
            inclinePct = matching.inclinePct.toDraftText(),
        ),
    )
  }

  fun updateCompletedSetWeight(raw: String) = updateCompletedSetEdit {
    it.copy(weightKg = raw, error = null)
  }

  fun updateCompletedSetReps(raw: String) = updateCompletedSetEdit {
    it.copy(reps = raw, error = null)
  }

  fun updateCompletedSetDuration(raw: String) = updateCompletedSetEdit {
    it.copy(durationSec = raw, error = null)
  }

  fun updateCompletedSetSpeed(raw: String) = updateCompletedSetEdit {
    it.copy(speedKmh = raw, error = null)
  }

  fun updateCompletedSetIncline(raw: String) = updateCompletedSetEdit {
    it.copy(inclinePct = raw, error = null)
  }

  fun cancelCompletedSetEdit() {
    clearCompletedSetEdit()
  }

  fun saveCompletedSetEdit() {
    val draft = completedSetEdit.value ?: return
    if (draft.isSubmitting) return
    val values = draft.toNumbersOrNull()
    if (values == null) {
      updateCompletedSetEdit { it.copy(error = "Введите корректные числа") }
      return
    }
    updateCompletedSetEdit { it.copy(isSubmitting = true, error = null) }
    viewModelScope.launch {
      try {
        when (setMutator.editCompletedNumbers(draft.setId, draft.type, values)) {
          CompletedSetEditResult.Saved -> {
            if (completedSetEdit.value?.token == draft.token) clearCompletedSetEdit()
          }

          CompletedSetEditResult.MissingOrInactive -> {
            if (completedSetEdit.value?.token == draft.token) {
              updateCompletedSetEdit {
                it.copy(isSubmitting = false, error = COMPLETED_SET_UNAVAILABLE_MESSAGE)
              }
            }
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      }
    }
  }

  fun addSet(workoutExerciseId: Long) {
    val workout = activeWorkout.value ?: return
    if (workout.focusedWorkoutExerciseId() != workoutExerciseId) return
    viewModelScope.launch { repository.addSet(workoutExerciseId) }
  }

  fun deleteSet(setId: Long) {
    viewModelScope.launch { repository.deleteSet(setId) }
  }

  /** Добавляет упражнение по id (после выбора в библиотеке-пикере). */
  fun addExerciseById(exerciseId: Long) {
    val workoutId = activeWorkout.value?.workout?.id ?: return
    viewModelScope.launch {
      try {
        repository.addExercise(workoutId, exerciseId)
      } catch (conflict: RoutineGymConflictException) {
        _events.send(
            ActiveWorkoutEvent.ShowMessage(
                "Упражнение недоступно во всех выбранных залах: " +
                    conflict.exerciseNames.joinToString(),
            ),
        )
      } catch (_: ActiveWorkoutUnavailableException) {
        _events.send(ActiveWorkoutEvent.ShowMessage("Тренировка уже завершена"))
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        _events.send(ActiveWorkoutEvent.ShowMessage("Не удалось добавить упражнение"))
      }
    }
  }

  fun deleteExercise(workoutExerciseId: Long) {
    viewModelScope.launch { repository.deleteExercise(workoutExerciseId) }
  }

  /** Сохраняет итоговый порядок упражнений после отпускания drag-handle. */
  fun reorderExercises(orderedWorkoutExerciseIds: List<Long>) {
    val workoutId = activeWorkout.value?.workout?.id ?: return
    viewModelScope.launch { repository.reorderExercises(workoutId, orderedWorkoutExerciseIds) }
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

  private suspend fun validateRestoredCompletedSetEdit(workout: WorkoutFull?) {
    if (!restoredDraftNeedsValidation) return
    restoredDraftNeedsValidation = false
    val draft = completedSetEdit.value ?: return
    val exists =
        workout?.exercises?.any { exercise ->
          exercise.exercise.type == draft.type &&
              exercise.sets.any { it.id == draft.setId && it.isCompleted }
        } == true
    if (!exists) {
      clearCompletedSetEdit()
      _events.send(ActiveWorkoutEvent.ShowMessage(COMPLETED_SET_UNAVAILABLE_MESSAGE))
    }
  }

  private fun updateCompletedSetEdit(transform: (CompletedSetEditDraft) -> CompletedSetEditDraft) {
    val current = completedSetEdit.value ?: return
    updateCompletedSetEdit(transform(current))
  }

  private fun updateCompletedSetEdit(draft: CompletedSetEditDraft) {
    completedSetEdit.value = draft
    savedStateHandle[COMPLETED_SET_EDIT_SET_ID] = draft.setId
    savedStateHandle[COMPLETED_SET_EDIT_TYPE] = draft.type.name
    savedStateHandle[COMPLETED_SET_EDIT_TOKEN] = draft.token
    savedStateHandle[COMPLETED_SET_EDIT_WEIGHT] = draft.weightKg
    savedStateHandle[COMPLETED_SET_EDIT_REPS] = draft.reps
    savedStateHandle[COMPLETED_SET_EDIT_DURATION] = draft.durationSec
    savedStateHandle[COMPLETED_SET_EDIT_SPEED] = draft.speedKmh
    savedStateHandle[COMPLETED_SET_EDIT_INCLINE] = draft.inclinePct
  }

  private fun clearCompletedSetEdit() {
    completedSetEdit.value = null
    listOf(
            COMPLETED_SET_EDIT_SET_ID,
            COMPLETED_SET_EDIT_TYPE,
            COMPLETED_SET_EDIT_TOKEN,
            COMPLETED_SET_EDIT_WEIGHT,
            COMPLETED_SET_EDIT_REPS,
            COMPLETED_SET_EDIT_DURATION,
            COMPLETED_SET_EDIT_SPEED,
            COMPLETED_SET_EDIT_INCLINE,
        )
        .forEach { key -> savedStateHandle.remove<Any?>(key) }
  }

  private fun savedCompletedSetEdit(): CompletedSetEditDraft? {
    val setId = savedStateHandle.get<Long>(COMPLETED_SET_EDIT_SET_ID) ?: return null
    val type =
        savedStateHandle.get<String>(COMPLETED_SET_EDIT_TYPE)?.let {
          runCatching { ExerciseType.valueOf(it) }.getOrNull()
        } ?: return null
    val token = savedStateHandle.get<Long>(COMPLETED_SET_EDIT_TOKEN) ?: return null
    return CompletedSetEditDraft(
        setId = setId,
        type = type,
        token = token,
        weightKg = savedStateHandle[COMPLETED_SET_EDIT_WEIGHT] ?: "",
        reps = savedStateHandle[COMPLETED_SET_EDIT_REPS] ?: "",
        durationSec = savedStateHandle[COMPLETED_SET_EDIT_DURATION] ?: "",
        speedKmh = savedStateHandle[COMPLETED_SET_EDIT_SPEED] ?: "",
        inclinePct = savedStateHandle[COMPLETED_SET_EDIT_INCLINE] ?: "",
    )
  }

  private fun WorkoutFull.focusedWorkoutExerciseId(): Long? {
    val focusedSetId = currentFocus()?.set?.id ?: return null
    return exercises
        .firstOrNull { exercise -> exercise.sets.any { it.id == focusedSetId } }
        ?.workoutExercise
        ?.id
  }
}

private fun CompletedSetEditDraft.toNumbersOrNull(): CompletedSetNumbers? {
  fun String.toOptionalDouble() = if (isBlank()) null else toFiniteDoubleOrNull()
  fun String.toOptionalInt() = if (isBlank()) null else toIntOrNull()
  return when (type) {
    ExerciseType.STRENGTH -> {
      val weight = weightKg.toOptionalDouble()
      val repetitions = reps.toOptionalInt()
      if ((weightKg.isNotBlank() && weight == null) || (reps.isNotBlank() && repetitions == null)) {
        null
      } else {
        CompletedSetNumbers(weightKg = weight, reps = repetitions)
      }
    }

    ExerciseType.TIMED -> {
      val duration = durationSec.toOptionalInt()
      if (durationSec.isNotBlank() && duration == null) null
      else CompletedSetNumbers(durationSec = duration)
    }

    ExerciseType.CARDIO -> {
      val duration = durationSec.toOptionalInt()
      val speed = speedKmh.toOptionalDouble()
      val incline = inclinePct.toOptionalDouble()
      if (
          (durationSec.isNotBlank() && duration == null) ||
              (speedKmh.isNotBlank() && speed == null) ||
              (inclinePct.isNotBlank() && incline == null)
      ) {
        null
      } else {
        CompletedSetNumbers(durationSec = duration, speedKmh = speed, inclinePct = incline)
      }
    }
  }
}

private fun Double?.toDraftText(): String = this?.toString().orEmpty()

private fun Int?.toDraftText(): String = this?.toString().orEmpty()

internal fun String.toFiniteDoubleOrNull(): Double? = toDoubleOrNull()?.takeIf(Double::isFinite)

internal fun String.isValidOptionalFiniteDecimal(): Boolean =
    isBlank() || toFiniteDoubleOrNull() != null

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
private const val COMPLETED_SET_UNAVAILABLE_MESSAGE = "Подход уже недоступен для правки"
private const val COMPLETED_SET_EDIT_SET_ID = "completed_set_edit_set_id"
private const val COMPLETED_SET_EDIT_TYPE = "completed_set_edit_type"
private const val COMPLETED_SET_EDIT_TOKEN = "completed_set_edit_token"
private const val COMPLETED_SET_EDIT_WEIGHT = "completed_set_edit_weight"
private const val COMPLETED_SET_EDIT_REPS = "completed_set_edit_reps"
private const val COMPLETED_SET_EDIT_DURATION = "completed_set_edit_duration"
private const val COMPLETED_SET_EDIT_SPEED = "completed_set_edit_speed"
private const val COMPLETED_SET_EDIT_INCLINE = "completed_set_edit_incline"
private const val COMPLETED_SET_EDIT_EPOCH = "completed_set_edit_epoch"
