package com.valerochka1337.valerochkagym.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.ai.ExerciseAiGenerationResult
import com.valerochka1337.valerochkagym.data.ai.ExerciseAiGenerator
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.data.db.entity.group
import com.valerochka1337.valerochkagym.data.settings.OpenRouterKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
)

/** Состояние первичной шторки создания упражнения по описанию. */
data class ExerciseAiCreationState(
    val description: String = "",
    val isGenerating: Boolean = false,
    val keyConfigured: Boolean = false,
    val error: String? = null,
    val modelUnavailable: Boolean = false,
)

/** Заглушка оставляет ручной путь доступным в прямых unit-тестах без Hilt. */
private object NoOpExerciseAiGenerator : ExerciseAiGenerator {
    override suspend fun generate(description: String): ExerciseAiGenerationResult =
        ExerciseAiGenerationResult.Failure("Укажите ключ OpenRouter в настройках")
}

private object NoOpOpenRouterKeyStore : OpenRouterKeyStore {
    override val isConfigured = MutableStateFlow(false)

    override suspend fun save(value: String) = Unit

    override suspend fun read(): String? = null

    override suspend fun clear() = Unit
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
    private val openRouterKeyStore: OpenRouterKeyStore = NoOpOpenRouterKeyStore,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedGroup = MutableStateFlow<MuscleGroup?>(null)
    private val _editor = MutableStateFlow<ExerciseEditorState?>(null)
    private val _aiCreation = MutableStateFlow<ExerciseAiCreationState?>(null)
    private val keyConfigured = MutableStateFlow(false)
    private var generationJob: Job? = null
    private var generationId = 0L

    /** Открытая шторка редактора; `null` — закрыта. */
    val editor: StateFlow<ExerciseEditorState?> = _editor.asStateFlow()

    /** Открытая ИИ-шторка; `null` — создание сейчас не начато. */
    val aiCreation: StateFlow<ExerciseAiCreationState?> = _aiCreation.asStateFlow()

    val uiState: StateFlow<ExerciseLibraryUiState> =
        combine(exerciseDao.getAll(), query, selectedGroup) { all, currentQuery, group ->
            val trimmed = currentQuery.trim()
            val filtered = all.filter { exercise ->
                (group == null || exercise.muscleGroup == group) &&
                    (trimmed.isEmpty() || exercise.name.contains(trimmed, ignoreCase = true))
            }
            ExerciseLibraryUiState(
                query = currentQuery,
                selectedGroup = group,
                exercises = filtered,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ExerciseLibraryUiState(),
        )

    init {
        viewModelScope.launch {
            openRouterKeyStore.isConfigured.collect { configured ->
                keyConfigured.value = configured
                _aiCreation.update { state -> state?.copy(keyConfigured = configured) }
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
        _aiCreation.value = ExerciseAiCreationState(keyConfigured = keyConfigured.value)
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
        if (state.isGenerating || !state.keyConfigured || state.description.trim().isEmpty()) return

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
        if (generationId != this.generationId) return
        _aiCreation.value = null
        _editor.value = ExerciseEditorState(
            exerciseId = exercise.id,
            name = exercise.name,
            type = exercise.type,
            loads = loads,
            editableName = exercise.isCustom,
            wasFoundByAi = true,
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
        val trimmed = name.trim()
        if (trimmed.isEmpty() || loads.isEmpty()) return
        _editor.value = null
        viewModelScope.launch {
            val exerciseId = if (current.exerciseId == null) {
                exerciseDao.insert(
                    ExerciseEntity(
                        name = trimmed,
                        muscleGroup = primaryGroup(loads),
                        type = type,
                        isCustom = true,
                    ),
                )
            } else {
                val existing = exerciseDao.getById(current.exerciseId) ?: return@launch
                exerciseDao.update(
                    existing.copy(
                        name = if (current.editableName) trimmed else existing.name,
                        type = type,
                    ),
                )
                existing.id
            }
            exerciseMuscleDao.replaceForExercise(
                exerciseId = exerciseId,
                rows = loads.map { ExerciseMuscleEntity(exerciseId, it.muscle, it.contribution) },
            )
        }
    }

    private fun primaryGroup(loads: List<MuscleLoad>): MuscleGroup =
        loads.maxByOrNull { it.contribution }?.muscle?.group() ?: MuscleGroup.FULL_BODY
}
