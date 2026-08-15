package com.valerochka1337.valerochkagym.ui.exercise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.di.ComputeDispatcher
import com.valerochka1337.valerochkagym.domain.ExerciseStatistics
import com.valerochka1337.valerochkagym.domain.ExerciseStatisticsCalculator
import com.valerochka1337.valerochkagym.ui.library.ExerciseEditorState
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExerciseDetailUiState(
    val loading: Boolean = true,
    val exercise: ExerciseEntity? = null,
    val loads: List<MuscleLoad> = emptyList(),
    val statistics: ExerciseStatistics? = null,
)

@HiltViewModel
class ExerciseDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exerciseDao: ExerciseDao,
    private val exerciseMuscleDao: ExerciseMuscleDao,
    workoutDao: WorkoutDao,
    statisticsCalculator: ExerciseStatisticsCalculator,
    @ComputeDispatcher computeDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val exerciseId: Long? = savedStateHandle[GymRoutes.EXERCISE_ID_ARG]
    private val _editor = MutableStateFlow<ExerciseEditorState?>(null)
    val editor: StateFlow<ExerciseEditorState?> = _editor.asStateFlow()

    val uiState: StateFlow<ExerciseDetailUiState> = combine(
        exerciseDao.getAll(),
        exerciseMuscleDao.observeAll(),
        workoutDao.observeCompletedSets(),
    ) { exercises, muscleRows, completedSets ->
        val exercise = exercises.firstOrNull { it.id == exerciseId }
        if (exercise == null) {
            ExerciseDetailUiState(loading = false)
        } else {
            val loads = muscleRows
                .asSequence()
                .filter { it.exerciseId == exercise.id }
                .sortedByDescending { it.contribution }
                .map { MuscleLoad(it.muscle, it.contribution) }
                .toList()
            ExerciseDetailUiState(
                loading = false,
                exercise = exercise,
                loads = loads,
                statistics = statisticsCalculator.calculate(
                    type = exercise.type,
                    rows = completedSets.filter { it.exerciseId == exercise.id },
                ),
            )
        }
    }
        .flowOn(computeDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ExerciseDetailUiState(),
        )

    fun openEditor() {
        val state = uiState.value
        val exercise = state.exercise ?: return
        _editor.value = ExerciseEditorState(
            exerciseId = exercise.id,
            name = exercise.name,
            type = exercise.type,
            loads = state.loads.associate { it.muscle to it.contribution },
            editableName = exercise.isCustom,
        )
    }

    fun closeEditor() {
        _editor.value = null
    }

    fun saveEditor(
        name: String,
        type: com.valerochka1337.valerochkagym.data.db.entity.ExerciseType,
        loads: List<MuscleLoad>,
    ) {
        val current = _editor.value ?: return
        val exerciseId = current.exerciseId ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || loads.isEmpty()) return
        _editor.value = null
        viewModelScope.launch {
            val existing = exerciseDao.getById(exerciseId) ?: return@launch
            exerciseDao.update(
                existing.copy(
                    name = if (current.editableName) trimmed else existing.name,
                    type = type,
                ),
            )
            exerciseMuscleDao.replaceForExercise(
                exerciseId = exerciseId,
                rows = loads.map { load ->
                    ExerciseMuscleEntity(exerciseId, load.muscle, load.contribution)
                },
            )
        }
    }
}
