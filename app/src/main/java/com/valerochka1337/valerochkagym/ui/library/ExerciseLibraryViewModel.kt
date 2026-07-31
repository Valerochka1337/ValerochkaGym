package com.valerochka1337.valerochkagym.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
 * Backs the exercise library screen. Search and muscle-group filtering happen
 * in memory (SQLite can't case-fold Cyrillic — see [ExerciseDao.getAll]).
 */
@HiltViewModel
class ExerciseLibraryViewModel @Inject constructor(
    private val exerciseDao: ExerciseDao,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedGroup = MutableStateFlow<MuscleGroup?>(null)

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

    fun createCustomExercise(name: String, group: MuscleGroup, type: ExerciseType) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            exerciseDao.insert(
                ExerciseEntity(
                    name = trimmed,
                    muscleGroup = group,
                    type = type,
                    isCustom = true,
                ),
            )
        }
    }
}
