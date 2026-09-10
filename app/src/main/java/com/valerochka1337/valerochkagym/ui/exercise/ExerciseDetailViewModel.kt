package com.valerochka1337.valerochkagym.ui.exercise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.EquipmentRequirementState
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.data.db.entity.withNextUpdatedAt
import com.valerochka1337.valerochkagym.di.ComputeDispatcher
import com.valerochka1337.valerochkagym.domain.ExerciseEquipmentRequirements
import com.valerochka1337.valerochkagym.domain.ExercisePersonalHint
import com.valerochka1337.valerochkagym.domain.ExercisePersonalHintRepository
import com.valerochka1337.valerochkagym.domain.ExerciseStatistics
import com.valerochka1337.valerochkagym.domain.ExerciseStatisticsCalculator
import com.valerochka1337.valerochkagym.domain.GymConfigurationConflict
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.HintEditTarget
import com.valerochka1337.valerochkagym.domain.NewExerciseConfiguration
import com.valerochka1337.valerochkagym.domain.NoOpGymRepository
import com.valerochka1337.valerochkagym.domain.NoteSaveResult
import com.valerochka1337.valerochkagym.domain.SaveExerciseConfigurationResult
import com.valerochka1337.valerochkagym.ui.library.ExerciseEditorState
import com.valerochka1337.valerochkagym.ui.library.formatExerciseSaveConflict
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ExerciseDetailUiState(
    val loading: Boolean = true,
    val exercise: ExerciseEntity? = null,
    val loads: List<MuscleLoad> = emptyList(),
    val requirements: ExerciseEquipmentRequirements = ExerciseEquipmentRequirements.UnknownLegacy,
    val statistics: ExerciseStatistics? = null,
    val personalHint: ExercisePersonalHint? = null,
)

data class ExercisePersonalHintEditDraft(
    val target: HintEditTarget,
    val token: Long,
    val text: String,
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ExerciseDetailViewModel
@Inject
constructor(
    private val savedStateHandle: SavedStateHandle,
    private val exerciseDao: ExerciseDao,
    private val exerciseMuscleDao: ExerciseMuscleDao,
    workoutDao: WorkoutDao,
    statisticsCalculator: ExerciseStatisticsCalculator,
    @ComputeDispatcher computeDispatcher: CoroutineDispatcher,
    private val gymRepository: GymRepository = NoOpGymRepository,
    private val personalHintRepository: ExercisePersonalHintRepository,
) : ViewModel() {

  private val exerciseId: Long? = savedStateHandle[GymRoutes.EXERCISE_ID_ARG]
  private val _editor = MutableStateFlow<ExerciseEditorState?>(null)
  val editor: StateFlow<ExerciseEditorState?> = _editor.asStateFlow()
  private val _personalHintEditor = MutableStateFlow(savedPersonalHintEditor())
  val personalHintEditor: StateFlow<ExercisePersonalHintEditDraft?> =
      _personalHintEditor.asStateFlow()
  private var nextPersonalHintToken =
      savedStateHandle.get<Long>(PERSONAL_HINT_EDIT_EPOCH) ?: _personalHintEditor.value?.token ?: 0L

  val uiState: StateFlow<ExerciseDetailUiState> =
      combine(
              exerciseDao.getAll(),
              exerciseMuscleDao.observeAll(),
              workoutDao.observeCompletedSets(),
              exerciseDao.observeAllRequirements(),
              personalHintRepository.observe(exerciseId ?: INVALID_EXERCISE_ID),
          ) { exercises, muscleRows, completedSets, requirementRows, hint ->
            val exercise = exercises.firstOrNull { it.id == exerciseId }
            if (exercise == null) {
              ExerciseDetailUiState(loading = false)
            } else {
              val loads =
                  muscleRows
                      .asSequence()
                      .filter { it.exerciseId == exercise.id }
                      .sortedByDescending { it.contribution }
                      .map { MuscleLoad(it.muscle, it.contribution) }
                      .toList()
              val requirements =
                  if (exercise.equipmentRequirementState == EquipmentRequirementState.UNKNOWN) {
                    ExerciseEquipmentRequirements.UnknownLegacy
                  } else {
                    val ids =
                        requirementRows
                            .asSequence()
                            .filter { it.exerciseId == exercise.id }
                            .mapTo(linkedSetOf()) { it.equipmentId }
                    if (ids.isEmpty()) ExerciseEquipmentRequirements.ExplicitNone
                    else ExerciseEquipmentRequirements.Required(ids)
                  }
              ExerciseDetailUiState(
                  loading = false,
                  exercise = exercise,
                  loads = loads,
                  requirements = requirements,
                  statistics =
                      statisticsCalculator.calculate(
                          type = exercise.type,
                          rows = completedSets.filter { it.exerciseId == exercise.id },
                      ),
                  personalHint = hint,
              )
            }
          }
          .flowOn(computeDispatcher)
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(5_000),
              initialValue = ExerciseDetailUiState(),
          )

  fun openPersonalHintEditor() {
    val exercise = uiState.value.exercise ?: return
    val token = ++nextPersonalHintToken
    viewModelScope.launch {
      val target =
          personalHintRepository.editTarget(exercise.id)
              ?: run {
                clearPersonalHintEditor()
                return@launch
              }
      if (token != nextPersonalHintToken) return@launch
      if (uiState.value.exercise?.id != exercise.id) return@launch
      savedStateHandle[PERSONAL_HINT_EDIT_EPOCH] = token
      updatePersonalHintEditor(
          ExercisePersonalHintEditDraft(
              target = target,
              token = token,
              text = uiState.value.personalHint?.text.orEmpty(),
          ),
      )
    }
  }

  fun updatePersonalHint(text: String) = updatePersonalHintEditor {
    it.copy(text = text, error = null)
  }

  fun cancelPersonalHintEditor() = clearPersonalHintEditor()

  fun savePersonalHint() {
    val draft = _personalHintEditor.value ?: return
    if (draft.isSubmitting) return
    val text = draft.text.trim()
    if (text.codePointCount(0, text.length) > MAX_HINT_CODE_POINTS) {
      updatePersonalHintEditor { it.copy(error = HINT_TOO_LONG_MESSAGE) }
      return
    }
    updatePersonalHintEditor { it.copy(text = text, isSubmitting = true, error = null) }
    viewModelScope.launch {
      when (personalHintRepository.save(draft.target, text)) {
        NoteSaveResult.Saved -> {
          if (_personalHintEditor.value?.token == draft.token) clearPersonalHintEditor()
        }
        NoteSaveResult.MissingOrInactive -> {
          if (_personalHintEditor.value?.token == draft.token) clearPersonalHintEditor()
        }
        NoteSaveResult.TooLong -> {
          if (_personalHintEditor.value?.token == draft.token) {
            updatePersonalHintEditor {
              it.copy(isSubmitting = false, error = HINT_TOO_LONG_MESSAGE)
            }
          }
        }
      }
    }
  }

  fun unpinPersonalHint() {
    val exercise = uiState.value.exercise ?: return
    val token = ++nextPersonalHintToken
    clearPersonalHintEditor()
    viewModelScope.launch {
      val target =
          personalHintRepository.editTarget(exercise.id)
              ?: run {
                clearPersonalHintEditor()
                return@launch
              }
      if (token != nextPersonalHintToken) return@launch
      if (uiState.value.exercise?.id != exercise.id) return@launch
      val result = personalHintRepository.unpin(target)
      if (result == NoteSaveResult.MissingOrInactive) clearPersonalHintEditor()
    }
  }

  private fun updatePersonalHintEditor(
      transform: (ExercisePersonalHintEditDraft) -> ExercisePersonalHintEditDraft,
  ) {
    val current = _personalHintEditor.value ?: return
    updatePersonalHintEditor(transform(current))
  }

  private fun updatePersonalHintEditor(draft: ExercisePersonalHintEditDraft) {
    _personalHintEditor.value = draft
    savedStateHandle[PERSONAL_HINT_EDIT_EXERCISE_ID] = draft.target.exerciseId
    savedStateHandle[PERSONAL_HINT_EDIT_SYNC_ID] = draft.target.exerciseSyncId
    savedStateHandle[PERSONAL_HINT_EDIT_OWNER_SCOPE] = draft.target.ownerScope
    savedStateHandle[PERSONAL_HINT_EDIT_SESSION_EPOCH] = draft.target.sessionEpoch
    savedStateHandle[PERSONAL_HINT_EDIT_TOKEN] = draft.token
    savedStateHandle[PERSONAL_HINT_EDIT_TEXT] = draft.text
  }

  private fun clearPersonalHintEditor() {
    _personalHintEditor.value = null
    listOf(
            PERSONAL_HINT_EDIT_EXERCISE_ID,
            PERSONAL_HINT_EDIT_SYNC_ID,
            PERSONAL_HINT_EDIT_OWNER_SCOPE,
            PERSONAL_HINT_EDIT_SESSION_EPOCH,
            PERSONAL_HINT_EDIT_TOKEN,
            PERSONAL_HINT_EDIT_TEXT,
        )
        .forEach { key -> savedStateHandle.remove<Any?>(key) }
  }

  private fun savedPersonalHintEditor(): ExercisePersonalHintEditDraft? {
    val id = savedStateHandle.get<Long>(PERSONAL_HINT_EDIT_EXERCISE_ID) ?: return null
    val syncId = savedStateHandle.get<String>(PERSONAL_HINT_EDIT_SYNC_ID) ?: return null
    val epoch = savedStateHandle.get<Long>(PERSONAL_HINT_EDIT_SESSION_EPOCH) ?: return null
    val token = savedStateHandle.get<Long>(PERSONAL_HINT_EDIT_TOKEN) ?: return null
    return ExercisePersonalHintEditDraft(
        target =
            HintEditTarget(
                exerciseId = id,
                exerciseSyncId = syncId,
                ownerScope = savedStateHandle.get(PERSONAL_HINT_EDIT_OWNER_SCOPE),
                sessionEpoch = epoch,
            ),
        token = token,
        text = savedStateHandle[PERSONAL_HINT_EDIT_TEXT] ?: "",
    )
  }

  fun openEditor() {
    val state = uiState.value
    val exercise = state.exercise ?: return
    if ((exercise.origin == "STANDARD")) return
    viewModelScope.launch {
      _editor.value =
          ExerciseEditorState(
              exerciseId = exercise.id,
              name = exercise.name,
              type = exercise.type,
              loads = state.loads.associate { it.muscle to it.contribution },
              editableName = !(exercise.origin == "STANDARD"),
              needsMuscleMapReview = exercise.needsMuscleMapReview,
              requirements =
                  if (gymRepository === NoOpGymRepository)
                      ExerciseEquipmentRequirements.ExplicitNone
                  else gymRepository.requirementsFor(exercise),
          )
    }
  }

  fun closeEditor() {
    _editor.value = null
  }

  fun saveEditor(
      name: String,
      type: com.valerochka1337.valerochkagym.data.db.entity.ExerciseType,
      loads: List<MuscleLoad>,
      requirements: ExerciseEquipmentRequirements,
  ) {
    val current = _editor.value ?: return
    if (current.isSaving) return
    val exerciseId = current.exerciseId ?: return
    val trimmed = name.trim()
    if (
        trimmed.isEmpty() ||
            loads.none { it.contribution == 100 } ||
            loads.any { it.contribution !in setOf(100, 50, 0) }
    ) {
      _editor.value = current.copy(saveError = "Выберите хотя бы одну основную мышцу.")
      return
    }
    if (requirements is ExerciseEquipmentRequirements.UnknownLegacy) {
      _editor.value = current.copy(saveError = "Выберите оборудование или «Без оборудования».")
      return
    }
    _editor.value =
        current.copy(
            name = trimmed,
            type = type,
            loads = loads.associate { it.muscle to it.contribution },
            requirements = requirements,
            isSaving = true,
            saveError = null,
        )
    viewModelScope.launch {
      val existing = exerciseDao.getById(exerciseId)
      if (existing == null) {
        showSaveFailure()
        return@launch
      }
      if ((existing.origin == "STANDARD")) {
        _editor.value = null
        return@launch
      }
      val updated =
          existing
              .copy(
                  name = trimmed,
                  type = type,
                  needsMuscleMapReview = false,
              )
              .withNextUpdatedAt()
      val muscleRows =
          loads.map { load -> ExerciseMuscleEntity(exerciseId, load.muscle, load.contribution) }
      val saved =
          try {
            if (gymRepository === NoOpGymRepository) {
              // Fallback для прямых unit-тестов; production идёт через одну repo-транзакцию.
              exerciseDao.update(updated)
              exerciseMuscleDao.replaceForExercise(exerciseId, muscleRows)
              updated
            } else {
              when (
                  val result =
                      gymRepository.saveExerciseConfiguration(
                          configuration =
                              NewExerciseConfiguration(updated, muscleRows, requirements),
                          gymIds = emptySet(),
                      )
              ) {
                is SaveExerciseConfigurationResult.Saved -> result.exercise
                is SaveExerciseConfigurationResult.Conflict -> {
                  showSaveConflict(result.details)
                  return@launch
                }
                SaveExerciseConfigurationResult.Failure -> null
              }
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

  /** Source-compatible entry point for callers that have no equipment editor yet. */
  fun saveEditor(
      name: String,
      type: com.valerochka1337.valerochkagym.data.db.entity.ExerciseType,
      loads: List<MuscleLoad>,
  ) {
    saveEditor(
        name,
        type,
        loads,
        _editor.value?.requirements ?: ExerciseEquipmentRequirements.ExplicitNone,
    )
  }

  private fun showSaveFailure() {
    _editor.value =
        _editor.value?.copy(
            isSaving = false,
            saveError = "Не удалось сохранить упражнение. Попробуйте ещё раз.",
        )
  }

  private fun showSaveConflict(conflict: GymConfigurationConflict) {
    _editor.value =
        _editor.value?.copy(isSaving = false, saveError = formatExerciseSaveConflict(conflict))
  }
}

private const val INVALID_EXERCISE_ID = Long.MIN_VALUE
private const val PERSONAL_HINT_EDIT_EXERCISE_ID = "personal_hint_edit_exercise_id"
private const val PERSONAL_HINT_EDIT_SYNC_ID = "personal_hint_edit_sync_id"
private const val PERSONAL_HINT_EDIT_OWNER_SCOPE = "personal_hint_edit_owner_scope"
private const val PERSONAL_HINT_EDIT_SESSION_EPOCH = "personal_hint_edit_session_epoch"
private const val PERSONAL_HINT_EDIT_TOKEN = "personal_hint_edit_token"
private const val PERSONAL_HINT_EDIT_TEXT = "personal_hint_edit_text"
private const val PERSONAL_HINT_EDIT_EPOCH = "personal_hint_edit_epoch"
private const val MAX_HINT_CODE_POINTS = 2_000
private const val HINT_TOO_LONG_MESSAGE = "Подсказка не длиннее 2000 символов"
