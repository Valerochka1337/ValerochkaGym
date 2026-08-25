package com.valerochka1337.valerochkagym.ui.settings

import android.app.Activity
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.backup.ClearDataUseCase
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.backup.ExportResult
import com.valerochka1337.valerochkagym.data.ai.OpenRouterFreeModel
import com.valerochka1337.valerochkagym.data.ai.OpenRouterFreeModelCatalog
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.ImportResult
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepository
import com.valerochka1337.valerochkagym.data.google.spreadsheetIdFrom
import com.valerochka1337.valerochkagym.data.settings.GymSettings
import com.valerochka1337.valerochkagym.data.settings.OpenRouterKeyStore
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.ui.theme.AccentColor
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import com.valerochka1337.valerochkagym.worker.NoOpRoutineUploadScheduler
import com.valerochka1337.valerochkagym.worker.RoutineUploadScheduler
import com.valerochka1337.valerochkagym.worker.UploadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Шаг изменения отдыха по умолчанию и его нижняя граница (в секундах). */
private const val MIN_REST_SECONDS = 15
private const val MIN_HEART_RATE_REST_THRESHOLD_BPM = 40
private const val MAX_HEART_RATE_REST_THRESHOLD_BPM = 220
private const val MIN_HEART_RATE_REST_HOLD_SECONDS = 5
private const val MAX_HEART_RATE_REST_HOLD_SECONDS = 60
internal const val OPEN_ROUTER_MODEL_CATALOG_TIMEOUT_MILLIS = 12_000L

/** Сообщение об ошибке настройки OAuth-доступа. */
private const val AUTH_ERROR_MESSAGE = "Не удалось настроить доступ — попробуйте ещё раз"

/** Совместимый с прямыми unit-тестами no-op; Hilt всегда внедряет реальный планировщик. */
private object NoOpMeasurementUploadScheduler : MeasurementUploadScheduler {
    override fun schedule(measurementId: String) = Unit
    override suspend fun retry(measurementId: String) = Unit
    override suspend fun scheduleAllPending(): Int = 0
}

/** Совместимая с прямыми unit-тестами заглушка для ключа OpenRouter. */
private object NoOpOpenRouterKeyStore : OpenRouterKeyStore {
    override val isConfigured = MutableStateFlow(false)

    override suspend fun save(value: String) = Unit

    override suspend fun read(): String? = null

    override suspend fun clear() = Unit
}

/** Не делает сетевой запрос в unit-тестах, но оставляет безопасный автоподбор доступным. */
private object NoOpOpenRouterFreeModelCatalog : OpenRouterFreeModelCatalog {
    override suspend fun getModels(): List<OpenRouterFreeModel> = listOf(OpenRouterFreeModel.Automatic)
}

private data class OpenRouterModelsUiState(
    val models: List<OpenRouterFreeModel> = listOf(OpenRouterFreeModel.Automatic),
    val isLoading: Boolean = false,
    val hasLoadError: Boolean = false,
)

private data class SettingsAuxiliaryState(
    val authBusy: Boolean,
    val spreadsheetError: Boolean,
    val authError: String?,
    val openRouterKeyConfigured: Boolean,
    val openRouterModels: OpenRouterModelsUiState,
)

/**
 * Состояние экрана настроек. [settings] == null — ещё не загружено (не мигаем пустой формой).
 * [authBusy] — идёт вход/выход через Google. [spreadsheetError] — последний ввод ссылки/ID не
 * распознан. [openRouterKeyConfigured] сообщает только факт наличия ключа — его значение никогда
 * не попадает в UI. [authError] — не удалось войти или настроить доступ (показываем и сбрасываем
 * при повторной попытке).
 */
data class SettingsUiState(
    val settings: GymSettings? = null,
    val authBusy: Boolean = false,
    val spreadsheetError: Boolean = false,
    val openRouterKeyConfigured: Boolean = false,
    val openRouterModels: List<OpenRouterFreeModel> = listOf(OpenRouterFreeModel.Automatic),
    val openRouterModelsLoading: Boolean = false,
    val openRouterModelsLoadError: Boolean = false,
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
    private val databaseExporter: DatabaseExporter,
    private val clearDataUseCase: ClearDataUseCase,
    private val measurementUploadScheduler: MeasurementUploadScheduler = NoOpMeasurementUploadScheduler,
    private val routineUploadScheduler: RoutineUploadScheduler = NoOpRoutineUploadScheduler,
    private val openRouterKeyStore: OpenRouterKeyStore = NoOpOpenRouterKeyStore,
    private val openRouterFreeModelCatalog: OpenRouterFreeModelCatalog = NoOpOpenRouterFreeModelCatalog,
) : ViewModel() {

    private val authBusy = MutableStateFlow(false)
    private val spreadsheetError = MutableStateFlow(false)
    private val authError = MutableStateFlow<String?>(null)
    private val openRouterModels = MutableStateFlow(OpenRouterModelsUiState())

    private val settingsAuxiliaryState: Flow<SettingsAuxiliaryState> = combine(
        authBusy,
        spreadsheetError,
        authError,
        openRouterKeyStore.isConfigured,
        openRouterModels,
    ) { busy, sheetError, currentAuthError, keyConfigured, models ->
        SettingsAuxiliaryState(
            authBusy = busy,
            spreadsheetError = sheetError,
            authError = currentAuthError,
            openRouterKeyConfigured = keyConfigured,
            openRouterModels = models,
        )
    }

    val uiState: StateFlow<SettingsUiState> =
        combine(
            settingsRepository.settings,
            settingsAuxiliaryState,
        ) { settings, auxiliary ->
            SettingsUiState(
                settings = settings,
                authBusy = auxiliary.authBusy,
                spreadsheetError = auxiliary.spreadsheetError,
                openRouterKeyConfigured = auxiliary.openRouterKeyConfigured,
                openRouterModels = auxiliary.openRouterModels.models,
                openRouterModelsLoading = auxiliary.openRouterModels.isLoading,
                openRouterModelsLoadError = auxiliary.openRouterModels.hasLoadError,
                authError = auxiliary.authError,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(),
        )

    init {
        refreshOpenRouterModels()
    }

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
            AuthorizeOutcome.Granted -> importHistoryIfConfigured()
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

    /** Сохраняет ключ OpenRouter в отдельном зашифрованном хранилище; в UI его не возвращаем. */
    fun setOpenRouterKey(raw: String) {
        val key = raw.trim()
        if (key.isEmpty()) {
            viewModelScope.launch { _messages.send("Введите ключ OpenRouter") }
            return
        }
        viewModelScope.launch {
            try {
                openRouterKeyStore.save(key)
                _messages.send("Ключ OpenRouter сохранён")
            } catch (_: Exception) {
                _messages.send("Не удалось сохранить ключ OpenRouter")
            }
        }
    }

    /** Удаляет ключ с устройства, не затрагивая остальные настройки. */
    fun clearOpenRouterKey() {
        viewModelScope.launch {
            try {
                openRouterKeyStore.clear()
                _messages.send("Ключ OpenRouter удалён")
            } catch (_: Exception) {
                _messages.send("Не удалось удалить ключ OpenRouter")
            }
        }
    }

    /** Обновляет live-каталог бесплатных моделей, не требуя ключа OpenRouter. */
    fun refreshOpenRouterModels() {
        openRouterModels.value = openRouterModels.value.copy(isLoading = true, hasLoadError = false)
        viewModelScope.launch {
            try {
                val models = withTimeoutOrNull(OPEN_ROUTER_MODEL_CATALOG_TIMEOUT_MILLIS) {
                    openRouterFreeModelCatalog.getModels()
                }?.ifEmpty { listOf(OpenRouterFreeModel.Automatic) } ?: run {
                    openRouterModels.value = openRouterModels.value.copy(
                        isLoading = false,
                        hasLoadError = true,
                    )
                    return@launch
                }
                openRouterModels.value = OpenRouterModelsUiState(models = models)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                openRouterModels.value = openRouterModels.value.copy(
                    isLoading = false,
                    hasLoadError = true,
                )
            }
        }
    }

    /** Сохраняет совместимую free-модель и её подтверждённый OpenRouter режим JSON. */
    fun setOpenRouterModel(model: OpenRouterFreeModel) {
        if (model.id.isBlank()) return
        viewModelScope.launch { settingsRepository.setOpenRouterModel(model) }
    }

    /** Разово восстанавливает все app-managed данные из таблицы и уведомляет о результате. */
    private suspend fun importHistory() {
        val message = when (val result = importRepository.importAll()) {
            is ImportResult.Success -> buildImportMessage(result)
            ImportResult.NothingToImport -> "Нечего импортировать"
            is ImportResult.Failure -> result.reason
        }
        _messages.send(message)
    }

    /** После входа восстанавливаем данные, только если ID таблицы уже вернулся из backup/DataStore. */
    private suspend fun importHistoryIfConfigured() {
        if (settingsRepository.settings.first().spreadsheetId != null) importHistory()
    }

    private fun buildImportMessage(result: ImportResult.Success): String = buildString {
        val restored = buildList {
            if (result.imported > 0) add("тренировок: ${result.imported}")
            if (result.importedMeasurements > 0) add("замеров: ${result.importedMeasurements}")
            if (result.importedRoutines > 0) add("программ: ${result.importedRoutines}")
        }
        if (restored.isEmpty()) {
            append("Новых данных нет")
        } else {
            append("Импортировано ")
            append(restored.joinToString())
        }
        if (result.skippedRows > 0) {
            append(' ')
            append("(пропущено строк: ${result.skippedRows})")
        }
    }

    /**
     * Ставит в очередь все невыгруженные тренировки и замеры, а также актуальные снимки всех
     * программ. У программ нет статуса: версия и UUID делают повторную выгрузку безопасной.
     */
    fun exportAll() {
        viewModelScope.launch {
            val count = uploadScheduler.scheduleAllPending() +
                measurementUploadScheduler.scheduleAllPending() +
                routineUploadScheduler.scheduleAll()
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

    /** Тактильный отклик интерфейса (GymHaptics); вибрация уведомления таймера — отдельно. */
    fun toggleHaptics(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHapticsEnabled(enabled) }
    }

    /** Автостарт таймера отдыха после отметки подхода. */
    fun toggleRestAutostart(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setRestAutostart(enabled) }
    }

    fun toggleHeartRateRest(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHeartRateRestEnabled(enabled) }
    }

    fun changeHeartRateRestThreshold(delta: Int) {
        val current = uiState.value.settings?.heartRateRestThresholdBpm ?: return
        val next = (current + delta).coerceIn(
            MIN_HEART_RATE_REST_THRESHOLD_BPM,
            MAX_HEART_RATE_REST_THRESHOLD_BPM,
        )
        if (next == current) return
        viewModelScope.launch { settingsRepository.setHeartRateRestThresholdBpm(next) }
    }

    fun changeHeartRateRestHoldSeconds(delta: Int) {
        val current = uiState.value.settings?.heartRateRestHoldSeconds ?: return
        val next = (current + delta).coerceIn(
            MIN_HEART_RATE_REST_HOLD_SECONDS,
            MAX_HEART_RATE_REST_HOLD_SECONDS,
        )
        if (next == current) return
        viewModelScope.launch { settingsRepository.setHeartRateRestHoldSeconds(next) }
    }

    /** Копирует базу в выбранный пользователем документ (SAF) и сообщает итог снэкбаром. */
    fun exportDatabase(target: Uri) {
        viewModelScope.launch {
            val message = when (val result = databaseExporter.export(target)) {
                ExportResult.Success -> "База данных экспортирована"
                is ExportResult.Failure -> result.reason
            }
            _messages.send(message)
        }
    }

    /** Стирает историю тренировок (каталог пересевается); настройки не трогаются. */
    fun clearAllData() {
        viewModelScope.launch {
            clearDataUseCase()
            _messages.send("Данные очищены")
        }
    }

    /**
     * Меняет акцент приложения. Иконку в лаунчере переключать отсюда не нужно: за ней следит
     * [com.valerochka1337.valerochkagym.data.appicon.AppIconManager], подписанный на настройку.
     */
    fun setAccent(accent: AccentColor) {
        viewModelScope.launch { settingsRepository.setAccent(accent) }
    }
}
