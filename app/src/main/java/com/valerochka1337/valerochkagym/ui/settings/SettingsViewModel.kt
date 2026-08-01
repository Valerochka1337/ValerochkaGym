package com.valerochka1337.valerochkagym.ui.settings

import android.app.Activity
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.spreadsheetIdFrom
import com.valerochka1337.valerochkagym.data.settings.GymSettings
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Шаг изменения отдыха по умолчанию и его нижняя граница (в секундах). */
private const val REST_STEP_SECONDS = 15
private const val MIN_REST_SECONDS = 15

/**
 * Состояние экрана настроек. [settings] == null — ещё не загружено (не мигаем пустой формой).
 * [authBusy] — идёт вход/выход через Google. [spreadsheetError] — последний ввод ссылки/ID не
 * распознан.
 */
data class SettingsUiState(
    val settings: GymSettings? = null,
    val authBusy: Boolean = false,
    val spreadsheetError: Boolean = false,
)

/**
 * Бэкенд экрана настроек. Хранение делегируется [SettingsRepository], вход и OAuth — [GoogleAuth].
 * Запрос согласия (consent) не может быть запущен из ViewModel, поэтому [IntentSender] уходит на
 * экран через [consentRequests]; экран запускает его и вызывает [consentResolved].
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val googleAuth: GoogleAuth,
) : ViewModel() {

    private val authBusy = MutableStateFlow(false)
    private val spreadsheetError = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> =
        combine(settingsRepository.settings, authBusy, spreadsheetError) { settings, busy, error ->
            SettingsUiState(settings = settings, authBusy = busy, spreadsheetError = error)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(),
        )

    private val _consentRequests = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)

    /** Запросы согласия на OAuth-доступ, которые экран должен запустить через launcher. */
    val consentRequests: SharedFlow<IntentSender> = _consentRequests

    fun signIn(activity: Activity) {
        viewModelScope.launch {
            authBusy.value = true
            val result = googleAuth.signIn(activity)
            if (result.isSuccess) {
                requestAuthorize(activity)
            }
            authBusy.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authBusy.value = true
            googleAuth.signOut()
            authBusy.value = false
        }
    }

    /** Повторный запрос доступа после того, как пользователь прошёл экран согласия. */
    fun consentResolved(activity: Activity) {
        viewModelScope.launch { requestAuthorize(activity) }
    }

    private suspend fun requestAuthorize(activity: Activity) {
        val outcome = googleAuth.authorize(activity)
        if (outcome is AuthorizeOutcome.NeedsConsent) {
            _consentRequests.emit(outcome.pendingIntent.intentSender)
        }
    }

    /** Сохраняет ID таблицы, распарсив ссылку или голый ID; при неудаче выставляет ошибку. */
    fun setSpreadsheetInput(raw: String) {
        val id = spreadsheetIdFrom(raw)
        if (id == null) {
            spreadsheetError.value = true
            return
        }
        spreadsheetError.value = false
        viewModelScope.launch { settingsRepository.setSpreadsheetId(id) }
    }

    /** Меняет отдых по умолчанию на [delta] секунд (обычно ±[REST_STEP_SECONDS]), не ниже минимума. */
    fun changeDefaultRest(delta: Int) {
        val current = uiState.value.settings?.defaultRestSeconds ?: return
        val next = (current + delta).coerceAtLeast(MIN_REST_SECONDS)
        if (next == current) return
        viewModelScope.launch { settingsRepository.setDefaultRestSeconds(next) }
    }

    fun toggleSound(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSoundEnabled(enabled) }
    }

    fun toggleVibration(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setVibrationEnabled(enabled) }
    }
}
