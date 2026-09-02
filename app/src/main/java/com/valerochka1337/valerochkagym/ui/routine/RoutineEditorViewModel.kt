package com.valerochka1337.valerochkagym.ui.routine

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.withNextUpdatedAt
import com.valerochka1337.valerochkagym.domain.GymConfiguration
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.NoOpGymRepository
import com.valerochka1337.valerochkagym.domain.RoutineConfigurationDraft
import com.valerochka1337.valerochkagym.domain.SaveRoutineConfigurationResult
import com.valerochka1337.valerochkagym.domain.ExerciseVariantRepository
import com.valerochka1337.valerochkagym.domain.NoOpExerciseVariantRepository
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseVariantEntity
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.worker.NoOpRoutineUploadScheduler
import com.valerochka1337.valerochkagym.worker.RoutineUploadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Количество пустых подходов по умолчанию у только что добавленного силового упражнения. */
private const val DEFAULT_STRENGTH_SETS = 3

/** Одно упражнение в редакторе программы. */
data class EditorExercise(
    /** Стабильный id строки только для редактора: одно упражнение может повторяться в программе. */
    val editorId: String = UUID.randomUUID().toString(),
    val exerciseId: Long,
    val exerciseName: String,
    val exerciseType: ExerciseType,
    val variantSyncId: String? = null,
    val variantName: String? = null,
    val variantArchived: Boolean = false,
    val restSeconds: Int?,
    val plannedSets: List<PlannedSet>,
)

/**
 * Состояние редактора программы. [isNew] отличает создание от редактирования (для заголовка);
 * [isValid] — можно ли сохранять (непустое имя и хотя бы одно упражнение).
 */
data class RoutineEditorUiState(
    val isNew: Boolean = true,
    // Пока true, тело редактора не рисуется: иначе для существующей программы кадр показывает
    // пустую «готовую» форму, которую тут же подменяет загруженная — это и есть моргание.
    val isLoading: Boolean = false,
    /** Невидимый UI ключ: сохраняет cloud-идентичность при редактировании существующей программы. */
    val syncId: String = UUID.randomUUID().toString(),
    /** Последняя версия снимка; следующее сохранение делает её строго больше. */
    val updatedAt: Long = 0L,
    val name: String = "",
    val note: String = "",
    val exercises: List<EditorExercise> = emptyList(),
    val gyms: List<GymConfiguration> = emptyList(),
    val selectedGymIds: Set<String> = emptySet(),
    val conflictingExercises: List<EditorExercise> = emptyList(),
    val saveError: String? = null,
    val isSaving: Boolean = false,
) {
    val isValid: Boolean
        get() = !isSaving && name.trim().isNotEmpty() && exercises.isNotEmpty() &&
            conflictingExercises.isEmpty()
}

/**
 * Бэкенд редактора программы. Загружает существующую программу по routineId из
 * [SavedStateHandle] (null — новая). Все правки идут в память; при [save] пишет
 * routine + упражнения (позиции по индексу) и шлёт событие [saved] для popBackStack.
 */
@HiltViewModel
class RoutineEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val routineDao: RoutineDao,
    private val exerciseDao: ExerciseDao,
    private val routineUploadScheduler: RoutineUploadScheduler = NoOpRoutineUploadScheduler,
    private val gymRepository: GymRepository = NoOpGymRepository,
    private val variantRepository: ExerciseVariantRepository = NoOpExerciseVariantRepository,
) : ViewModel() {

    private val routineId: Long? =
        savedStateHandle.get<String>(GymRoutes.ROUTINE_ID_ARG)?.toLongOrNull()

    private val _uiState = MutableStateFlow(
        RoutineEditorUiState(isNew = routineId == null, isLoading = routineId != null),
    )
    val uiState: StateFlow<RoutineEditorUiState> = _uiState.asStateFlow()
    private val _pendingVariantSelection = MutableStateFlow<PendingRoutineVariantSelection?>(null)
    val pendingVariantSelection: StateFlow<PendingRoutineVariantSelection?> = _pendingVariantSelection

    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()

    init {
        viewModelScope.launch {
            gymRepository.observeGyms().collect { gyms ->
                _uiState.update { state -> state.withGyms(gyms) }
            }
        }
        if (routineId != null) {
            viewModelScope.launch { load(routineId) }
        }
    }

    private suspend fun load(id: Long) {
        val full = routineDao.getRoutineWithExercises(id)
        if (full == null) {
            // Программа исчезла (например, удалена) — снимаем загрузку, чтобы не залипнуть.
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        val variantsByExercise = full.exercises
            .map { it.exercise.id }
            .distinct()
            .associateWith { exerciseId -> variantRepository.observeForExercise(exerciseId).first().associateBy { it.syncId } }
        _uiState.update { current ->
            RoutineEditorUiState(
                isNew = false,
                syncId = full.routine.syncId,
                updatedAt = full.routine.updatedAt,
                name = full.routine.name,
                note = full.routine.note,
                exercises = full.exercises
                    .sortedBy { it.routineExercise.position }
                    .map { item ->
                        EditorExercise(
                            exerciseId = item.exercise.id,
                            exerciseName = item.exercise.name,
                            exerciseType = item.exercise.type,
                            variantSyncId = item.routineExercise.variantSyncId,
                            variantName = item.routineExercise.variantSyncId?.let { variantId ->
                                variantsByExercise[item.exercise.id]?.get(variantId)?.name
                            },
                            variantArchived = item.routineExercise.variantSyncId?.let { variantId ->
                                variantsByExercise[item.exercise.id]?.get(variantId)?.isArchived
                            } ?: false,
                            restSeconds = item.routineExercise.restSeconds,
                            plannedSets = item.routineExercise.plannedSets,
                        )
                    },
                gyms = current.gyms,
                selectedGymIds = full.gyms.mapTo(linkedSetOf()) { it.syncId },
            ).recalculateConflicts()
        }
    }

    fun setName(value: String) {
        _uiState.update { if (it.isSaving) it else it.copy(name = value, saveError = null) }
    }

    fun toggleGym(gymId: String) {
        _uiState.update { state ->
            if (state.isSaving) return@update state
            val selected = state.selectedGymIds.toMutableSet()
            if (!selected.add(gymId)) selected.remove(gymId)
            state.copy(selectedGymIds = selected, saveError = null).recalculateConflicts()
        }
    }

    /** Добавляет упражнение по id (после выбора в библиотеке). */
    fun addExerciseById(exerciseId: Long) {
        viewModelScope.launch {
            val exercise = exerciseDao.getById(exerciseId) ?: return@launch
            val variants = variantRepository.activeForExercise(exerciseId)
            if (variants.isEmpty()) addExercise(exercise) else {
                _pendingVariantSelection.value = PendingRoutineVariantSelection(exercise, variants)
            }
        }
    }

    fun chooseVariant(variantSyncId: String?) {
        val pending = _pendingVariantSelection.value ?: return
        _pendingVariantSelection.value = null
        addExercise(
            exercise = pending.exercise,
            variantSyncId = variantSyncId,
            variantName = pending.variants.firstOrNull { it.syncId == variantSyncId }?.name,
        )
    }

    fun cancelVariantSelection() { _pendingVariantSelection.value = null }

    fun addExercise(exercise: ExerciseEntity, variantSyncId: String? = null, variantName: String? = null) {
        val setCount = if (exercise.type == ExerciseType.STRENGTH) DEFAULT_STRENGTH_SETS else 1
        val editorExercise = EditorExercise(
            exerciseId = exercise.id,
            exerciseName = exercise.name,
            exerciseType = exercise.type,
            variantSyncId = variantSyncId,
            variantName = variantName,
            restSeconds = null,
            plannedSets = List(setCount) { PlannedSet() },
        )
        _uiState.update { state ->
            if (state.isSaving) return@update state
            state.copy(exercises = state.exercises + editorExercise, saveError = null)
                .recalculateConflicts()
        }
    }

    fun removeExercise(index: Int) {
        _uiState.update { state ->
            if (state.isSaving || index !in state.exercises.indices) return@update state
            state.copy(
                exercises = state.exercises.toMutableList().apply { removeAt(index) },
                saveError = null,
            ).recalculateConflicts()
        }
    }

    /** Синхронно переставляет упражнение для drag-and-drop и действий TalkBack. */
    fun moveExercise(fromIndex: Int, toIndex: Int) {
        _uiState.update { state ->
            if (
                state.isSaving ||
                fromIndex !in state.exercises.indices ||
                toIndex !in state.exercises.indices ||
                fromIndex == toIndex
            ) {
                return@update state
            }
            state.copy(
                exercises = state.exercises.toMutableList().apply {
                    add(toIndex, removeAt(fromIndex))
                },
            )
        }
    }

    fun setRest(exIndex: Int, restSeconds: Int?) {
        updateExercise(exIndex) { it.copy(restSeconds = restSeconds) }
    }

    /** Добавляет подход как копию последнего (или пустой, если подходов ещё нет). */
    fun addPlannedSet(exIndex: Int) {
        updateExercise(exIndex) { exercise ->
            val newSet = exercise.plannedSets.lastOrNull() ?: PlannedSet()
            exercise.copy(plannedSets = exercise.plannedSets + newSet)
        }
    }

    fun removePlannedSet(exIndex: Int, setIndex: Int) {
        updateExercise(exIndex) { exercise ->
            if (setIndex !in exercise.plannedSets.indices) return@updateExercise exercise
            exercise.copy(
                plannedSets = exercise.plannedSets.toMutableList().apply { removeAt(setIndex) },
            )
        }
    }

    fun updatePlannedSet(exIndex: Int, setIndex: Int, plannedSet: PlannedSet) {
        updateExercise(exIndex) { exercise ->
            if (setIndex !in exercise.plannedSets.indices) return@updateExercise exercise
            exercise.copy(
                plannedSets = exercise.plannedSets.toMutableList().apply { this[setIndex] = plannedSet },
            )
        }
    }

    private inline fun updateExercise(index: Int, transform: (EditorExercise) -> EditorExercise) {
        _uiState.update { state ->
            if (state.isSaving || index !in state.exercises.indices) return@update state
            state.copy(
                exercises = state.exercises.toMutableList().apply { this[index] = transform(this[index]) },
            )
        }
    }

    fun save() {
        val state = _uiState.value
        if (!state.isValid) return
        _uiState.update { it.copy(isSaving = true, saveError = null) }
        viewModelScope.launch {
            // @Upsert возвращает -1 при обновлении существующей строки, поэтому для правки
            // берём уже известный routineId, а результат upsert используем только для новой.
            val routine = RoutineEntity(
                id = routineId ?: 0,
                syncId = state.syncId,
                updatedAt = state.updatedAt,
                name = state.name.trim(),
                note = state.note,
            ).withNextUpdatedAt()
            val entities = state.exercises.mapIndexed { index, exercise ->
                RoutineExerciseEntity(
                    routineId = routineId ?: 0,
                    exerciseId = exercise.exerciseId,
                    variantSyncId = exercise.variantSyncId,
                    position = index,
                    restSeconds = exercise.restSeconds,
                    plannedSets = exercise.plannedSets,
                )
            }
            if (gymRepository === NoOpGymRepository) {
                saveLegacy(routine, entities)
                return@launch
            }
            when (
                val result = gymRepository.saveRoutineConfiguration(
                    RoutineConfigurationDraft(
                        routine = routine,
                        exercises = entities,
                        gymIds = state.selectedGymIds,
                    ),
                )
            ) {
                is SaveRoutineConfigurationResult.Saved -> {
                    routineUploadScheduler.schedule(result.routine.syncId)
                    _saved.send(Unit)
                }
                is SaveRoutineConfigurationResult.Conflict -> {
                    _uiState.update {
                        it.copy(
                            conflictingExercises = it.exercises
                                .filter { editor -> result.exercises.any { exercise -> exercise.id == editor.exerciseId } }
                                .distinctBy(EditorExercise::exerciseId),
                            saveError = "Некоторые упражнения недоступны во всех выбранных залах",
                            isSaving = false,
                        )
                    }
                }
                SaveRoutineConfigurationResult.GymNotFound -> {
                    _uiState.update {
                        it.copy(
                            saveError = "Один из выбранных залов больше не существует",
                            isSaving = false,
                        )
                    }
                }
                SaveRoutineConfigurationResult.Failure -> {
                    _uiState.update {
                        it.copy(saveError = "Не удалось сохранить программу", isSaving = false)
                    }
                }
            }
        }
    }

    private suspend fun saveLegacy(
        routine: RoutineEntity,
        entities: List<RoutineExerciseEntity>,
    ) {
        val insertedId = routineDao.upsertRoutine(routine)
        val id = routineId ?: insertedId
        routineDao.replaceRoutineExercises(
            id,
            entities.map { it.copy(routineId = id) },
        )
        routineUploadScheduler.schedule(routine.syncId)
        _saved.send(Unit)
    }
}

data class PendingRoutineVariantSelection(
    val exercise: ExerciseEntity,
    val variants: List<ExerciseVariantEntity>,
)

private fun RoutineEditorUiState.withGyms(value: List<GymConfiguration>): RoutineEditorUiState {
    val existingIds = value.mapTo(hashSetOf()) { it.id }
    return copy(
        gyms = value,
        selectedGymIds = selectedGymIds.filterTo(linkedSetOf()) { it in existingIds },
    ).recalculateConflicts()
}

private fun RoutineEditorUiState.recalculateConflicts(): RoutineEditorUiState {
    if (selectedGymIds.isEmpty()) return copy(conflictingExercises = emptyList())
    val selected = gyms.filter { it.id in selectedGymIds }
    if (selected.size != selectedGymIds.size) return this
    val unavailableIds = exercises.asSequence()
        .map { it.exerciseId }
        .filter { exerciseId -> selected.any { gym -> gym.exercises.none { it.id == exerciseId } } }
        .toSet()
    return copy(
        conflictingExercises = exercises.filter { it.exerciseId in unavailableIds }
            .distinctBy(EditorExercise::exerciseId),
        saveError = null,
    )
}
