package com.valerochka1337.valerochkagym.ui.gyms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.domain.GymConfiguration
import com.valerochka1337.valerochkagym.domain.GymRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Состояние pushed-раздела со всеми сохранёнными конфигурациями залов. */
data class GymsUiState(
    val gyms: List<GymConfiguration>? = null,
    val loadError: Boolean = false,
)

@HiltViewModel
class GymsViewModel @Inject constructor(
    repository: GymRepository,
) : ViewModel() {

    val uiState: StateFlow<GymsUiState> = repository.observeGyms()
        .map { gyms ->
            GymsUiState(gyms = gyms.sortedBy { it.name.lowercase() })
        }
        .catch { emit(GymsUiState(gyms = emptyList(), loadError = true)) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = GymsUiState(),
        )
}
