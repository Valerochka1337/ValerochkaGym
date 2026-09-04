package com.valerochka1337.valerochkagym.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.sortedWorkoutFull
import com.valerochka1337.valerochkagym.domain.PreviousSetsUseCase
import com.valerochka1337.valerochkagym.domain.SaveCompletedWorkoutAsRoutineResult
import com.valerochka1337.valerochkagym.domain.SaveCompletedWorkoutAsRoutineUseCase
import com.valerochka1337.valerochkagym.domain.WorkoutStatsUseCase
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.common.formatDuration
import com.valerochka1337.valerochkagym.ui.common.formatVolume
import com.valerochka1337.valerochkagym.ui.common.formatWorkoutDateTime
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.worker.UploadScheduler
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

/** Одна строка подхода в деталях: «1 · 80×8», флаг выполнения — для галочки и приглушения. */
data class DetailSetUi(
    val number: Int,
    val summary: String,
    val completed: Boolean,
)

/** Упражнение в деталях: имя, мышечная группа и строки подходов. [id] — ключ элемента списка. */
data class DetailExerciseUi(
    val id: Long,
    val exerciseId: Long,
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
    val canSaveAsProgram: Boolean = false,
    val showSaveAsProgramDialog: Boolean = false,
    val saveAsProgramName: String = "",
    val isSavingAsProgram: Boolean = false,
    val saveAsProgramError: String? = null,
)

/**
 * Бэкенд детального экрана тренировки. По workoutId из [SavedStateHandle] грузит дерево тренировки,
 * считает длительность/объём и раскладывает упражнения с подходами. Удаление шлёт событие
 * [deleteEvents], по которому экран возвращается назад.
 */
@HiltViewModel
class WorkoutDetailViewModel
@Inject
constructor(
    private val savedStateHandle: SavedStateHandle,
    private val workoutDao: WorkoutDao,
    private val statsUseCase: WorkoutStatsUseCase,
    private val previousSetsUseCase: PreviousSetsUseCase,
    private val uploadScheduler: UploadScheduler,
    private val saveCompletedWorkoutAsRoutineUseCase: SaveCompletedWorkoutAsRoutineUseCase,
) : ViewModel() {

  private val workoutId: String? = savedStateHandle[GymRoutes.WORKOUT_ID_ARG]

  private val _uiState =
      MutableStateFlow(
          WorkoutDetailUiState(
              showSaveAsProgramDialog = savedStateHandle[SAVE_DIALOG_VISIBLE] ?: false,
              saveAsProgramName = savedStateHandle[SAVE_NAME] ?: "",
              // An in-flight coroutine cannot survive recreation; restore a retryable draft.
              isSavingAsProgram = false,
              saveAsProgramError = savedStateHandle[SAVE_ERROR],
          ),
      )
  val uiState: StateFlow<WorkoutDetailUiState> = _uiState.asStateFlow()

  private val _deleteEvents = Channel<Unit>(Channel.BUFFERED)
  private val _saveEvents = Channel<Unit>(Channel.BUFFERED)

  /** Событие «тренировка удалена» — экран возвращается на список истории. */
  val deleteEvents = _deleteEvents.receiveAsFlow()
  /** One-shot acknowledgement; history stays at the current detail destination. */
  val saveEvents = _saveEvents.receiveAsFlow()

  /** Loaded snapshot; never write it back to the source workout. */
  private var workout: com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull? = null
  private var saveOperationSyncId: String? = savedStateHandle[SAVE_OPERATION_SYNC_ID]
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
      val duration = (full.workout.finishedAt ?: full.workout.startedAt) - full.workout.startedAt
      val exercises =
          full.exercises.map { exercise ->
            DetailExerciseUi(
                id = exercise.workoutExercise.id,
                exerciseId = exercise.exercise.id,
                name = exercise.exercise.name,
                muscleGroup = exercise.exercise.muscleGroup.displayName(),
                sets =
                    exercise.sets.mapIndexed { index, set ->
                      DetailSetUi(
                          number = index + 1,
                          summary =
                              previousSetsUseCase.formatSummary(
                                  listOf(set),
                                  exercise.exercise.type,
                              ),
                          completed = set.isCompleted,
                      )
                    },
            )
          }
      _uiState.value =
          WorkoutDetailUiState(
              loading = false,
              name = full.workout.name,
              dateTime = formatWorkoutDateTime(full.workout.startedAt),
              duration = formatDuration(duration),
              volume = formatVolume(statsUseCase.volume(full)),
              uploadStatus = full.workout.uploadStatus,
              uploadError = full.workout.uploadError,
              note = full.workout.note,
              exercises = exercises,
              canSaveAsProgram =
                  full.workout.finishedAt != null &&
                      full.exercises.any { section -> section.sets.any { it.isCompleted } },
              showSaveAsProgramDialog = _uiState.value.showSaveAsProgramDialog,
              saveAsProgramName = _uiState.value.saveAsProgramName,
              isSavingAsProgram = _uiState.value.isSavingAsProgram,
              saveAsProgramError = _uiState.value.saveAsProgramError,
          )
      if (!_uiState.value.canSaveAsProgram) dismissSaveAsProgram()
      // Дерево тренировки неизменно, но статус выгрузки меняется воркером — держим его живым.
      workoutDao.observeWorkout(id).collect { entity ->
        if (entity != null) {
          _uiState.update {
            it.copy(uploadStatus = entity.uploadStatus, uploadError = entity.uploadError)
          }
        }
      }
    }
  }

  /**
   * Повторная выгрузка упавшей тренировки: сбрасывает статус в PENDING и ставит воркер в очередь.
   */
  fun retryUpload() {
    val id = workoutId ?: return
    viewModelScope.launch { uploadScheduler.retry(id) }
  }

  fun delete() {
    val id = workoutId ?: return
    viewModelScope.launch {
      workoutDao.deleteWorkout(id)
      _deleteEvents.send(Unit)
    }
  }

  fun openSaveAsProgram() {
    val state = _uiState.value
    if (!state.canSaveAsProgram || state.isSavingAsProgram || state.showSaveAsProgramDialog) return
    saveOperationSyncId = UUID.randomUUID().toString()
    updateSaveState {
      it.copy(
          showSaveAsProgramDialog = true,
          saveAsProgramName = it.name,
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
            clearSaveAsProgram()
            _saveEvents.send(Unit)
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
      transform: (WorkoutDetailUiState) -> WorkoutDetailUiState
  ): WorkoutDetailUiState {
    var updated: WorkoutDetailUiState? = null
    _uiState.update { current ->
      transform(current).also {
        updated = it
        persistSaveState(it)
      }
    }
    return requireNotNull(updated)
  }

  private fun persistSaveState(state: WorkoutDetailUiState) {
    savedStateHandle[SAVE_DIALOG_VISIBLE] = state.showSaveAsProgramDialog
    savedStateHandle[SAVE_NAME] = state.saveAsProgramName
    savedStateHandle[SAVE_ERROR] = state.saveAsProgramError
    saveOperationSyncId?.let { savedStateHandle[SAVE_OPERATION_SYNC_ID] = it }
        ?: savedStateHandle.remove<String>(SAVE_OPERATION_SYNC_ID)
  }

  private fun clearOrphanedSaveDraft() {
    val state = _uiState.value
    if (state.showSaveAsProgramDialog && saveOperationSyncId.isNullOrBlank()) clearSaveAsProgram()
    if (!state.showSaveAsProgramDialog && saveOperationSyncId != null) {
      saveOperationSyncId = null
      persistSaveState(state)
    }
  }

  private fun clearSaveAsProgram() {
    saveOperationSyncId = null
    updateSaveState { state ->
      state.copy(
          showSaveAsProgramDialog = false,
          saveAsProgramName = "",
          isSavingAsProgram = false,
          saveAsProgramError = null,
      )
    }
  }

  private companion object {
    const val SAVE_DIALOG_VISIBLE = "save_as_program_dialog_visible"
    const val SAVE_NAME = "save_as_program_name"
    const val SAVE_ERROR = "save_as_program_error"
    const val SAVE_OPERATION_SYNC_ID = "save_as_program_operation_sync_id"
  }
}
