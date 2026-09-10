package com.valerochka1337.valerochkagym.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.data.profile.AiProfilePromptGate
import com.valerochka1337.valerochkagym.domain.BasicProfile
import com.valerochka1337.valerochkagym.domain.ExperienceLevel
import com.valerochka1337.valerochkagym.domain.ProfileEditTarget
import com.valerochka1337.valerochkagym.domain.ProfileRepository
import com.valerochka1337.valerochkagym.domain.ProfileSaveResult
import com.valerochka1337.valerochkagym.domain.ProfileSex
import com.valerochka1337.valerochkagym.domain.TrainingGoal
import com.valerochka1337.valerochkagym.service.WallClock
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class ProfileEditorUiState(
    val isLoading: Boolean = true,
    val target: ProfileEditTarget? = null,
    val trainingGoal: TrainingGoal? = null,
    val sex: ProfileSex? = null,
    val birthDate: String = "",
    val experienceLevel: ExperienceLevel? = null,
    val plannedSessionsPerWeek: String = "",
    val preferredSessionDurationMinutes: String = "",
    val equipmentIds: Set<String> = emptySet(),
    val manualConstraints: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
    val promptDisabled: Boolean = false,
)

@HiltViewModel
class ProfileViewModel
@Inject
constructor(
    private val profileRepository: ProfileRepository,
    private val promptGate: AiProfilePromptGate,
    private val savedStateHandle: SavedStateHandle,
    private val clock: WallClock = WallClock { System.currentTimeMillis() },
) : ViewModel() {
  private val _uiState = MutableStateFlow(ProfileEditorUiState())
  val uiState: StateFlow<ProfileEditorUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      val snapshot = profileRepository.openEditor()
      if (snapshot == null) {
        _uiState.value =
            ProfileEditorUiState(isLoading = false, error = "Профиль больше недоступен")
        return@launch
      }
      val promptDisabled = promptGate.isDisabledForCurrentScope()
      profileRepository.observe(snapshot.target).collectLatest { profile ->
        if (profile == null) {
          _uiState.value =
              ProfileEditorUiState(isLoading = false, error = "Профиль больше недоступен")
        } else if (!_uiState.value.isSaving) {
          _uiState.value = profile.toUi(snapshot.target, savedStateHandle, promptDisabled)
        }
      }
    }
  }

  fun setGoal(value: TrainingGoal?) = update { copy(trainingGoal = value, error = null) }

  fun setSex(value: ProfileSex?) = update { copy(sex = value, error = null) }

  fun setBirthDate(value: String) = update { copy(birthDate = value, error = null) }

  fun setExperience(value: ExperienceLevel?) = update {
    copy(experienceLevel = value, error = null)
  }

  fun setSessions(value: String) = update { copy(plannedSessionsPerWeek = value, error = null) }

  fun setDuration(value: String) = update {
    copy(preferredSessionDurationMinutes = value, error = null)
  }

  fun setConstraints(value: String) = update { copy(manualConstraints = value, error = null) }

  fun toggleEquipment(id: String) {
    if (!LocalEquipmentCatalog.isKnown(id)) return
    update {
      copy(
          equipmentIds =
              equipmentIds.toMutableSet().also { ids -> if (!ids.add(id)) ids.remove(id) }
      )
    }
  }

  fun setPromptDisabled(disabled: Boolean) {
    viewModelScope.launch {
      promptGate.setDisabledForCurrentScope(disabled)
      _uiState.value = _uiState.value.copy(promptDisabled = disabled)
    }
  }

  fun save() {
    val state = _uiState.value
    val target = state.target ?: return
    val profile = state.toProfileOrNull(clock.nowMillis())
    if (profile == null) {
      _uiState.value = state.copy(error = "Проверьте дату и числовые значения")
      return
    }
    _uiState.value = state.copy(isSaving = true, error = null)
    viewModelScope.launch {
      when (profileRepository.save(target, profile)) {
        ProfileSaveResult.Saved -> _uiState.value = _uiState.value.copy(isSaving = false)
        ProfileSaveResult.Invalid ->
            _uiState.value =
                _uiState.value.copy(isSaving = false, error = "Проверьте данные профиля")
        ProfileSaveResult.StaleTarget ->
            _uiState.value =
                ProfileEditorUiState(isLoading = false, error = "Профиль больше недоступен")
      }
    }
  }

  private fun update(block: ProfileEditorUiState.() -> ProfileEditorUiState) {
    val next = _uiState.value.block()
    next.saveDraft(savedStateHandle)
    _uiState.value = next
  }
}

private fun BasicProfile.toUi(
    target: ProfileEditTarget,
    handle: SavedStateHandle,
    promptDisabled: Boolean,
): ProfileEditorUiState {
  val draft = profileDraftFrom(handle, target)
  return (draft
          ?: ProfileEditorUiState(
              isLoading = false,
              target = target,
              trainingGoal = trainingGoal,
              sex = sex,
              birthDate = birthDate.orEmpty(),
              experienceLevel = experienceLevel,
              plannedSessionsPerWeek = plannedSessionsPerWeek?.toString().orEmpty(),
              preferredSessionDurationMinutes =
                  preferredSessionDurationMinutes?.toString().orEmpty(),
              equipmentIds = equipmentIds,
              manualConstraints = manualConstraints.orEmpty(),
          ))
      .copy(promptDisabled = promptDisabled)
}

private fun profileDraftFrom(
    handle: SavedStateHandle,
    target: ProfileEditTarget,
): ProfileEditorUiState? {
  if (
      handle.get<String>("profile_draft_scope") != target.scope ||
          handle.get<String>("profile_draft_owner") != target.ownerId ||
          handle.get<Long>("profile_draft_epoch") != target.sessionEpoch
  )
      return null
  return ProfileEditorUiState(
      isLoading = false,
      target = target,
      trainingGoal = handle.get<String>("profile_draft_goal")?.let { TrainingGoal.valueOf(it) },
      sex = handle.get<String>("profile_draft_sex")?.let { ProfileSex.valueOf(it) },
      birthDate = handle.get<String>("profile_draft_birth").orEmpty(),
      experienceLevel =
          handle.get<String>("profile_draft_experience")?.let { ExperienceLevel.valueOf(it) },
      plannedSessionsPerWeek = handle.get<String>("profile_draft_sessions").orEmpty(),
      preferredSessionDurationMinutes = handle.get<String>("profile_draft_duration").orEmpty(),
      equipmentIds = handle.get<ArrayList<String>>("profile_draft_equipment").orEmpty().toSet(),
      manualConstraints = handle.get<String>("profile_draft_constraints").orEmpty(),
  )
}

private fun ProfileEditorUiState.saveDraft(handle: SavedStateHandle) {
  val target = target ?: return
  handle["profile_draft_scope"] = target.scope
  handle["profile_draft_owner"] = target.ownerId
  handle["profile_draft_epoch"] = target.sessionEpoch
  handle["profile_draft_goal"] = trainingGoal?.name
  handle["profile_draft_sex"] = sex?.name
  handle["profile_draft_birth"] = birthDate
  handle["profile_draft_experience"] = experienceLevel?.name
  handle["profile_draft_sessions"] = plannedSessionsPerWeek
  handle["profile_draft_duration"] = preferredSessionDurationMinutes
  handle["profile_draft_equipment"] = ArrayList(equipmentIds.sorted())
  handle["profile_draft_constraints"] = manualConstraints
}

private fun ProfileEditorUiState.toProfileOrNull(nowMillis: Long): BasicProfile? {
  val date = birthDate.trim().ifBlank { null }
  val todayUtc = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate()
  if (
      date != null &&
          runCatching { LocalDate.parse(date) }
              .getOrNull()
              ?.let { it >= LocalDate.of(1900, 1, 1) && it <= todayUtc } != true
  )
      return null
  val sessions = plannedSessionsPerWeek.trim().ifBlank { null }?.toIntOrNull()
  if (plannedSessionsPerWeek.isNotBlank() && sessions !in 1..7) return null
  val duration = preferredSessionDurationMinutes.trim().ifBlank { null }?.toIntOrNull()
  if (preferredSessionDurationMinutes.isNotBlank() && duration !in 10..240) return null
  val constraints = manualConstraints.trim().ifBlank { null }
  if (constraints != null && constraints.codePointCount(0, constraints.length) > 2000) return null
  return BasicProfile(
      trainingGoal,
      sex,
      date,
      experienceLevel,
      sessions,
      duration,
      equipmentIds,
      constraints,
  )
}
