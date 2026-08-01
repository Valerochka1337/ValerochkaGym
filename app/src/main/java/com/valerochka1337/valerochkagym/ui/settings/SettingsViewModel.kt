package com.valerochka1337.valerochkagym.ui.settings

import android.app.Activity
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.ImportResult
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepository
import com.valerochka1337.valerochkagym.data.google.spreadsheetIdFrom
import com.valerochka1337.valerochkagym.data.settings.GymSettings
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.worker.UploadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Шаг изменения отдыха по умолчанию и его нижняя граница (в секундах). */
private const val REST_STEP_SECONDS = 15
private const val MIN_REST_SECONDS = 15

/** Сообщение об ошибке настройки OAuth-доступа. */
private const val AUTH_ERROR_MESSAGE = "Не удалось настроить доступ — попробуйте ещё раз"

/**
 * Состояние экрана настроек. [settings] == null — ещё не загружено (не мигаем пустой формой).
 * [authBusy] — идёт вход/выход через Google. [spreadsheetError] — последний ввод ссылки/ID не
 * распознан. [authError] — не удалось войти или настроить доступ (показываем и сбрасываем при
 * повторной попытке).
 */
data class SettingsUiState(
    val settings: GymSettings? = null,
    val authBusy: Boolean = false,
    val spreadsheetError: Boolean = false,
    val authError: String? = null,
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
    private val uploadScheduler: UploadScheduler,
    private val importRepository: WorkoutImportRepository,
) : ViewModel() {

    private val authBusy = MutableStateFlow(false)
    private val spreadsheetError = MutableStateFlow(false)
    private val authError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> =
        combine(
            settingsRepository.settings,
            authBusy,
            spreadsheetError,
            authError,
        ) { settings, busy, sheetError, authError ->
            SettingsUiState(
                settings = settings,
                authBusy = busy,
                spreadsheetError = sheetError,
                authError = authError,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(),
        )

    private val _consentRequests = Channel<IntentSender>(Channel.CONFLATED)

    /** Запросы согласия на OAuth-доступ, которые экран должен запустить через launcher. */
    val consentRequests: Flow<IntentSender> = _consentRequests.receiveAsFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** Короткие уведомления для snackbar (например, результат «Выгрузить всё»). */
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun signIn(activity: Activity) {
        viewModelScope.launch {
            authBusy.value = true
            authError.value = null
            try {
                val result = googleAuth.signIn(activity)
                if (result.isSuccess) {
                    requestAuthorize(activity)
                } else {
                    authError.value = AUTH_ERROR_MESSAGE
                }
            } finally {
                authBusy.value = false
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authBusy.value = true
            try {
                googleAuth.signOut()
            } finally {
                authBusy.value = false
            }
        }
    }

    /** Повторный запрос доступа после того, как пользователь прошёл экран согласия. */
    fun consentResolved(activity: Activity) {
        viewModelScope.launch {
            authError.value = null
            requestAuthorize(activity)
        }
    }

    private suspend fun requestAuthorize(activity: Activity) {
        when (val outcome = googleAuth.authorize(activity)) {
            is AuthorizeOutcome.NeedsConsent -> _consentRequests.send(outcome.pendingIntent.intentSender)
            is AuthorizeOutcome.Failed -> authError.value = AUTH_ERROR_MESSAGE
            AuthorizeOutcome.Granted -> Unit
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
        viewModelScope.launch {
            settingsRepository.setSpreadsheetId(id)
            importHistory()
        }
    }

    /** Разово тянет историю из только что сохранённой таблицы и уведомляет о результате. */
    private suspend fun importHistory() {
        val message = when (val result = importRepository.importAll()) {
            is ImportResult.Success -> "Импортировано тренировок: ${result.imported}"
            ImportResult.NothingToImport -> "Нечего импортировать"
            is ImportResult.Failure -> result.reason
        }
        _messages.send(message)
    }

    /**
     * Ставит в очередь выгрузку всех завершённых, ещё не выгруженных тренировок (PENDING/FAILED):
     * каждой сбрасывает статус в PENDING и запускает воркер. Показывает, сколько поставлено в очередь.
     */
    fun exportAll() {
        viewModelScope.launch {
            val count = uploadScheduler.scheduleAllPending()
            _messages.send("Поставлено в очередь: $count")
        }
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
