package com.valerochka1337.valerochkagym.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.SaveGymResult
import com.valerochka1337.valerochkagym.worker.RoutineUploadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface ConfigurationCloneTarget {
  val name: String

  data class Routine(val id: Long, override val name: String) : ConfigurationCloneTarget

  data class Gym(val syncId: String, override val name: String) : ConfigurationCloneTarget
}

data class ConfigurationCloneUiState(
    val target: ConfigurationCloneTarget? = null,
    val name: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
)

/**
 * Creates a named configuration copy through the repository without exposing storage details to UI.
 */
@HiltViewModel
class ConfigurationCloneViewModel
@Inject
constructor(
    private val repository: GymRepository,
    private val routineUploadScheduler: RoutineUploadScheduler,
) : ViewModel() {
  private val mutableUiState = MutableStateFlow(ConfigurationCloneUiState())
  val uiState = mutableUiState.asStateFlow()

  private val mutableMessages = Channel<String>(Channel.BUFFERED)
  val messages = mutableMessages.receiveAsFlow()

  fun open(target: ConfigurationCloneTarget) {
    if (mutableUiState.value.isSaving) return
    mutableUiState.value =
        ConfigurationCloneUiState(target = target, name = "${target.name} (копия)")
  }

  fun setName(name: String) {
    if (mutableUiState.value.isSaving) return
    mutableUiState.value = mutableUiState.value.copy(name = name, error = null)
  }

  fun dismiss() {
    if (mutableUiState.value.isSaving) return
    mutableUiState.value = ConfigurationCloneUiState()
  }

  fun save() {
    val state = mutableUiState.value
    val target = state.target ?: return
    if (state.isSaving) return
    val name = state.name.trim()
    if (name.isEmpty()) {
      mutableUiState.value = state.copy(error = "Введите название")
      return
    }
    mutableUiState.value = state.copy(name = name, isSaving = true, error = null)
    viewModelScope.launch {
      try {
        val result =
            when (target) {
              is ConfigurationCloneTarget.Routine -> {
                val copy = repository.duplicateRoutine(target.id, name)
                if (copy == null) {
                  CloneResult.Failure(failure(target))
                } else {
                  // The Room transaction has committed. A scheduler failure must not make this
                  // dialog retryable, because retrying would create a second copy.
                  try {
                    routineUploadScheduler.schedule(copy.syncId)
                  } catch (error: CancellationException) {
                    throw error
                  } catch (_: Exception) {}
                  CloneResult.Success("Копия программы создана")
                }
              }
              is ConfigurationCloneTarget.Gym ->
                  when (repository.cloneGym(target.syncId, name)) {
                    is SaveGymResult.Saved -> CloneResult.Success("Копия зала создана")
                    SaveGymResult.NameAlreadyExists ->
                        CloneResult.Failure("Зал с таким названием уже существует")
                    else -> CloneResult.Failure(failure(target))
                  }
            }
        when (result) {
          is CloneResult.Success -> {
            mutableUiState.value = ConfigurationCloneUiState()
            mutableMessages.send(result.message)
          }
          is CloneResult.Failure -> {
            mutableUiState.value =
                mutableUiState.value.copy(isSaving = false, error = result.message)
          }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        mutableUiState.value = mutableUiState.value.copy(isSaving = false, error = failure(target))
      }
    }
  }

  private fun failure(target: ConfigurationCloneTarget): String =
      when (target) {
        is ConfigurationCloneTarget.Routine -> "Не удалось создать копию программы"
        is ConfigurationCloneTarget.Gym -> "Не удалось создать копию зала"
      }

  private sealed interface CloneResult {
    data class Success(val message: String) : CloneResult

    data class Failure(val message: String) : CloneResult
  }
}
