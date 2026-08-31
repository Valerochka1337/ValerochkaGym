package com.valerochka1337.valerochkagym.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.ai.ExerciseAiGenerationResult
import com.valerochka1337.valerochkagym.data.ai.ExerciseAiGenerator
import com.valerochka1337.valerochkagym.data.ai.AiApiConfigurationProvider
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.data.db.entity.group
import com.valerochka1337.valerochkagym.data.db.entity.withNextUpdatedAt
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.NewExerciseConfiguration
import com.valerochka1337.valerochkagym.domain.NoOpGymRepository
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.worker.ConfigurationUploadScheduler
import com.valerochka1337.valerochkagym.worker.NoOpConfigurationUploadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Screen state for the exercise library. [exercises] is already filtered by
 * [query] and [selectedGroup]; a null value means the list hasn't loaded yet
 * (distinct from a loaded-but-empty result), so the UI can suppress the empty
 * state until the first emission. [isEmpty] is true only for a loaded empty list.
 */
data class ExerciseLibraryUiState(
    val query: String = "",
    val selectedGroup: MuscleGroup? = null,
    val exercises: List<ExerciseEntity>? = null,
    val gymNames: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = exercises?.isEmpty() == true
}

/**
 * Начальное состояние шторки-редактора упражнения. [exerciseId] `null` — создание нового.
 *
 * [editableName] выключается для встроенных упражнений: их название — ключ, по которому карта
 * мышц ищется в каталоге, а выгрузка в Google Sheets сопоставляет по нему строки при импорте.
 * Разметку мышц при этом менять можно: у встроенных упражнений она тоже лишь оценка.
 */
data class ExerciseEditorState(
    val exerciseId: Long?,
    val name: String,
    val type: ExerciseType,
    val loads: Map<Muscle, Int>,
    val editableName: Boolean,
    /** ИИ сопоставил описание с записью библиотеки; редактор предупредит об изменении, а не создании. */
    val wasFoundByAi: Boolean = false,
    /** Подтверждённое сохранением действие включит найденную запись во все выбранные залы. */
    val assignToSelectedGyms: Boolean = false,
    /** Контекст picker-а: после успешного сохранения запись сразу попадёт сюда. */
    val selectionTarget: String? = null,
    /** Человекочитаемый состав залов для явного предупреждения перед изменением конфигураций. */
    val selectedGymNames: List<String> = emptyList(),
    val isSaving: Boolean = false,
    val saveError: String? = null,
)

/** Состояние первичной шторки создания упражнения по описанию. */
data class ExerciseAiCreationState(
    val description: String = "",
    val isGenerating: Boolean = false,
    val aiConfigured: Boolean = false,
    val error: String? = null,
    val modelUnavailable: Boolean = false,
)

data class SavedExerciseResult(
    val exercise: ExerciseEntity,
    val addedToWorkout: Boolean,
)

/** Заглушка оставляет ручной путь доступным в прямых unit-тестах без Hilt. */
private object NoOpExerciseAiGenerator : ExerciseAiGenerator {
    override suspend fun generate(description: String): ExerciseAiGenerationResult =
        ExerciseAiGenerationResult.Failure("Настройте нейросеть в настройках")
}

private object NoOpAiApiConfigurationProvider : AiApiConfigurationProvider {
    override val isConfigured = MutableStateFlow(false)

    override suspend fun connection() = null

    override suspend fun requestConfiguration() = null
}

/**
 * Backs the exercise library screen. Search and muscle-group filtering happen
 * in memory (SQLite can't case-fold Cyrillic — see [ExerciseDao.getAll]).
 *
 * Кроме списка ведёт шторку-редактор: создание своего упражнения с разметкой мышц по модели
 * тела и правку разметки у любого упражнения (см. [ExerciseEditorState]).
 */
@HiltViewModel
class ExerciseLibraryViewModel @Inject constructor(
    private val exerciseDao: ExerciseDao,
    private val exerciseMuscleDao: ExerciseMuscleDao,
    private val exerciseAiGenerator: ExerciseAiGenerator = NoOpExerciseAiGenerator,
    private val aiApiConfigurationProvider: AiApiConfigurationProvider =
        NoOpAiApiConfigurationProvider,
    savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val gymRepository: GymRepository = NoOpGymRepository,
    private val configurationUploadScheduler: ConfigurationUploadScheduler =
        NoOpConfigurationUploadScheduler,
) : ViewModel() {

    private val selectedGymIds: Set<String> = savedStateHandle
        .get<String>(GymRoutes.GYM_IDS_ARG)
        .orEmpty()
        .split(',')
        .filterTo(linkedSetOf()) { it.isNotBlank() }
    private val workoutId: String? = savedStateHandle.get<String>(GymRoutes.WORKOUT_ID_ARG)

    private val query = MutableStateFlow("")
    private val selectedGroup = MutableStateFlow<MuscleGroup?>(null)
    private val _editor = MutableStateFlow<ExerciseEditorState?>(null)
    private val _aiCreation = MutableStateFlow<ExerciseAiCreationState?>(null)
    private val aiConfigured = MutableStateFlow(false)
    private var generationJob: Job? = null
    private var generationId = 0L

    private val _savedExercise = Channel<SavedExerciseResult>(Channel.BUFFERED)

    /** Новая/найденная запись уже сохранена и должна сразу вернуться в вызывающий picker. */
    val savedExercise = _savedExercise.receiveAsFlow()

    /** Открытая шторка редактора; `null` — закрыта. */
    val editor: StateFlow<ExerciseEditorState?> = _editor.asStateFlow()

    /** Открытая ИИ-шторка; `null` — создание сейчас не начато. */
    val aiCreation: StateFlow<ExerciseAiCreationState?> = _aiCreation.asStateFlow()

    private val sourceExercises = if (selectedGymIds.isEmpty() || gymRepository === NoOpGymRepository) {
        exerciseDao.getAll()
    } else {
        gymRepository.observeAvailableExercises(selectedGymIds)
    }

    val uiState: StateFlow<ExerciseLibraryUiState> =
        combine(sourceExercises, query, selectedGroup, gymRepository.observeGyms()) {
                all, currentQuery, group, gyms ->
            val trimmed = currentQuery.trim()
            val filtered = all.filter { exercise ->
                (group == null || exercise.muscleGroup == group) &&
                    (trimmed.isEmpty() || exercise.name.contains(trimmed, ignoreCase = true))
            }
            ExerciseLibraryUiState(
                query = currentQuery,
                selectedGroup = group,
                exercises = filtered,
                gymNames = gyms.filter { it.id in selectedGymIds }.map { it.name },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ExerciseLibraryUiState(),
        )

    init {
        viewModelScope.launch {
            aiApiConfigurationProvider.isConfigured.collect { configured ->
                aiConfigured.value = configured
                _aiCreation.update { state -> state?.copy(aiConfigured = configured) }
            }
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun clearQuery() {
        query.value = ""
    }

    /** Toggles the group filter: tapping the active group clears the filter. */
    fun onGroupClicked(group: MuscleGroup) {
        selectedGroup.value = if (selectedGroup.value == group) null else group
    }

    fun openCreate() {
        closeAiCreation()
        _aiCreation.value = ExerciseAiCreationState(aiConfigured = aiConfigured.value)
    }

    fun onAiDescriptionChange(value: String) {
        _aiCreation.update { state ->
            state?.takeUnless { it.isGenerating }?.copy(
                description = value,
                error = null,
                modelUnavailable = false,
            )
        }
    }

    /** Закрывает первичную шторку и прекращает сетевой запрос, если он ещё идёт. */
    fun closeAiCreation() {
        generationId++
        generationJob?.cancel()
        generationJob = null
        _aiCreation.value = null
    }

    /** Ручной путь всегда доступен, в том числе без ключа или сети. */
    fun openManualCreate() {
        closeAiCreation()
        _editor.value = emptyEditorState()
    }

    fun generateAiExercise() {
        val state = _aiCreation.value ?: return
        if (state.isGenerating || !state.aiConfigured || state.description.trim().isEmpty()) return

        val currentGenerationId = ++generationId
        _aiCreation.value = state.copy(isGenerating = true, error = null, modelUnavailable = false)
        generationJob = viewModelScope.launch {
            when (val result = exerciseAiGenerator.generate(state.description)) {
                is ExerciseAiGenerationResult.Failure -> showGenerationFailure(
                    generationId = currentGenerationId,
                    message = result.message,
                    modelUnavailable = result.modelUnavailable,
                )
                is ExerciseAiGenerationResult.New -> {
                    if (currentGenerationId != generationId) return@launch
                    _aiCreation.value = null
                    _editor.value = ExerciseEditorState(
                        exerciseId = null,
                        name = result.name,
                        type = result.type,
                        loads = result.loads.associate { it.muscle to it.contribution },
                        editableName = true,
                        selectionTarget = pickerTarget(),
                        selectedGymNames = uiState.value.gymNames,
                    )
                }

                is ExerciseAiGenerationResult.Existing -> openExistingFromGeneration(
                    generationId = currentGenerationId,
                    exerciseId = result.exerciseId,
                )
            }
        }
    }

    private suspend fun openExistingFromGeneration(generationId: Long, exerciseId: Long) {
        val exercise = exerciseDao.getById(exerciseId)
        if (exercise == null) {
            showGenerationFailure(generationId, "Упражнение больше не найдено")
            return
        }
        val loads = exerciseMuscleDao.getForExercise(exercise.id)
            .associate { it.muscle to it.contribution }
        val needsGymAssignment = selectedGymIds.isNotEmpty() &&
            gymRepository.unavailableExercises(selectedGymIds, setOf(exercise.id)).isNotEmpty()
        if (generationId != this.generationId) return
        _aiCreation.value = null
        _editor.value = ExerciseEditorState(
            exerciseId = exercise.id,
            name = exercise.name,
            type = exercise.type,
            loads = loads,
            editableName = exercise.isCustom,
            wasFoundByAi = true,
            assignToSelectedGyms = needsGymAssignment,
            selectionTarget = pickerTarget(),
            selectedGymNames = uiState.value.gymNames,
        )
    }

    private fun showGenerationFailure(
        generationId: Long,
        message: String,
        modelUnavailable: Boolean = false,
    ) {
        if (generationId != this.generationId) return
        _aiCreation.update { state ->
            state?.copy(
                isGenerating = false,
                error = message,
                modelUnavailable = modelUnavailable,
            )
        }
    }

    private fun emptyEditorState(): ExerciseEditorState = ExerciseEditorState(
            exerciseId = null,
            name = "",
            type = ExerciseType.STRENGTH,
            loads = emptyMap(),
            editableName = true,
            selectionTarget = pickerTarget(),
            selectedGymNames = uiState.value.gymNames,
        )

    /** Открывает разметку существующего упражнения — текущая карта подгружается из базы. */
    fun openEdit(exercise: ExerciseEntity) {
        viewModelScope.launch {
            val loads = exerciseMuscleDao.getForExercise(exercise.id)
                .associate { it.muscle to it.contribution }
            _editor.value = ExerciseEditorState(
                exerciseId = exercise.id,
                name = exercise.name,
                type = exercise.type,
                loads = loads,
                editableName = exercise.isCustom,
            )
        }
    }

    fun closeEditor() {
        _editor.value = null
    }

    /**
     * Сохраняет упражнение вместе с разметкой мышц.
     *
     * У нового упражнения крупная группа выводится из самой вовлечённой мышцы — отдельного
     * выбора не требуется. У существующего группа **не** пересчитывается: иначе правка разметки
     * молча переносила бы упражнение в другой фильтр библиотеки и меняла бы колонку
     * `muscle_group` в будущих выгрузках.
     */
    fun saveEditor(name: String, type: ExerciseType, loads: List<MuscleLoad>) {
        val current = _editor.value ?: return
        if (current.isSaving) return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || loads.isEmpty()) return
        val submittedLoads = loads.associate { it.muscle to it.contribution }
        _editor.value = current.copy(
            name = trimmed,
            type = type,
            loads = submittedLoads,
            isSaving = true,
            saveError = null,
        )
        viewModelScope.launch {
            val saved = try {
                if (current.exerciseId == null) {
                    val exercise = ExerciseEntity(
                        name = trimmed,
                        muscleGroup = primaryGroup(loads),
                        type = type,
                        isCustom = true,
                    )
                    val muscleRows = loads.map {
                        ExerciseMuscleEntity(exerciseId = 0, muscle = it.muscle, contribution = it.contribution)
                    }
                    if (gymRepository === NoOpGymRepository) {
                        val exerciseId = exerciseDao.insert(exercise)
                        exerciseMuscleDao.replaceForExercise(
                            exerciseId,
                            muscleRows.map { it.copy(exerciseId = exerciseId) },
                        )
                        exercise.copy(id = exerciseId)
                    } else if (workoutId != null) {
                        gymRepository.createExerciseAssignAndAddToWorkout(
                            NewExerciseConfiguration(exercise, muscleRows),
                            selectedGymIds,
                            workoutId,
                        )
                    } else {
                        gymRepository.createExerciseAndAssign(
                            NewExerciseConfiguration(exercise, muscleRows),
                            selectedGymIds,
                        )
                    }
                } else {
                    val existing = exerciseDao.getById(current.exerciseId)
                        ?: return@launch showSaveFailure()
                    val updated = existing.copy(
                        name = if (current.editableName) trimmed else existing.name,
                        type = type,
                    ).withNextUpdatedAt()
                    val muscleRows = loads.map {
                        ExerciseMuscleEntity(existing.id, it.muscle, it.contribution)
                    }
                    if (gymRepository === NoOpGymRepository) {
                        exerciseDao.update(updated)
                        exerciseMuscleDao.replaceForExercise(existing.id, muscleRows)
                        configurationUploadScheduler.scheduleExercise(updated.syncId)
                        updated
                    } else if (workoutId != null && current.wasFoundByAi) {
                        gymRepository.updateExerciseAssignAndAddToWorkout(
                            configuration = NewExerciseConfiguration(updated, muscleRows),
                            gymIds = selectedGymIds,
                            workoutId = workoutId,
                        )
                    } else {
                        gymRepository.updateExerciseAndAssign(
                            configuration = NewExerciseConfiguration(updated, muscleRows),
                            gymIds = if (current.assignToSelectedGyms) selectedGymIds else emptySet(),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (saved == null) return@launch showSaveFailure()
            _editor.value = null
            if (current.exerciseId == null || current.wasFoundByAi) {
                _savedExercise.send(
                    SavedExerciseResult(
                        exercise = saved,
                        addedToWorkout = workoutId != null && gymRepository !== NoOpGymRepository &&
                            (current.exerciseId == null || current.wasFoundByAi),
                    ),
                )
            }
        }
    }

    private fun showSaveFailure() {
        _editor.update { state ->
            state?.copy(
                isSaving = false,
                saveError = "Не удалось сохранить упражнение. Проверьте выбранные залы и повторите.",
            )
        }
    }

    private fun pickerTarget(): String =
        if (workoutId != null) "активную тренировку" else "текущую программу"

    private fun primaryGroup(loads: List<MuscleLoad>): MuscleGroup =
        loads.maxByOrNull { it.contribution }?.muscle?.group() ?: MuscleGroup.FULL_BODY
}
