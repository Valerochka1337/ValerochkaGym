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
import com.valerochka1337.valerochkagym.data.db.entity.withNextUpdatedAt
import com.valerochka1337.valerochkagym.di.ComputeDispatcher
import com.valerochka1337.valerochkagym.domain.ExerciseStatistics
import com.valerochka1337.valerochkagym.domain.ExerciseExecutionKey
import com.valerochka1337.valerochkagym.domain.ExecutionGroupToken
import com.valerochka1337.valerochkagym.domain.ExerciseStatisticsCalculator
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.NewExerciseConfiguration
import com.valerochka1337.valerochkagym.domain.NoOpGymRepository
import com.valerochka1337.valerochkagym.domain.ExerciseVariantRepository
import com.valerochka1337.valerochkagym.domain.NoOpExerciseVariantRepository
import com.valerochka1337.valerochkagym.domain.SaveExerciseVariantResult
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseVariantEntity
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
    val variants: List<ExerciseVariantEntity> = emptyList(),
    val variantError: String? = null,
    val selectedExecutionKey: ExerciseExecutionKey? = null,
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
    private val variantRepository: ExerciseVariantRepository = NoOpExerciseVariantRepository,
) : ViewModel() {

    private val exerciseId: Long? = savedStateHandle[GymRoutes.EXERCISE_ID_ARG]
    private val requestedVariantSyncId: String? = ExecutionGroupToken.decode(
        savedStateHandle[GymRoutes.EXECUTION_GROUP_ARG],
    )
    private val _editor = MutableStateFlow<ExerciseEditorState?>(null)
    private val _variantError = MutableStateFlow<String?>(null)
    val editor: StateFlow<ExerciseEditorState?> = _editor.asStateFlow()

    val uiState: StateFlow<ExerciseDetailUiState> = combine(
        exerciseDao.getAll(),
        exerciseMuscleDao.observeAll(),
        workoutDao.observeCompletedSets(),
        exerciseId?.let(variantRepository::observeForExercise) ?: kotlinx.coroutines.flow.flowOf(emptyList()),
        _variantError,
    ) { exercises, muscleRows, completedSets, variants, variantError ->
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
            val exerciseRows = completedSets.filter { it.exerciseId == exercise.id }
            // The route is a selection contract: an explicit none stays none even when a named
            // group is newer, and an absent named group remains an empty named comparison.
            val selectedVariant = requestedVariantSyncId
            ExerciseDetailUiState(
                loading = false,
                exercise = exercise,
                loads = loads,
                selectedExecutionKey = ExerciseExecutionKey(exercise.id, selectedVariant),
                statistics = statisticsCalculator.calculate(
                    type = exercise.type,
                    rows = exerciseRows.filter { it.variantSyncId == selectedVariant },
                ),
                variants = variants,
                variantError = variantError,
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
        if (current.isSaving) return
        val exerciseId = current.exerciseId ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || loads.isEmpty()) return
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
            val updated = existing.copy(
                name = if (current.editableName) trimmed else existing.name,
                type = type,
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

    fun saveVariant(syncId: String?, name: String) {
        val exerciseId = uiState.value.exercise?.id ?: return
        viewModelScope.launch {
            when (val result = if (syncId == null) variantRepository.create(exerciseId, name) else variantRepository.rename(syncId, name)) {
                is SaveExerciseVariantResult.Saved -> setVariantError(null)
                SaveExerciseVariantResult.BlankName -> setVariantError("Введите название варианта")
                SaveExerciseVariantResult.DuplicateName -> setVariantError("Вариант с таким названием уже есть")
                SaveExerciseVariantResult.NotFound -> setVariantError("Не удалось сохранить вариант. Попробуйте ещё раз.")
            }
        }
    }

    fun archiveVariant(syncId: String, archived: Boolean) {
        viewModelScope.launch {
            if (!variantRepository.setArchived(syncId, archived)) setVariantError("Не удалось сохранить вариант. Попробуйте ещё раз.")
        }
    }

    fun clearVariantError() = setVariantError(null)

    private fun setVariantError(error: String?) { _variantError.value = error }

    private fun showSaveFailure() {
        _editor.value = _editor.value?.copy(
            isSaving = false,
            saveError = "Не удалось сохранить упражнение. Попробуйте ещё раз.",
        )
    }
}
