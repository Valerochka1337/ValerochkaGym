package com.valerochka1337.valerochkagym.ui.gyms

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.domain.DeleteGymResult
import com.valerochka1337.valerochkagym.domain.GymConfigurationConflict
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.GymRoutineReference
import com.valerochka1337.valerochkagym.domain.SaveGymResult
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Редактируемая конфигурация зала и каталог упражнений для мультивыбора. */
data class GymEditorUiState(
    val isNew: Boolean = true,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val name: String = "",
    val query: String = "",
    val exercises: List<ExerciseEntity>? = null,
    val selectedExerciseIds: Set<Long> = emptySet(),
    val loadError: String? = null,
    val actionError: String? = null,
    val saveConflict: GymConfigurationConflict? = null,
    val deleteConflict: List<GymRoutineReference>? = null,
) {
  val isBusy: Boolean
    get() = isSaving || isDeleting

  val canSave: Boolean
    get() = !isLoading && !isBusy && loadError == null && exercises != null && name.isNotBlank()

  val filteredExercises: List<ExerciseEntity>
    get() {
      val needle = query.trim()
      if (needle.isEmpty()) return exercises.orEmpty()
      return exercises.orEmpty().filter { exercise ->
        exercise.name.contains(needle, ignoreCase = true) ||
            exercise.muscleGroup.displayName().contains(needle, ignoreCase = true) ||
            exercise.type.displayName().contains(needle, ignoreCase = true)
      }
    }
}

/**
 * Редактор новой или существующей конфигурации. Все правки остаются локальным черновиком, а
 * конфликт состава показывается пользователю и никогда не применяется молча.
 */
@HiltViewModel
class GymEditorViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: GymRepository,
) : ViewModel() {

  private val gymId: String? = savedStateHandle.get(GymRoutes.GYM_ID_ARG)

  private val _uiState =
      MutableStateFlow(
          GymEditorUiState(isNew = gymId == null, isLoading = gymId != null),
      )
  val uiState: StateFlow<GymEditorUiState> = _uiState.asStateFlow()

  private val _finished = Channel<Unit>(Channel.BUFFERED)
  /** Сохранение либо удаление завершено — экран может вернуться к списку. */
  val finished = _finished.receiveAsFlow()

  init {
    viewModelScope.launch {
      repository
          .observeExerciseCatalog()
          .catch {
            _uiState.update { state ->
              state.copy(
                  exercises = emptyList(),
                  loadError = state.loadError ?: "Не удалось загрузить каталог упражнений.",
              )
            }
          }
          .collect { exercises ->
            _uiState.update { state ->
              state.copy(exercises = exercises.sortedBy { it.name.lowercase() })
            }
          }
    }
    gymId?.let { id -> viewModelScope.launch { load(id) } }
  }

  private suspend fun load(id: String) {
    try {
      val gym = repository.getGym(id)
      _uiState.update { state ->
        if (gym == null) {
          state.copy(isLoading = false, loadError = "Зал не найден.")
        } else {
          state.copy(
              isLoading = false,
              name = gym.name,
              selectedExerciseIds = gym.exercises.mapTo(linkedSetOf()) { it.id },
          )
        }
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      _uiState.update {
        it.copy(isLoading = false, loadError = "Не удалось загрузить конфигурацию зала.")
      }
    }
  }

  fun setName(value: String) {
    _uiState.update { state ->
      if (state.isBusy) state else state.copy(name = value, actionError = null)
    }
  }

  fun setQuery(value: String) {
    _uiState.update { it.copy(query = value) }
  }

  fun clearQuery() {
    _uiState.update { it.copy(query = "") }
  }

  fun toggleExercise(exerciseId: Long) {
    _uiState.update { state ->
      if (state.isBusy || state.exercises?.none { it.id == exerciseId } != false) {
        state
      } else {
        val selected = state.selectedExerciseIds.toMutableSet()
        if (!selected.add(exerciseId)) selected.remove(exerciseId)
        state.copy(
            selectedExerciseIds = selected,
            actionError = null,
            saveConflict = null,
        )
      }
    }
  }

  fun save() {
    val state = _uiState.value
    if (!state.canSave) return
    val trimmedName = state.name.trim()
    _uiState.update {
      it.copy(isSaving = true, actionError = null, saveConflict = null, deleteConflict = null)
    }
    viewModelScope.launch {
      val result =
          try {
            repository.saveGym(
                id = gymId,
                name = trimmedName,
                exerciseIds = state.selectedExerciseIds,
            )
          } catch (cancellation: CancellationException) {
            throw cancellation
          } catch (_: Exception) {
            SaveGymResult.Failure
          }
      when (result) {
        is SaveGymResult.Saved -> {
          _uiState.update { it.copy(isSaving = false) }
          _finished.send(Unit)
        }
        is SaveGymResult.Conflict ->
            _uiState.update { it.copy(isSaving = false, saveConflict = result.details) }
        SaveGymResult.NameAlreadyExists ->
            _uiState.update {
              it.copy(isSaving = false, actionError = "Зал с таким названием уже существует.")
            }
        SaveGymResult.NotFound ->
            _uiState.update { it.copy(isSaving = false, actionError = "Зал больше не существует.") }
        SaveGymResult.Failure ->
            _uiState.update {
              it.copy(
                  isSaving = false,
                  actionError = "Не удалось сохранить зал. Попробуйте ещё раз.",
              )
            }
      }
    }
  }

  fun delete() {
    val id = gymId ?: return
    val state = _uiState.value
    if (state.isLoading || state.isBusy || state.loadError != null) return
    _uiState.update {
      it.copy(isDeleting = true, actionError = null, saveConflict = null, deleteConflict = null)
    }
    viewModelScope.launch {
      val result =
          try {
            repository.deleteGym(id)
          } catch (cancellation: CancellationException) {
            throw cancellation
          } catch (_: Exception) {
            DeleteGymResult.Failure
          }
      when (result) {
        DeleteGymResult.Deleted -> {
          _uiState.update { it.copy(isDeleting = false) }
          _finished.send(Unit)
        }
        is DeleteGymResult.InUse ->
            _uiState.update { it.copy(isDeleting = false, deleteConflict = result.routines) }
        DeleteGymResult.NotFound ->
            _uiState.update {
              it.copy(isDeleting = false, actionError = "Зал больше не существует.")
            }
        DeleteGymResult.Failure ->
            _uiState.update {
              it.copy(
                  isDeleting = false,
                  actionError = "Не удалось удалить зал. Попробуйте ещё раз.",
              )
            }
      }
    }
  }

  fun dismissActionError() {
    _uiState.update { it.copy(actionError = null) }
  }

  fun dismissSaveConflict() {
    _uiState.update { it.copy(saveConflict = null) }
  }

  fun dismissDeleteConflict() {
    _uiState.update { it.copy(deleteConflict = null) }
  }
}
