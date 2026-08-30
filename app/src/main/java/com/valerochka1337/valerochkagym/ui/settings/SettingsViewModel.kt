package com.valerochka1337.valerochkagym.ui.settings

import android.app.Activity
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.backup.ClearDataUseCase
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.backup.ExportResult
import com.valerochka1337.valerochkagym.data.ai.AiModel
import com.valerochka1337.valerochkagym.data.ai.AiModelCatalog
import com.valerochka1337.valerochkagym.data.ai.normalizeAiBaseUrl
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.ImportResult
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepository
import com.valerochka1337.valerochkagym.data.google.spreadsheetIdFrom
import com.valerochka1337.valerochkagym.data.settings.GymSettings
import com.valerochka1337.valerochkagym.data.settings.AiApiKeyStore
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
import kotlin.time.Duration.Companion.milliseconds

/** Шаг изменения отдыха по умолчанию и его нижняя граница (в секундах). */
private const val MIN_REST_SECONDS = 15
private const val MIN_HEART_RATE_REST_THRESHOLD_BPM = 40
private const val MAX_HEART_RATE_REST_THRESHOLD_BPM = 220
private const val MIN_HEART_RATE_REST_HOLD_SECONDS = 5
private const val MAX_HEART_RATE_REST_HOLD_SECONDS = 60
internal const val AI_MODEL_CATALOG_TIMEOUT_MILLIS = 12_000L

/** Сообщение об ошибке настройки OAuth-доступа. */
private const val AUTH_ERROR_MESSAGE = "Не удалось настроить доступ — попробуйте ещё раз"

/** Совместимый с прямыми unit-тестами no-op; Hilt всегда внедряет реальный планировщик. */
private object NoOpMeasurementUploadScheduler : MeasurementUploadScheduler {
    override fun schedule(measurementId: String) = Unit
    override suspend fun retry(measurementId: String) = Unit
    override suspend fun scheduleAllPending(): Int = 0
}

/** Совместимая с прямыми unit-тестами заглушка для API key. */
private object NoOpAiApiKeyStore : AiApiKeyStore {
    override val isConfigured = MutableStateFlow(false)

    override suspend fun save(value: String) = Unit

    override suspend fun read(): String? = null

    override suspend fun preview(): String? = null

    override suspend fun clear() = Unit
}

/** Не делает сетевой запрос в прямых unit-тестах без Hilt. */
private object NoOpAiModelCatalog : AiModelCatalog {
    override suspend fun getModels(): List<AiModel> = emptyList()
}

private data class AiModelsUiState(
    val models: List<AiModel> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoadError: Boolean = false,
)

private data class AiApiKeyUiState(
    val isConfigured: Boolean,
    val preview: String?,
)

private data class SettingsInputErrors(
    val spreadsheet: Boolean,
    val aiBaseUrl: Boolean,
)

private data class SettingsAuxiliaryState(
    val authBusy: Boolean,
    val inputErrors: SettingsInputErrors,
    val authError: String?,
    val aiApiKeyConfigured: Boolean,
    val aiApiKeyPreview: String?,
    val aiModels: AiModelsUiState,
)

/**
 * Состояние экрана настроек. [settings] == null — ещё не загружено (не мигаем пустой формой).
 * [authBusy] — идёт вход/выход через Google. [spreadsheetError] — последний ввод ссылки/ID не
 * распознан. [aiApiKeyConfigured] сообщает только факт наличия ключа, а [aiApiKeyPreview] —
 * безопасную маску с последними четырьмя символами; полный ключ в UI не попадает. [authError] —
 * не удалось войти или настроить доступ (показываем и сбрасываем при повторной попытке).
 */
data class SettingsUiState(
    val settings: GymSettings? = null,
    val authBusy: Boolean = false,
    val spreadsheetError: Boolean = false,
    val aiBaseUrlError: Boolean = false,
    val aiApiKeyConfigured: Boolean = false,
    val aiApiKeyPreview: String? = null,
    val aiModels: List<AiModel> = emptyList(),
    val aiModelsLoading: Boolean = false,
    val aiModelsLoadError: Boolean = false,
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
    private val aiApiKeyStore: AiApiKeyStore = NoOpAiApiKeyStore,
    private val aiModelCatalog: AiModelCatalog = NoOpAiModelCatalog,
) : ViewModel() {

    private val authBusy = MutableStateFlow(false)
    private val spreadsheetError = MutableStateFlow(false)
    private val aiBaseUrlError = MutableStateFlow(false)
    private val authError = MutableStateFlow<String?>(null)
    private val aiApiKeyPreview = MutableStateFlow<String?>(null)
    private val aiModels = MutableStateFlow(AiModelsUiState())

    private val inputErrors = combine(
        spreadsheetError,
        aiBaseUrlError,
    ) { sheetError, baseUrlError ->
        SettingsInputErrors(spreadsheet = sheetError, aiBaseUrl = baseUrlError)
    }

    private val aiApiKeyState = combine(
        aiApiKeyStore.isConfigured,
        aiApiKeyPreview,
    ) { isConfigured, preview ->
        AiApiKeyUiState(isConfigured = isConfigured, preview = preview)
    }

    private val settingsAuxiliaryState: Flow<SettingsAuxiliaryState> = combine(
        authBusy,
        inputErrors,
        authError,
        aiApiKeyState,
        aiModels,
    ) { busy, currentInputErrors, currentAuthError, keyState, models ->
        SettingsAuxiliaryState(
            authBusy = busy,
            inputErrors = currentInputErrors,
            authError = currentAuthError,
            aiApiKeyConfigured = keyState.isConfigured,
            aiApiKeyPreview = keyState.preview,
            aiModels = models,
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
                spreadsheetError = auxiliary.inputErrors.spreadsheet,
                aiBaseUrlError = auxiliary.inputErrors.aiBaseUrl,
                aiApiKeyConfigured = auxiliary.aiApiKeyConfigured,
                aiApiKeyPreview = auxiliary.aiApiKeyPreview,
                aiModels = auxiliary.aiModels.models,
                aiModelsLoading = auxiliary.aiModels.isLoading,
                aiModelsLoadError = auxiliary.aiModels.hasLoadError,
                authError = auxiliary.authError,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(),
        )

    init {
        viewModelScope.launch {
            aiApiKeyPreview.value = aiApiKeyStore.preview()
            val settings = settingsRepository.settings.first()
            if (settings.aiBaseUrl != null && aiApiKeyStore.isConfigured.first()) {
                loadAiModels()
            }
        }
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

    /** Проверяет и сохраняет HTTP(S) base URL; при смене сервера выбор модели сбрасывается. */
    fun setAiBaseUrl(raw: String) {
        val normalized = normalizeAiBaseUrl(raw)
        if (normalized == null) {
            aiBaseUrlError.value = true
            return
        }
        aiBaseUrlError.value = false
        viewModelScope.launch {
            try {
                settingsRepository.setAiBaseUrl(normalized)
                _messages.send("Адрес сохранён")
                if (aiApiKeyStore.isConfigured.first()) loadAiModels()
            } catch (_: Exception) {
                _messages.send("Не удалось сохранить адрес")
            }
        }
    }

    /** Сохраняет API key и возвращает в UI только безопасную маску. */
    fun setAiApiKey(raw: String) {
        val key = raw.trim()
        if (key.isEmpty()) {
            viewModelScope.launch { _messages.send("Введите API key") }
            return
        }
        viewModelScope.launch {
            try {
                aiApiKeyStore.save(key)
                aiApiKeyPreview.value = aiApiKeyStore.preview()
                _messages.send("API key сохранён")
                if (settingsRepository.settings.first().aiBaseUrl != null) loadAiModels()
            } catch (_: Exception) {
                _messages.send("Не удалось сохранить API key")
            }
        }
    }

    /** Удаляет ключ с устройства, не затрагивая остальные настройки. */
    fun clearAiApiKey() {
        viewModelScope.launch {
            try {
                aiApiKeyStore.clear()
                aiApiKeyPreview.value = null
                aiModels.value = AiModelsUiState()
                _messages.send("API key удалён")
            } catch (_: Exception) {
                _messages.send("Не удалось удалить API key")
            }
        }
    }

    /** Обновляет авторизованный каталог моделей текущего сервера. */
    fun refreshAiModels() {
        viewModelScope.launch { loadAiModels() }
    }

    private suspend fun loadAiModels() {
        val settings = settingsRepository.settings.first()
        if (settings.aiBaseUrl == null || !aiApiKeyStore.isConfigured.first()) {
            aiModels.value = AiModelsUiState()
            return
        }
        // Не даём выбрать модель из каталога прежнего сервера или прежнего ключа во время reload.
        aiModels.value = AiModelsUiState(isLoading = true)
        try {
            val models = withTimeoutOrNull(AI_MODEL_CATALOG_TIMEOUT_MILLIS.milliseconds) {
                aiModelCatalog.getModels()
            }
            if (models.isNullOrEmpty()) {
                aiModels.value = aiModels.value.copy(
                    isLoading = false,
                    hasLoadError = true,
                )
                return
            }
            aiModels.value = AiModelsUiState(models = models)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            aiModels.value = aiModels.value.copy(
                isLoading = false,
                hasLoadError = true,
            )
        }
    }

    /** Сохраняет модель, которую сервер вернул для выписанного ключа. */
    fun setAiModel(model: AiModel) {
        if (model.id.isBlank()) return
        viewModelScope.launch { settingsRepository.setAiModel(model) }
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
