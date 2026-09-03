package com.valerochka1337.valerochkagym.ui.health

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.HealthDao
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncConflictEntity
import com.valerochka1337.valerochkagym.data.health.ConflictChoice
import com.valerochka1337.valerochkagym.data.health.ConflictResolutionResult
import com.valerochka1337.valerochkagym.data.health.SyncConflictResolver
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HealthConflictUiState(
    val conflict: HealthSyncConflictEntity? = null,
    val local: HealthConflictPayloadView? = null,
    val remote: HealthConflictPayloadView? = null,
    val resolving: Boolean = false,
    val error: String? = null,
) {
    val canResolve: Boolean get() = conflict != null && local != null && remote != null && !resolving
}

sealed interface HealthConflictEvent {
    data object Resolved : HealthConflictEvent
}

/** Resolves exactly one durable marker; there is intentionally no dismiss/delete operation. */
@HiltViewModel
class HealthConflictViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    healthDao: HealthDao,
    private val resolver: SyncConflictResolver,
) : ViewModel() {
    private val category = savedStateHandle.get<String>(GymRoutes.HEALTH_CONFLICT_CATEGORY_ARG).orEmpty()
    private val syncId = savedStateHandle.get<String>(GymRoutes.HEALTH_CONFLICT_SYNC_ID_ARG).orEmpty()
    private val version = savedStateHandle.get<Long>(GymRoutes.HEALTH_CONFLICT_VERSION_ARG) ?: -1L
    private val operation = MutableStateFlow(Operation())
    private val eventChannel = Channel<HealthConflictEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private val conflict = healthDao.observeConflict(category, syncId, version).map { entry ->
        val local = entry?.let { HealthConflictFormatter.format(it.category, it.localPayload) }
        val remote = entry?.let { HealthConflictFormatter.format(it.category, it.remotePayload) }
        HealthConflictUiState(
            conflict = entry,
            local = local,
            remote = remote,
            error = if (entry != null && (local == null || remote == null)) {
                "Версия конфликта повреждена и не может быть применена. Данные не изменены."
            } else null,
        )
    }
    val uiState: StateFlow<HealthConflictUiState> = combine(conflict, operation) { state, operation ->
        state.copy(resolving = operation.resolving, error = operation.error ?: state.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthConflictUiState())

    fun choose(choice: ConflictChoice) {
        val current = uiState.value
        if (operation.value.resolving) return
        if (!current.canResolve) {
            if (current.conflict != null) operation.value = Operation(error = "Выберите только корректно прочитанные версии.")
            return
        }
        // Set synchronously before launch: two taps delivered in the same frame still create one
        // successor/outbox entry.
        operation.value = Operation(resolving = true)
        viewModelScope.launch {
            try {
                when (resolver.resolve(category, syncId, version, choice)) {
                    is ConflictResolutionResult.Resolved -> {
                        operation.value = Operation()
                        eventChannel.send(HealthConflictEvent.Resolved)
                    }
                    ConflictResolutionResult.InvalidPayload -> operation.value = Operation(error = "Не удалось прочитать выбранную версию. Конфликт сохранён.")
                    ConflictResolutionResult.Missing -> operation.value = Operation(error = "Конфликт уже изменён в другом действии. Обновите экран.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                operation.value = Operation(error = "Не удалось разрешить конфликт. Данные не изменены.")
            }
        }
    }

    private data class Operation(val resolving: Boolean = false, val error: String? = null)
}
