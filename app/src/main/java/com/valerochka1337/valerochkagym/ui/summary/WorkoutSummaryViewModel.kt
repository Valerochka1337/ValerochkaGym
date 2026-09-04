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
import com.valerochka1337.valerochkagym.domain.SaveCompletedWorkoutAsRoutineResult
import com.valerochka1337.valerochkagym.domain.SaveCompletedWorkoutAsRoutineUseCase
import com.valerochka1337.valerochkagym.domain.WorkoutStatsUseCase
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Сводка одного упражнения в итогах: имя и краткая строка выполненных подходов. [id] — ключ списка. */
data class ExerciseSummaryUi(
    val id: Long,
    val exerciseId: Long,
    val name: String,
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
    val canSaveAsProgram: Boolean = false,
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
class WorkoutSummaryViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val workoutDao: WorkoutDao,
    private val statsUseCase: WorkoutStatsUseCase,
    private val routineUpdateUseCase: RoutineUpdateUseCase,
    private val previousSetsUseCase: PreviousSetsUseCase,
    private val saveCompletedWorkoutAsRoutineUseCase: SaveCompletedWorkoutAsRoutineUseCase,
) : ViewModel() {

    private val workoutId: String? = savedStateHandle[GymRoutes.WORKOUT_ID_ARG]

    private val _uiState = MutableStateFlow(
        WorkoutSummaryUiState(
            showSaveAsProgramDialog = savedStateHandle[SAVE_DIALOG_VISIBLE] ?: false,
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

    /** Загруженная тренировка — источник для applyToRoutine при подтверждении диалога. */
    private var workout: WorkoutFull? = null
    private var saveOperationSyncId: String? = savedStateHandle[SAVE_OPERATION_SYNC_ID]
    private val saveConfirmationMutex = Mutex()

    init {
        clearOrphanedSaveDraft()
        load()
    }

    private fun load() {
        val id = workoutId ?: run {
            _uiState.update { it.copy(loading = false) }
            clearSaveAsProgram()
            return
        }
        viewModelScope.launch {
            val full = workoutDao.getWorkoutFull(id)?.let(::sortedWorkoutFull) ?: run {
                _uiState.update { it.copy(loading = false) }
                clearSaveAsProgram()
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
                canSaveAsProgram = full.workout.finishedAt != null &&
                    full.exercises.any { section -> section.sets.any { it.isCompleted } },
                showSaveAsProgramDialog = _uiState.value.showSaveAsProgramDialog,
                saveAsProgramName = _uiState.value.saveAsProgramName,
                isSavingAsProgram = _uiState.value.isSavingAsProgram,
                saveAsProgramError = _uiState.value.saveAsProgramError,
            )
            if (!_uiState.value.canSaveAsProgram) dismissSaveAsProgram()
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

    fun openSaveAsProgram() {
        val state = _uiState.value
        if (!state.canSaveAsProgram || state.isSavingAsProgram || state.showSaveAsProgramDialog) return
        saveOperationSyncId = UUID.randomUUID().toString()
        updateSaveState {
            it.copy(
                showSaveAsProgramDialog = true,
                saveAsProgramName = it.workoutName,
                saveAsProgramError = null,
            )
        }
    }

    fun changeSaveAsProgramName(name: String) {
        updateSaveState { state ->
            if (state.isSavingAsProgram) state else state.copy(saveAsProgramName = name, saveAsProgramError = null)
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
                    SaveCompletedWorkoutAsRoutineResult.BlankName -> updateSaveState { current ->
                        current.copy(isSavingAsProgram = false, saveAsProgramError = "Введите название программы.")
                    }
                    is SaveCompletedWorkoutAsRoutineResult.Conflict -> updateSaveState { current ->
                        current.copy(
                            isSavingAsProgram = false,
                            saveAsProgramError = "Некоторые упражнения больше недоступны в выбранных залах.",
                        )
                    }
                    SaveCompletedWorkoutAsRoutineResult.GymNotFound -> updateSaveState { current ->
                        current.copy(isSavingAsProgram = false, saveAsProgramError = "Не удалось сохранить программу. Попробуйте ещё раз.")
                    }
                    SaveCompletedWorkoutAsRoutineResult.Failure -> updateSaveState { current ->
                        current.copy(isSavingAsProgram = false, saveAsProgramError = "Не удалось сохранить программу. Попробуйте ещё раз.")
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

    private fun updateSaveState(transform: (WorkoutSummaryUiState) -> WorkoutSummaryUiState): WorkoutSummaryUiState {
        var updated: WorkoutSummaryUiState? = null
        _uiState.update { current -> transform(current).also {
            updated = it
            persistSaveState(it)
        } }
        return requireNotNull(updated)
    }

    private fun persistSaveState(state: WorkoutSummaryUiState) {
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
