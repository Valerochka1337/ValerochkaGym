package com.valerochka1337.valerochkagym.ui.gyms

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.EquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.domain.GymConfiguration
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

data class GymDetailUiState(
    val loading: Boolean = true,
    val gym: GymConfiguration? = null,
    val equipment: List<EquipmentCatalog.Equipment> = emptyList(),
    val availableExercises: List<ExerciseEntity> = emptyList(),
    val loadError: Boolean = false,
)

/** Reactive, read-only detail state for one gym configuration. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GymDetailViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: GymRepository,
) : ViewModel() {

  private val gymId: String? = savedStateHandle[GymRoutes.GYM_ID_ARG]
  private val refreshes = MutableStateFlow(0)

  val uiState: StateFlow<GymDetailUiState> =
      refreshes
          .flatMapLatest {
            val detail: kotlinx.coroutines.flow.Flow<GymDetailUiState> =
                if (gymId == null) {
                  flowOf(GymDetailUiState(loading = false))
                } else {
                  repository
                      .observeGyms()
                      .map { gyms -> gyms.firstOrNull { it.id == gymId } }
                      .flatMapLatest { gym ->
                        if (gym == null) {
                          flowOf(GymDetailUiState(loading = false))
                        } else {
                          combine(
                              repository.observeAvailableExercises(setOf(gym.id)),
                              LocalEquipmentCatalog.state,
                          ) { exercises, catalog ->
                            val equipmentById = catalog.associateBy { it.equipment.id }
                            val equipment =
                                gym.equipmentIds
                                    .mapNotNull { id -> equipmentById[id]?.equipment }
                                    .sortedBy { it.name }
                            GymDetailUiState(
                                loading = false,
                                gym = gym,
                                equipment = equipment,
                                availableExercises = exercises,
                            )
                          }
                        }
                      }
                }
            detail
                .onStart { emit(GymDetailUiState()) }
                .catch { emit(GymDetailUiState(loading = false, loadError = true)) }
          }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(5_000),
              initialValue = GymDetailUiState(),
          )

  fun retry() {
    refreshes.value++
  }
}
