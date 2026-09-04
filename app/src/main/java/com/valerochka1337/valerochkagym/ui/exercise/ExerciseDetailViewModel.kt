package com.valerochka1337.valerochkagym.ui.exercise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.CanonicalExerciseRegistry
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.data.db.entity.withNextUpdatedAt
import com.valerochka1337.valerochkagym.di.ComputeDispatcher
import com.valerochka1337.valerochkagym.domain.ExerciseStatistics
import com.valerochka1337.valerochkagym.domain.ExerciseStatisticsCalculator
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.NewExerciseConfiguration
import com.valerochka1337.valerochkagym.domain.NoOpGymRepository
import com.valerochka1337.valerochkagym.ui.library.ExerciseEditorState
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
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
    private val gymRepository: GymRepository = NoOpGymRepository,
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
        if (CanonicalExerciseRegistry.isBuiltIn(exercise)) return
        _editor.value = ExerciseEditorState(
            exerciseId = exercise.id,
            name = exercise.name,
            type = exercise.type,
            loads = state.loads.associate { it.muscle to it.contribution },
            editableName = !CanonicalExerciseRegistry.isBuiltIn(exercise),
            needsMuscleMapReview = exercise.needsMuscleMapReview,
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
        if (current.isSaving) return
        val exerciseId = current.exerciseId ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || loads.none { it.contribution == 100 } ||
            loads.any { it.contribution !in setOf(100, 50, 0) }) {
            _editor.value = current.copy(saveError = "Выберите хотя бы одну основную мышцу.")
            return
        }
        _editor.value = current.copy(
            name = trimmed,
            type = type,
            loads = loads.associate { it.muscle to it.contribution },
            isSaving = true,
            saveError = null,
        )
        viewModelScope.launch {
            val existing = exerciseDao.getById(exerciseId)
            if (existing == null) {
                showSaveFailure()
                return@launch
            }
            if (CanonicalExerciseRegistry.isBuiltIn(existing)) {
                _editor.value = null
                return@launch
            }
            val updated = existing.copy(
                name = trimmed,
                type = type,
                needsMuscleMapReview = false,
            ).withNextUpdatedAt()
            val muscleRows = loads.map { load ->
                ExerciseMuscleEntity(exerciseId, load.muscle, load.contribution)
            }
            val saved = try {
                if (gymRepository === NoOpGymRepository) {
                    // Fallback для прямых unit-тестов; production идёт через одну repo-транзакцию.
                    exerciseDao.update(updated)
                    exerciseMuscleDao.replaceForExercise(exerciseId, muscleRows)
                    updated
                } else {
                    gymRepository.updateExerciseAndAssign(
                        configuration = NewExerciseConfiguration(updated, muscleRows),
                        gymIds = emptySet(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (saved == null) return@launch showSaveFailure()
            _editor.value = null
        }
    }

    private fun showSaveFailure() {
        _editor.value = _editor.value?.copy(
            isSaving = false,
            saveError = "Не удалось сохранить упражнение. Попробуйте ещё раз.",
        )
    }
}
