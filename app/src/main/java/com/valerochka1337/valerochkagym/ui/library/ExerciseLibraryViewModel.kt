package com.valerochka1337.valerochkagym.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.data.db.entity.group
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
)

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
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedGroup = MutableStateFlow<MuscleGroup?>(null)
    private val _editor = MutableStateFlow<ExerciseEditorState?>(null)

    /** Открытая шторка редактора; `null` — закрыта. */
    val editor: StateFlow<ExerciseEditorState?> = _editor.asStateFlow()

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
        _editor.value = ExerciseEditorState(
            exerciseId = null,
            name = "",
            type = ExerciseType.STRENGTH,
            loads = emptyMap(),
            editableName = true,
        )
    }

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
