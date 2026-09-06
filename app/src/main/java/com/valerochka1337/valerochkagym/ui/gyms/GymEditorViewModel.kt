package com.valerochka1337.valerochkagym.ui.gyms

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.EquipmentCatalog
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

enum class GymEquipmentMode {
  ALL,
  SELECTED,
}

/** Редактируемая конфигурация зала и каталог упражнений для мультивыбора. */
data class GymEditorUiState(
    val isNew: Boolean = true,
    val isCopy: Boolean = false,
    val copySourceWasLegacy: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val name: String = "",
    val query: String = "",
    val exercises: List<ExerciseEntity>? = null,
    val selectedExerciseIds: Set<Long> = emptySet(),
    val equipment: List<EquipmentCatalog.Equipment>? = null,
    val selectedEquipmentIds: Set<String> = emptySet(),
    val mode: GymEquipmentMode = GymEquipmentMode.ALL,
    val expandedGroups: Set<String> = emptySet(),
    val bulkUndo: Set<String>? = null,
    val preview: Boolean = false,
    val previewLoading: Boolean = false,
    val previewExercises: List<ExerciseEntity>? = null,
    val discardConfirmationVisible: Boolean = false,
    val loadError: String? = null,
    val actionError: String? = null,
    val saveConflict: GymConfigurationConflict? = null,
    val deleteConflict: List<GymRoutineReference>? = null,
) {
  val isBusy: Boolean
    get() = isSaving || isDeleting

  val canSave: Boolean
    get() = !isLoading && !isBusy && loadError == null && equipment != null && name.isNotBlank()

  val filteredEquipment: List<EquipmentCatalog.Equipment>
    get() =
        EquipmentCatalog.search(query).filter { entry ->
          entry.id in equipment.orEmpty().map { it.id }.toSet() &&
              (mode == GymEquipmentMode.ALL || entry.id in selectedEquipmentIds)
        }

  val groupedEquipment: Map<String, List<EquipmentCatalog.Equipment>>
    get() = filteredEquipment.groupBy { it.group }.toSortedMap()

  /** Search bulk actions honor the current mode; an unfiltered catalog always keeps full groups. */
  val groupedBulkEquipment: Map<String, List<EquipmentCatalog.Equipment>>
    get() =
        (if (query.isBlank()) {
              EquipmentCatalog.entries.filter { entry ->
                entry.id in equipment.orEmpty().map { it.id }.toSet()
              }
            } else {
              filteredEquipment
            })
            .groupBy { it.group }
            .toSortedMap()

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
    private val savedStateHandle: SavedStateHandle,
    private val repository: GymRepository,
) : ViewModel() {

  private val gymId: String? = savedStateHandle.get(GymRoutes.GYM_ID_ARG)
  private val copySourceGymId: String? = savedStateHandle.get(GymRoutes.GYM_COPY_SOURCE_ARG)

  private val _uiState =
      MutableStateFlow(
          GymEditorUiState(
              isNew = gymId == null,
              isCopy = copySourceGymId != null,
              isLoading = gymId != null || copySourceGymId != null,
              name = savedStateHandle[DRAFT_NAME] ?: "",
              query = savedStateHandle[DRAFT_QUERY] ?: "",
              selectedEquipmentIds =
                  savedStateHandle.get<ArrayList<String>>(DRAFT_EQUIPMENT)?.toSet().orEmpty(),
              mode =
                  savedStateHandle.get<String>(DRAFT_MODE)?.let {
                    runCatching { GymEquipmentMode.valueOf(it) }.getOrNull()
                  } ?: if (gymId == null) GymEquipmentMode.ALL else GymEquipmentMode.SELECTED,
              expandedGroups =
                  savedStateHandle.get<ArrayList<String>>(DRAFT_EXPANDED)?.toSet().orEmpty(),
          ),
      )
  val uiState: StateFlow<GymEditorUiState> = _uiState.asStateFlow()

  private val _finished = Channel<Unit>(Channel.BUFFERED)
  /** Сохранение либо удаление завершено — экран может вернуться к списку. */
  val finished = _finished.receiveAsFlow()
  private val _exit = Channel<Unit>(Channel.BUFFERED)
  val exit = _exit.receiveAsFlow()

  init {
    _uiState.update { it.copy(equipment = EquipmentCatalog.entries) }
    viewModelScope.launch {
      repository
          .observeExerciseCatalog()
          .catch { emit(emptyList()) }
          .collect { exercises ->
            _uiState.update {
              it.copy(exercises = exercises.sortedBy { exercise -> exercise.name.lowercase() })
            }
          }
    }
    (gymId ?: copySourceGymId)?.let { id ->
      viewModelScope.launch { load(id, copySourceGymId != null) }
    }
  }

  private suspend fun load(id: String, asCopy: Boolean = false) {
    try {
      if (savedStateHandle.get<Boolean>(DRAFT_LOADED) == true) {
        _uiState.update { it.copy(isLoading = false) }
        return
      }
      val gym = repository.getGym(id)
      _uiState.update { state ->
        if (gym == null) {
          state.copy(isLoading = false, loadError = "Зал не найден.")
        } else {
          state.copy(
              isLoading = false,
              name = if (asCopy) "" else gym.name,
              selectedEquipmentIds = gym.equipmentIds,
              selectedExerciseIds = gym.exercises.mapTo(linkedSetOf()) { it.id },
              mode = if (asCopy) GymEquipmentMode.ALL else GymEquipmentMode.SELECTED,
              copySourceWasLegacy = asCopy && !gym.inventoryConfigured,
          )
        }
      }
      val loaded = _uiState.value
      savedStateHandle[DRAFT_NAME] = loaded.name
      savedStateHandle[DRAFT_EQUIPMENT] = ArrayList(loaded.selectedEquipmentIds)
      savedStateHandle[DRAFT_INITIAL_NAME] = loaded.name
      savedStateHandle[DRAFT_INITIAL_EQUIPMENT] = ArrayList(loaded.selectedEquipmentIds)
      savedStateHandle[DRAFT_LOADED] = true
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
      if (state.isBusy) state else state.copy(name = value, bulkUndo = null, actionError = null)
    }
    savedStateHandle[DRAFT_NAME] = value
  }

  fun setQuery(value: String) {
    _uiState.update { it.copy(query = value) }
    savedStateHandle[DRAFT_QUERY] = value
  }

  fun clearQuery() {
    _uiState.update { it.copy(query = "") }
    savedStateHandle[DRAFT_QUERY] = ""
  }

  fun setMode(mode: GymEquipmentMode) {
    _uiState.update { if (it.isBusy) it else it.copy(mode = mode) }
    savedStateHandle[DRAFT_MODE] = mode.name
  }

  fun toggleGroupExpanded(group: String) {
    _uiState.update { state ->
      val expanded = state.expandedGroups.toMutableSet()
      if (!expanded.add(group)) expanded.remove(group)
      state.copy(expandedGroups = expanded)
    }
    savedStateHandle[DRAFT_EXPANDED] = ArrayList(_uiState.value.expandedGroups)
  }

  fun setPreview(enabled: Boolean) {
    if (!enabled) {
      _uiState.update { it.copy(preview = false, previewLoading = false, previewExercises = null) }
      return
    }
    val exercises = _uiState.value.exercises ?: return
    val equipment = _uiState.value.selectedEquipmentIds
    _uiState.update { it.copy(preview = true, previewLoading = true, previewExercises = null) }
    viewModelScope.launch {
      try {
        val available =
            exercises.filter { exercise ->
              when (val requirements = repository.requirementsFor(exercise)) {
                is com.valerochka1337.valerochkagym.domain.ExerciseEquipmentRequirements.Required ->
                    requirements.equipmentIds.all { EquipmentCatalog.covers(equipment, it) }
                com.valerochka1337.valerochkagym.domain.ExerciseEquipmentRequirements
                    .ExplicitNone -> true
                com.valerochka1337.valerochkagym.domain.ExerciseEquipmentRequirements
                    .UnknownLegacy -> false
              }
            }
        _uiState.update { it.copy(previewLoading = false, previewExercises = available) }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        _uiState.update {
          it.copy(
              previewLoading = false,
              previewExercises = emptyList(),
              actionError = "Не удалось проверить доступные упражнения.",
          )
        }
      }
    }
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

  fun toggleEquipment(equipmentId: String) {
    _uiState.update { state ->
      if (state.isBusy || state.equipment?.none { it.id == equipmentId } != false) state
      else {
        val selected = state.selectedEquipmentIds.toMutableSet()
        if (!selected.add(equipmentId)) selected.remove(equipmentId)
        state.copy(
            selectedEquipmentIds = selected,
            bulkUndo = null,
            preview = false,
            previewLoading = false,
            previewExercises = null,
            actionError = null,
            saveConflict = null,
        )
      }
    }
    savedStateHandle[DRAFT_EQUIPMENT] = ArrayList(_uiState.value.selectedEquipmentIds)
  }

  fun selectAll() =
      setScope(_uiState.value.equipment.orEmpty().mapTo(linkedSetOf()) { it.id }, true)

  fun clearAll() =
      setScope(_uiState.value.equipment.orEmpty().mapTo(linkedSetOf()) { it.id }, false)

  fun selectFound() =
      setScope(_uiState.value.filteredEquipment.mapTo(linkedSetOf()) { it.id }, true)

  fun clearFound() =
      setScope(_uiState.value.filteredEquipment.mapTo(linkedSetOf()) { it.id }, false)

  /** Bulk scope is the complete pool when there is no query, otherwise current visible results. */
  fun toggleAll() {
    val state = _uiState.value
    val scope = if (state.query.isBlank()) state.equipment.orEmpty() else state.filteredEquipment
    toggleScope(scope.mapTo(linkedSetOf()) { it.id })
  }

  fun toggleGroup(group: String) =
      toggleScope(
          _uiState.value.groupedBulkEquipment[group].orEmpty().mapTo(linkedSetOf()) { it.id },
      )

  fun undoBulk() {
    _uiState.update { state ->
      val previous = state.bulkUndo ?: return@update state
      state.copy(
          selectedEquipmentIds = previous,
          bulkUndo = null,
          preview = false,
          previewLoading = false,
          previewExercises = null,
      )
    }
    savedStateHandle[DRAFT_EQUIPMENT] = ArrayList(_uiState.value.selectedEquipmentIds)
  }

  private fun toggleScope(scope: Set<String>) {
    if (scope.isEmpty()) return
    _uiState.update { state ->
      if (state.isBusy) return@update state
      val selected = state.selectedEquipmentIds.toMutableSet()
      if (scope.all { it in selected }) selected.removeAll(scope) else selected.addAll(scope)
      state.copy(
          selectedEquipmentIds = selected,
          bulkUndo = state.selectedEquipmentIds,
          preview = false,
          previewLoading = false,
          previewExercises = null,
          actionError = null,
          saveConflict = null,
      )
    }
    savedStateHandle[DRAFT_EQUIPMENT] = ArrayList(_uiState.value.selectedEquipmentIds)
  }

  private fun setScope(scope: Set<String>, selected: Boolean) {
    _uiState.update { state ->
      if (state.isBusy) state
      else {
        val values = state.selectedEquipmentIds.toMutableSet()
        if (selected) values.addAll(scope) else values.removeAll(scope)
        state.copy(
            selectedEquipmentIds = values,
            bulkUndo = state.selectedEquipmentIds,
            preview = false,
            previewLoading = false,
            previewExercises = null,
        )
      }
    }
    savedStateHandle[DRAFT_EQUIPMENT] = ArrayList(_uiState.value.selectedEquipmentIds)
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
            repository.saveGymInventory(
                id = gymId,
                name = trimmedName,
                equipmentIds = state.selectedEquipmentIds,
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

  fun requestExit() {
    val state = _uiState.value
    if (state.isBusy) return
    if (hasUnsavedChanges(state)) {
      _uiState.update { it.copy(discardConfirmationVisible = true) }
    } else {
      viewModelScope.launch { _exit.send(Unit) }
    }
  }

  fun continueEditing() {
    _uiState.update { it.copy(discardConfirmationVisible = false) }
  }

  fun discardDraft() {
    _uiState.update { it.copy(discardConfirmationVisible = false) }
    viewModelScope.launch { _exit.send(Unit) }
  }

  private fun hasUnsavedChanges(state: GymEditorUiState): Boolean {
    if (gymId == null) return state.name.isNotBlank() || state.selectedEquipmentIds.isNotEmpty()
    val initialName: String = savedStateHandle[DRAFT_INITIAL_NAME] ?: state.name
    val initialEquipment =
        savedStateHandle.get<ArrayList<String>>(DRAFT_INITIAL_EQUIPMENT)?.toSet()
            ?: state.selectedEquipmentIds
    return state.name != initialName || state.selectedEquipmentIds != initialEquipment
  }

  private companion object {
    const val DRAFT_NAME = "gym_equipment_name"
    const val DRAFT_QUERY = "gym_equipment_query"
    const val DRAFT_EQUIPMENT = "gym_equipment_selected"
    const val DRAFT_MODE = "gym_equipment_mode"
    const val DRAFT_EXPANDED = "gym_equipment_expanded"
    const val DRAFT_LOADED = "gym_equipment_loaded"
    const val DRAFT_INITIAL_NAME = "gym_equipment_initial_name"
    const val DRAFT_INITIAL_EQUIPMENT = "gym_equipment_initial_equipment"
  }
}
