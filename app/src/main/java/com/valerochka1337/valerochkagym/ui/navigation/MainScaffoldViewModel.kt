package com.valerochka1337.valerochkagym.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Бэкенд оболочки приложения: признак наличия активной тренировки для баннера «вернуться
 * к тренировке» на вкладках.
 */
@HiltViewModel
class MainScaffoldViewModel @Inject constructor(
    repository: ActiveWorkoutRepository,
) : ViewModel() {

    val hasActiveWorkout: StateFlow<Boolean> = repository.observeActive()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
