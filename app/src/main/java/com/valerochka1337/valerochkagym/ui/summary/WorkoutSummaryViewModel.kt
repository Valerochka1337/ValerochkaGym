package com.valerochka1337.valerochkagym.ui.summary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.data.sortedWorkoutFull
import com.valerochka1337.valerochkagym.domain.PrResult
import com.valerochka1337.valerochkagym.domain.PreviousSetsUseCase
import com.valerochka1337.valerochkagym.domain.RoutineReplacementSnapshot
import com.valerochka1337.valerochkagym.domain.RoutineUpdateResult
import com.valerochka1337.valerochkagym.domain.RoutineUpdateUseCase
import com.valerochka1337.valerochkagym.domain.SaveCompletedWorkoutAsRoutineResult
import com.valerochka1337.valerochkagym.domain.SaveCompletedWorkoutAsRoutineUseCase
import com.valerochka1337.valerochkagym.domain.WorkoutStatsUseCase
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Сводка одного упражнения в итогах: имя и краткая строка выполненных подходов. [id] — ключ списка.
 */
data class ExerciseSummaryUi(
    val id: Long,
    val exerciseId: Long,
    val name: String,
    val setsSummary: String,
)

/**
 * Состояние экрана итогов. Сохранение программы — единый путь: выбор, имя новой программы либо
 * подтверждение перезаписи, затем запись. Команды хранятся в VM, а черновик — в SavedStateHandle.
 */
data class WorkoutSummaryUiState(
    val loading: Boolean = true,
    val workoutName: String = "",
    val durationSeconds: Long = 0L,
    val volumeKg: Double = 0.0,
    val prs: List<PrResult> = emptyList(),
    val exercises: List<ExerciseSummaryUi> = emptyList(),
    val canSaveAsProgram: Boolean = false,
    val canReplaceRoutine: Boolean = false,
    val showSaveChoice: Boolean = false,
    val isPreparingReplacement: Boolean = false,
    val showReplaceRoutineDialog: Boolean = false,
    val showSaveAsProgramDialog: Boolean = false,
    val saveAsProgramName: String = "",
    val isSavingAsProgram: Boolean = false,
    val saveAsProgramError: String? = null,
)

/**
 * Бэкенд экрана итогов завершённой тренировки. По workoutId из [SavedStateHandle] грузит
 * тренировку, считает длительность/объём/рекорды и проверяет расхождение с программой.
 */
@HiltViewModel
class WorkoutSummaryViewModel
@Inject
constructor(
    private val savedStateHandle: SavedStateHandle,
    private val workoutDao: WorkoutDao,
    private val statsUseCase: WorkoutStatsUseCase,
    private val routineUpdateUseCase: RoutineUpdateUseCase,
    private val previousSetsUseCase: PreviousSetsUseCase,
    private val saveCompletedWorkoutAsRoutineUseCase: SaveCompletedWorkoutAsRoutineUseCase,
) : ViewModel() {

  private val workoutId: String? = savedStateHandle[GymRoutes.WORKOUT_ID_ARG]

  private val _uiState =
      MutableStateFlow(
          WorkoutSummaryUiState(
              showSaveAsProgramDialog = savedStateHandle[SAVE_DIALOG_VISIBLE] ?: false,
              showSaveChoice = savedStateHandle[SAVE_CHOICE_VISIBLE] ?: false,
              showReplaceRoutineDialog = savedStateHandle[SAVE_REPLACE_VISIBLE] ?: false,
              saveAsProgramName = savedStateHandle[SAVE_NAME] ?: "",
              // An in-flight coroutine cannot survive recreation; restore a retryable draft.
              isSavingAsProgram = false,
              saveAsProgramError = savedStateHandle[SAVE_ERROR],
          ),
      )
  val uiState: StateFlow<WorkoutSummaryUiState> = _uiState.asStateFlow()

  private val _saveEvents = Channel<Unit>(Channel.BUFFERED)
  /** One-shot acknowledgement; saving never changes summary navigation. */
  val saveEvents = _saveEvents.receiveAsFlow()
  private val _doneEvents = Channel<Unit>(Channel.BUFFERED)
  val doneEvents = _doneEvents.receiveAsFlow()

  /** Загруженная тренировка — источник для applyToRoutine при подтверждении диалога. */
  private var workout: WorkoutFull? = null
  private var saveOperationSyncId: String? = savedStateHandle[SAVE_OPERATION_SYNC_ID]
  private var replacementSnapshot: RoutineReplacementSnapshot? = null
  private var finishAfterSaving: Boolean = savedStateHandle[FINISH_AFTER_SAVING] ?: false
  private var replacePreparationEpoch = 0L
  private val saveConfirmationMutex = Mutex()

  init {
    clearOrphanedSaveDraft()
    load()
  }

  private fun load() {
    val id =
        workoutId
            ?: run {
              _uiState.update { it.copy(loading = false) }
              clearSaveAsProgram()
              return
            }
    viewModelScope.launch {
      val full =
          workoutDao.getWorkoutFull(id)?.let(::sortedWorkoutFull)
              ?: run {
                _uiState.update { it.copy(loading = false) }
                clearSaveAsProgram()
                return@launch
              }
      workout = full
      val volume = statsUseCase.volume(full)
      val prs = statsUseCase.newPrs(full)
      val canReplace =
          full.workout.finishedAt != null &&
              full.exercises.any { section -> section.sets.any { it.isCompleted } } &&
              routineUpdateUseCase.canReplace(full)
      val duration =
          ((full.workout.finishedAt ?: full.workout.startedAt) - full.workout.startedAt) / 1000
      val exercises =
          full.exercises
              .filter { section -> section.sets.any { it.isCompleted } }
              .map { exercise ->
                ExerciseSummaryUi(
                    id = exercise.workoutExercise.id,
                    exerciseId = exercise.exercise.id,
                    name = exercise.exercise.name,
                    setsSummary =
                        previousSetsUseCase.formatSummary(
                            exercise.sets.filter { it.isCompleted },
                            exercise.exercise.type,
                        ),
                )
              }
      _uiState.value =
          WorkoutSummaryUiState(
              loading = false,
              workoutName = full.workout.name,
              durationSeconds = duration.coerceAtLeast(0),
              volumeKg = volume,
              prs = prs,
              exercises = exercises,
              canSaveAsProgram =
                  full.workout.finishedAt != null &&
                      full.exercises.any { section -> section.sets.any { it.isCompleted } },
              canReplaceRoutine = canReplace,
              showSaveChoice = _uiState.value.showSaveChoice,
              showReplaceRoutineDialog = _uiState.value.showReplaceRoutineDialog,
              showSaveAsProgramDialog = _uiState.value.showSaveAsProgramDialog,
              saveAsProgramName = _uiState.value.saveAsProgramName,
              isSavingAsProgram = _uiState.value.isSavingAsProgram,
              saveAsProgramError = _uiState.value.saveAsProgramError,
          )
      if (!_uiState.value.canSaveAsProgram) dismissSaveAsProgram()
    }
  }

  fun onDone() {
    if (_uiState.value.canSaveAsProgram) {
      finishAfterSaving = true
      updateSaveState { it.copy(showSaveChoice = true, saveAsProgramError = null) }
    } else {
      viewModelScope.launch { _doneEvents.send(Unit) }
    }
  }

  fun chooseCreateRoutine() {
    val state = _uiState.value
    if (!state.showSaveChoice || state.isSavingAsProgram) return
    replacePreparationEpoch++
    saveOperationSyncId = UUID.randomUUID().toString()
    updateSaveState {
      it.copy(
          showSaveChoice = false,
          showSaveAsProgramDialog = true,
          saveAsProgramName = it.workoutName,
          saveAsProgramError = null,
      )
    }
  }

  fun chooseReplaceRoutine() {
    val state = _uiState.value
    if (
        !state.showSaveChoice ||
            !state.canReplaceRoutine ||
            state.isSavingAsProgram ||
            state.isPreparingReplacement
    )
        return
    val full = workout ?: return
    val operationId = UUID.randomUUID().toString()
    val epoch = ++replacePreparationEpoch
    updateSaveState { it.copy(isPreparingReplacement = true, saveAsProgramError = null) }
    viewModelScope.launch {
      val snapshot = routineUpdateUseCase.prepareReplacement(full, operationId)
      if (epoch != replacePreparationEpoch || !_uiState.value.showSaveChoice) return@launch
      if (snapshot == null) {
        updateSaveState {
          it.copy(
              isPreparingReplacement = false,
              saveAsProgramError = "Эту программу больше нельзя перезаписать.",
          )
        }
      } else {
        replacementSnapshot = snapshot
        saveOperationSyncId = operationId
        updateSaveState {
          it.copy(
              showSaveChoice = false,
              isPreparingReplacement = false,
              showReplaceRoutineDialog = true,
              saveAsProgramError = null,
          )
        }
      }
    }
  }

  fun confirmRoutineReplace() {
    val full = workout ?: return
    if (!_uiState.value.showReplaceRoutineDialog || !saveConfirmationMutex.tryLock()) return
    updateSaveState { it.copy(isSavingAsProgram = true, saveAsProgramError = null) }
    viewModelScope.launch {
      try {
        val snapshot = replacementSnapshot ?: restoreReplacementSnapshot(full)
        val result =
            if (snapshot == null) RoutineUpdateResult.Conflict
            else routineUpdateUseCase.apply(snapshot)
        when (result) {
          is RoutineUpdateResult.Saved -> {
            finishOrAcknowledgeSave()
          }
          RoutineUpdateResult.ReadOnly ->
              updateSaveState {
                it.copy(
                    isSavingAsProgram = false,
                    saveAsProgramError = "Эту программу нельзя перезаписать.",
                )
              }
          RoutineUpdateResult.NotFound ->
              updateSaveState {
                it.copy(
                    isSavingAsProgram = false,
                    saveAsProgramError = "Программа больше не существует.",
                )
              }
          RoutineUpdateResult.Conflict ->
              updateSaveState {
                it.copy(
                    isSavingAsProgram = false,
                    saveAsProgramError = "Программа изменилась. Откройте выбор снова.",
                )
              }
          RoutineUpdateResult.EmptyWorkout -> clearSaveAsProgram()
          RoutineUpdateResult.Failure ->
              updateSaveState {
                it.copy(
                    isSavingAsProgram = false,
                    saveAsProgramError = "Не удалось сохранить программу. Попробуйте ещё раз.",
                )
              }
        }
      } finally {
        saveConfirmationMutex.unlock()
      }
    }
  }

  fun dismissSaveChoice() {
    if (!_uiState.value.isSavingAsProgram) {
      replacePreparationEpoch++
      updateSaveState { it.copy(showSaveChoice = false, isPreparingReplacement = false) }
    }
  }

  fun skipSavingAndFinish() {
    if (_uiState.value.showSaveChoice && !_uiState.value.isSavingAsProgram) {
      replacePreparationEpoch++
      clearSaveAsProgram()
      viewModelScope.launch { _doneEvents.send(Unit) }
    }
  }

  fun dismissReplaceRoutine() {
    if (!_uiState.value.isSavingAsProgram) clearSaveAsProgram()
  }

  fun openSaveAsProgram() {
    val state = _uiState.value
    if (!state.canSaveAsProgram || state.isSavingAsProgram || state.showSaveAsProgramDialog) return
    replacePreparationEpoch++
    finishAfterSaving = false
    saveOperationSyncId = UUID.randomUUID().toString()
    updateSaveState {
      it.copy(
          showSaveChoice = false,
          showSaveAsProgramDialog = true,
          saveAsProgramName = it.workoutName,
          saveAsProgramError = null,
      )
    }
  }

  fun changeSaveAsProgramName(name: String) {
    updateSaveState { state ->
      if (state.isSavingAsProgram) state
      else state.copy(saveAsProgramName = name, saveAsProgramError = null)
    }
  }

  fun confirmSaveAsProgram() {
    if (!saveConfirmationMutex.tryLock()) return
    val full = workout
    val state = _uiState.value
    val operationSyncId = saveOperationSyncId
    if (!state.showSaveAsProgramDialog || state.isSavingAsProgram || full == null) {
      saveConfirmationMutex.unlock()
      return
    }
    if (operationSyncId.isNullOrBlank()) {
      clearSaveAsProgram()
      saveConfirmationMutex.unlock()
      return
    }
    val name = state.saveAsProgramName
    updateSaveState { it.copy(isSavingAsProgram = true, saveAsProgramError = null) }

    viewModelScope.launch {
      try {
        when (val result = saveCompletedWorkoutAsRoutineUseCase(full, name, operationSyncId)) {
          is SaveCompletedWorkoutAsRoutineResult.Saved -> {
            finishOrAcknowledgeSave()
          }
          SaveCompletedWorkoutAsRoutineResult.BlankName ->
              updateSaveState { current ->
                current.copy(
                    isSavingAsProgram = false,
                    saveAsProgramError = "Введите название программы.",
                )
              }
          is SaveCompletedWorkoutAsRoutineResult.Conflict ->
              updateSaveState { current ->
                current.copy(
                    isSavingAsProgram = false,
                    saveAsProgramError =
                        "Некоторые упражнения больше недоступны в выбранных залах.",
                )
              }
          SaveCompletedWorkoutAsRoutineResult.GymNotFound ->
              updateSaveState { current ->
                current.copy(
                    isSavingAsProgram = false,
                    saveAsProgramError = "Не удалось сохранить программу. Попробуйте ещё раз.",
                )
              }
          SaveCompletedWorkoutAsRoutineResult.Failure ->
              updateSaveState { current ->
                current.copy(
                    isSavingAsProgram = false,
                    saveAsProgramError = "Не удалось сохранить программу. Попробуйте ещё раз.",
                )
              }
        }
      } finally {
        saveConfirmationMutex.unlock()
      }
    }
  }

  fun dismissSaveAsProgram() {
    if (!_uiState.value.isSavingAsProgram) clearSaveAsProgram()
  }

  private fun updateSaveState(
      transform: (WorkoutSummaryUiState) -> WorkoutSummaryUiState
  ): WorkoutSummaryUiState {
    var updated: WorkoutSummaryUiState? = null
    _uiState.update { current ->
      transform(current).also {
        updated = it
        persistSaveState(it)
      }
    }
    return requireNotNull(updated)
  }

  private fun persistSaveState(state: WorkoutSummaryUiState) {
    savedStateHandle[SAVE_DIALOG_VISIBLE] = state.showSaveAsProgramDialog
    savedStateHandle[SAVE_CHOICE_VISIBLE] = state.showSaveChoice
    savedStateHandle[SAVE_REPLACE_VISIBLE] = state.showReplaceRoutineDialog
    savedStateHandle[SAVE_NAME] = state.saveAsProgramName
    savedStateHandle[SAVE_ERROR] = state.saveAsProgramError
    savedStateHandle[FINISH_AFTER_SAVING] = finishAfterSaving
    replacementSnapshot?.command?.let { command ->
      savedStateHandle[SAVE_SOURCE_ID] = command.sourceRoutineId
      savedStateHandle[SAVE_SOURCE_SYNC_ID] = command.sourceRoutineSyncId
      savedStateHandle[SAVE_EXPECTED_UPDATED_AT] = command.expectedUpdatedAt
      savedStateHandle[SAVE_EXPECTED_FINGERPRINT] = command.expectedSourceFingerprint
      savedStateHandle[SAVE_PREDICTED_FINGERPRINT] = command.predictedTargetFingerprint
    }
    saveOperationSyncId?.let { savedStateHandle[SAVE_OPERATION_SYNC_ID] = it }
        ?: savedStateHandle.remove<String>(SAVE_OPERATION_SYNC_ID)
  }

  private fun clearOrphanedSaveDraft() {
    val state = _uiState.value
    if (
        (state.showSaveAsProgramDialog || state.showReplaceRoutineDialog) &&
            saveOperationSyncId.isNullOrBlank()
    )
        clearSaveAsProgram()
    if (
        !state.showSaveAsProgramDialog &&
            !state.showReplaceRoutineDialog &&
            saveOperationSyncId != null
    ) {
      saveOperationSyncId = null
      persistSaveState(state)
    }
  }

  private fun clearSaveAsProgram() {
    saveOperationSyncId = null
    replacementSnapshot = null
    finishAfterSaving = false
    clearReplacementContext()
    updateSaveState { state ->
      state.copy(
          showSaveAsProgramDialog = false,
          showSaveChoice = false,
          isPreparingReplacement = false,
          showReplaceRoutineDialog = false,
          saveAsProgramName = "",
          isSavingAsProgram = false,
          saveAsProgramError = null,
      )
    }
  }

  private suspend fun restoreReplacementSnapshot(full: WorkoutFull): RoutineReplacementSnapshot? {
    val operationId = saveOperationSyncId ?: return null
    val sourceId = savedStateHandle.get<Long>(SAVE_SOURCE_ID) ?: return null
    val sourceSyncId = savedStateHandle.get<String>(SAVE_SOURCE_SYNC_ID) ?: return null
    val expectedUpdatedAt = savedStateHandle.get<Long>(SAVE_EXPECTED_UPDATED_AT) ?: return null
    val expectedFingerprint = savedStateHandle.get<String>(SAVE_EXPECTED_FINGERPRINT) ?: return null
    val predictedFingerprint =
        savedStateHandle.get<String>(SAVE_PREDICTED_FINGERPRINT) ?: return null
    return routineUpdateUseCase.restoreReplacement(
        workout = full,
        sourceRoutineId = sourceId,
        sourceRoutineSyncId = sourceSyncId,
        expectedUpdatedAt = expectedUpdatedAt,
        expectedSourceFingerprint = expectedFingerprint,
        predictedTargetFingerprint = predictedFingerprint,
        operationUuid = operationId,
    )
  }

  private suspend fun finishOrAcknowledgeSave() {
    val shouldFinish = finishAfterSaving
    clearSaveAsProgram()
    if (shouldFinish) _doneEvents.send(Unit) else _saveEvents.send(Unit)
  }

  private fun clearReplacementContext() {
    savedStateHandle.remove<Long>(SAVE_SOURCE_ID)
    savedStateHandle.remove<String>(SAVE_SOURCE_SYNC_ID)
    savedStateHandle.remove<Long>(SAVE_EXPECTED_UPDATED_AT)
    savedStateHandle.remove<String>(SAVE_EXPECTED_FINGERPRINT)
    savedStateHandle.remove<String>(SAVE_PREDICTED_FINGERPRINT)
  }

  private companion object {
    const val SAVE_DIALOG_VISIBLE = "save_as_program_dialog_visible"
    const val SAVE_CHOICE_VISIBLE = "save_as_program_choice_visible"
    const val SAVE_REPLACE_VISIBLE = "save_as_program_replace_visible"
    const val SAVE_NAME = "save_as_program_name"
    const val SAVE_ERROR = "save_as_program_error"
    const val SAVE_OPERATION_SYNC_ID = "save_as_program_operation_sync_id"
    const val FINISH_AFTER_SAVING = "save_as_program_finish_after_saving"
    const val SAVE_SOURCE_ID = "save_as_program_source_id"
    const val SAVE_SOURCE_SYNC_ID = "save_as_program_source_sync_id"
    const val SAVE_EXPECTED_UPDATED_AT = "save_as_program_expected_updated_at"
    const val SAVE_EXPECTED_FINGERPRINT = "save_as_program_expected_fingerprint"
    const val SAVE_PREDICTED_FINGERPRINT = "save_as_program_predicted_fingerprint"
  }
}
