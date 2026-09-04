package com.valerochka1337.valerochkagym.ui.settings

import android.app.Activity
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.backup.ClearDataUseCase
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.backup.ExportResult
import com.valerochka1337.valerochkagym.data.backup.PrivateOriginalsClearFailure
import com.valerochka1337.valerochkagym.data.ai.AiModel
import com.valerochka1337.valerochkagym.data.ai.AiModelCatalog
import com.valerochka1337.valerochkagym.data.ai.normalizeAiBaseUrl
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.ImportResult
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepository
import com.valerochka1337.valerochkagym.data.google.HealthSheetsRepository
import com.valerochka1337.valerochkagym.data.google.HealthImportResult
import com.valerochka1337.valerochkagym.data.google.RemoteClearResult
import com.valerochka1337.valerochkagym.data.google.SheetsRepository
import com.valerochka1337.valerochkagym.data.google.spreadsheetIdFrom
import com.valerochka1337.valerochkagym.data.settings.GymSettings
import com.valerochka1337.valerochkagym.data.settings.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.settings.HealthSyncSettings
import com.valerochka1337.valerochkagym.data.settings.AiApiKeyStore
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.ui.theme.AccentColor
import com.valerochka1337.valerochkagym.ui.theme.PaletteMode
import com.valerochka1337.valerochkagym.ui.theme.ThemeMode
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import com.valerochka1337.valerochkagym.worker.HealthSyncScheduler
import com.valerochka1337.valerochkagym.worker.NoOpHealthSyncScheduler
import com.valerochka1337.valerochkagym.worker.ConfigurationUploadScheduler
import com.valerochka1337.valerochkagym.worker.NoOpConfigurationUploadScheduler
import com.valerochka1337.valerochkagym.worker.NoOpRoutineUploadScheduler
import com.valerochka1337.valerochkagym.worker.RoutineUploadScheduler
import com.valerochka1337.valerochkagym.worker.UploadScheduler
import com.valerochka1337.valerochkagym.worker.WeeklyScheduleRecoveryScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    override suspend fun schedule(measurementId: String) = Unit
    override suspend fun retry(measurementId: String) = Unit
    override suspend fun scheduleAllPending(): Int = 0
}

/** Совместимый с прямыми unit-тестами no-op; Hilt внедряет WorkManager-планировщик. */
private object NoOpWeeklyScheduleRecoveryScheduler : WeeklyScheduleRecoveryScheduler {
    override fun enqueue() = Unit
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

data class HealthSyncDisclosure(
    val category: HealthSyncCategory,
    val title: String,
    val fields: List<String>,
    val exclusions: List<String>,
) {
    val warning = "Эти данные доступны всем, у кого есть доступ к выбранной Google Sheets таблице."
    val localOnly = "Объём каждого выполненного подхода передаётся в его первичной строке. Отдельные строки и наборы агрегированной аналитики — общий тоннаж, e1RM, нагрузка по мышцам, изменения, тренды, сравнения и сводки — не создаются и не передаются."
}

/** Two explicit remote-delete stages; confirmation owns a frozen category set. */
sealed interface RemoteClearUiState {
    data class Selecting(val selected: Set<HealthSyncCategory> = emptySet()) : RemoteClearUiState
    data class Confirming(
        val selected: List<HealthSyncCategory>,
        val isClearing: Boolean = false,
    ) : RemoteClearUiState
}

/** One source of truth for the visible category names and their per-send disclosure contract. */
internal fun HealthSyncCategory.healthSyncDisclosure(): HealthSyncDisclosure = when (this) {
    HealthSyncCategory.WORKOUTS_AND_CONFIGURATION -> HealthSyncDisclosure(
        category = this,
        title = "Тренировки и программы",
        fields = listOf(
            "Идентификаторы, даты завершённых тренировок, названия, секции и заметки программ",
            "Выполненные подходы: упражнения, варианты, веса, повторы, длительность, скорость, наклон и объём",
            "Программы: план подходов и повторений, отдых, упражнения, варианты, залы и связи с залами",
        ),
        exclusions = emptyList(),
    )
    HealthSyncCategory.MEASUREMENTS -> HealthSyncDisclosure(
        category = this,
        title = "Состав тела и InBody",
        fields = listOf(
            "Идентификатор, дата и время замера",
            "Все исходные показатели состава тела, InBody и обхватов",
            "Условия замера: после еды, после тренировки, необычная гидратация и заметка",
        ),
        exclusions = emptyList(),
    )
    HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS -> HealthSyncDisclosure(
        category = this,
        title = "Медицинские анализы",
        fields = listOf(
            "Стабильные идентификаторы, версии, статусы, происхождение, даты, названия и заметки исследований",
            "Каждый результат: исходное название, тип, значение, единица и референс",
            "Метод, материал, источник, страница документа и каноническое сопоставление показателя",
        ),
        exclusions = listOf("PDF и фото", "сырой ответ AI", "черновики"),
    )
    HealthSyncCategory.HEALTH_RESTRICTIONS -> HealthSyncDisclosure(
        category = this,
        title = "Ограничения и важная информация",
        fields = listOf(
            "Стабильные идентификаторы, версии, структурированное описание, статус и источник",
            "Даты подтверждения, начала и пересмотра",
        ),
        exclusions = listOf("исходный свободный текст"),
    )
}

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
    private val configurationUploadScheduler: ConfigurationUploadScheduler =
        NoOpConfigurationUploadScheduler,
    private val weeklyScheduleRecoveryScheduler: WeeklyScheduleRecoveryScheduler =
        NoOpWeeklyScheduleRecoveryScheduler,
    private val healthSyncScheduler: HealthSyncScheduler = NoOpHealthSyncScheduler,
    private val healthSheetsRepository: HealthSheetsRepository? = null,
    private val sheetsRepository: SheetsRepository? = null,
) : ViewModel() {

    /** Keeps consent, import-before-enable and scheduling in one observable order. */
    private val healthSyncMutationMutex = Mutex()
    private val pendingHealthSyncDisclosure = MutableStateFlow<HealthSyncDisclosure?>(null)
    val healthSyncDisclosure: StateFlow<HealthSyncDisclosure?> = pendingHealthSyncDisclosure.asStateFlow()
    private val pendingRemoteClear = MutableStateFlow<RemoteClearUiState?>(null)
    val remoteClearState: StateFlow<RemoteClearUiState?> = pendingRemoteClear.asStateFlow()

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
            settingsRepository.initializeLegacyHealthSyncIfNeeded()
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
            AuthorizeOutcome.Granted -> {
                weeklyScheduleRecoveryScheduler.wake()
                importHistoryIfConfigured()
            }
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
            healthSyncMutationMutex.withLock {
                val previousId = settingsRepository.settings.first().spreadsheetId
                if (previousId == id) {
                    importHistory()
                    return@withLock
                }
                // The new target is only retained after its managed history can be read. The
                // mutex also prevents category scheduling from observing a half-validated target.
                settingsRepository.setSpreadsheetId(id)
                try {
                    // A first connection performs one primary-data probe even though sync stays
                    // disabled. An explicit master-off choice is never bypassed; medical data
                    // remains consent-scoped in [importHistory].
                    val firstConnectionProbe = previousId == null &&
                        !settingsRepository.hasExplicitHealthSyncEnabledChoice()
                    if (!importHistory(forcePrimaryImport = firstConnectionProbe)) {
                        settingsRepository.setSpreadsheetId(previousId)
                        _messages.send("Не удалось проверить новую таблицу; прежняя таблица сохранена")
                    }
                } catch (error: CancellationException) {
                    withContext(NonCancellable) {
                        settingsRepository.setSpreadsheetId(previousId)
                    }
                    throw error
                } catch (_: Exception) {
                    settingsRepository.setSpreadsheetId(previousId)
                    _messages.send("Не удалось проверить новую таблицу; прежняя таблица сохранена")
                }
            }
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
    private suspend fun importHistory(forcePrimaryImport: Boolean = false): Boolean {
        val sync = settingsRepository.settings.first().healthSync
        val primary = setOf(
            HealthSyncCategory.WORKOUTS_AND_CONFIGURATION,
            HealthSyncCategory.MEASUREMENTS,
        ).filterTo(linkedSetOf()) { category -> forcePrimaryImport || sync.isEnabled(category) }
        val primaryResult = if (primary.isEmpty()) ImportResult.NothingToImport else importRepository.import(primary)
        val message = when (primaryResult) {
            is ImportResult.Success -> buildImportMessage(primaryResult)
            ImportResult.NothingToImport -> "Нечего импортировать"
            is ImportResult.Failure -> primaryResult.reason
        }
        if (primaryResult is ImportResult.Failure) {
            _messages.send(message)
            return false
        }
        val healthImported = MEDICAL_SYNC_CATEGORIES
            .filter(sync::isEnabled)
            .sumOf { category -> healthSheetsRepository?.import(category) ?: 0 }
        _messages.send(
            if (healthImported == 0) message else "Импортировано данных здоровья: $healthImported",
        )
        return true
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
            if (result.importedExercises > 0) add("упражнений: ${result.importedExercises}")
            if (result.importedGyms > 0) add("залов: ${result.importedGyms}")
            if (result.importedRoutineGyms > 0) {
                add("привязок программ: ${result.importedRoutineGyms}")
            }
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
            val sync = settingsRepository.settings.first().healthSync
            var count = 0
            if (sync.isEnabled(HealthSyncCategory.WORKOUTS_AND_CONFIGURATION)) {
                count += uploadScheduler.scheduleAllPending()
                count += routineUploadScheduler.scheduleAll()
                count += configurationUploadScheduler.scheduleAll()
            }
            if (sync.isEnabled(HealthSyncCategory.MEASUREMENTS)) {
                count += measurementUploadScheduler.scheduleAllPending()
            }
            MEDICAL_SYNC_CATEGORIES.filter(sync::isEnabled).forEach { category ->
                count += healthSyncScheduler.schedulePending(category)
            }
            _messages.send("Поставлено в очередь: $count")
        }
    }

    fun setHealthSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            healthSyncMutationMutex.withLock {
                if (enabled) {
                    // A confirmation can arrive while an earlier import suspends. Re-read the
                    // selected categories until every current selection has imported, then and
                    // only then make the master switch effective and schedule work.
                    val imported = mutableSetOf<HealthSyncCategory>()
                    while (true) {
                        val category = settingsRepository.settings.first().healthSync.categories
                            .firstOrNull { it !in imported }
                            ?: break
                        importForEnable(category)?.let { message ->
                            _messages.send(message)
                            return@withLock
                        }
                        imported += category
                    }
                }
                settingsRepository.setHealthSyncEnabled(enabled)
                applyHealthSyncSettings(settingsRepository.settings.first().healthSync)
            }
        }
    }

    fun setHealthSyncCategory(category: HealthSyncCategory, enabled: Boolean) {
        viewModelScope.launch {
            healthSyncMutationMutex.withLock {
                val before = settingsRepository.settings.first().healthSync
                if (enabled && before.enabled) {
                    importForEnable(category)?.let { message ->
                        _messages.send(message)
                        return@withLock
                    }
                }
                settingsRepository.setHealthSyncCategory(category, enabled)
                applyHealthSyncSettings(settingsRepository.settings.first().healthSync)
            }
        }
    }

    /** UI entrypoint: turning a category on always requires one explicit, category-scoped disclosure. */
    fun requestHealthSyncCategory(category: HealthSyncCategory, enabled: Boolean) {
        if (!enabled) {
            setHealthSyncCategory(category, false)
            return
        }
        viewModelScope.launch {
            val fresh = settingsRepository.settings.first().healthSync
            if (category in fresh.categories) return@launch
            pendingHealthSyncDisclosure.value = category.healthSyncDisclosure()
        }
    }

    fun cancelHealthSyncDisclosure() {
        pendingHealthSyncDisclosure.value = null
    }

    fun confirmHealthSyncDisclosure() {
        viewModelScope.launch {
            healthSyncMutationMutex.withLock {
                val disclosure = pendingHealthSyncDisclosure.value ?: return@withLock
                val category = disclosure.category
                val fresh = settingsRepository.settings.first().healthSync
                pendingHealthSyncDisclosure.value = null
                // A stale dialog never overrides an intervening change. Existing enabled state is a no-op.
                if (category in fresh.categories) return@withLock
                if (fresh.enabled) {
                    importForEnable(category)?.let { message ->
                        _messages.send(message)
                        return@withLock
                    }
                }
                settingsRepository.setHealthSyncCategory(category, true)
                // The master switch may have been turned off while the dialog was open. Its
                // immediate cancellation has already reached every scheduler; recording the user's
                // category selection must not enqueue or cancel work a second time.
                if (fresh.enabled) {
                    applyHealthSyncSettings(settingsRepository.settings.first().healthSync)
                }
            }
        }
    }

    /** Opens a separately confirmed, category-scoped remote deletion. It never changes local state. */
    fun requestRemoteClear() {
        if (pendingRemoteClear.value == null) pendingRemoteClear.value = RemoteClearUiState.Selecting()
    }

    fun toggleRemoteClearCategory(category: HealthSyncCategory) {
        val state = pendingRemoteClear.value as? RemoteClearUiState.Selecting ?: return
        pendingRemoteClear.value = state.copy(
            selected = state.selected.toMutableSet().apply {
                if (!add(category)) remove(category)
            },
        )
    }

    fun continueRemoteClear() {
        val state = pendingRemoteClear.value as? RemoteClearUiState.Selecting ?: return
        if (state.selected.isEmpty()) return
        pendingRemoteClear.value = RemoteClearUiState.Confirming(
            selected = HealthSyncCategory.entries.filter(state.selected::contains),
        )
    }

    /** Back/cancel is inert while a confirmed request is in flight and otherwise forgets selection. */
    fun cancelRemoteClear() {
        if ((pendingRemoteClear.value as? RemoteClearUiState.Confirming)?.isClearing == true) return
        pendingRemoteClear.value = null
    }

    fun confirmRemoteClear() {
        val confirmation = pendingRemoteClear.value as? RemoteClearUiState.Confirming ?: return
        if (confirmation.isClearing) return
        pendingRemoteClear.value = confirmation.copy(isClearing = true)
        viewModelScope.launch {
            try {
                healthSyncMutationMutex.withLock {
                    val paused = pauseRemoteClearWorkers(confirmation.selected)
                    try {
                        val outcomes = mutableListOf<Pair<HealthSyncCategory, RemoteClearResult>>()
                        for (category in confirmation.selected) {
                            val result = clearRemoteCategory(category)
                            outcomes += category to result
                            if (result is RemoteClearResult.Failure) break
                        }
                        pendingRemoteClear.value = null
                        _messages.send(remoteClearMessage(confirmation.selected, outcomes))
                    } finally {
                        withContext(NonCancellable) {
                            restoreRemoteClearWorkers(paused)
                        }
                    }
                }
            } catch (error: CancellationException) {
                pendingRemoteClear.value = confirmation.copy(isClearing = false)
                throw error
            } catch (_: Exception) {
                pendingRemoteClear.value = null
                _messages.send("Не удалось очистить выбранные данные в Google Sheets. Локальные данные и настройки не менялись — повторите через кнопку.")
            }
        }
    }

    private suspend fun clearRemoteCategory(category: HealthSyncCategory): RemoteClearResult = when (category) {
        HealthSyncCategory.WORKOUTS_AND_CONFIGURATION -> sheetsRepository
            ?.clearWorkoutsAndConfigurationAfterConfirmation()
            ?: RemoteClearResult.Failure("Очистка тренировок недоступна")
        HealthSyncCategory.MEASUREMENTS -> sheetsRepository
            ?.clearMeasurementsAfterConfirmation()
            ?: RemoteClearResult.Failure("Очистка замеров недоступна")
        HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS,
        HealthSyncCategory.HEALTH_RESTRICTIONS,
        -> healthSheetsRepository?.clearAfterConfirmation(category)
            ?: RemoteClearResult.Failure("Очистка данных здоровья недоступна")
    }

    /** Stops only currently effective category workers while a remote range is being cleared. */
    private suspend fun pauseRemoteClearWorkers(
        selected: List<HealthSyncCategory>,
    ): Set<HealthSyncCategory> {
        val sync = settingsRepository.settings.first().healthSync
        val active = selected.filterTo(linkedSetOf(), sync::isEnabled)
        active.forEach { category -> applyCategoryScheduling(category, enabled = false) }
        return active
    }

    /** Restores the current persisted state; a concurrent settings change cannot interleave the mutex. */
    private suspend fun restoreRemoteClearWorkers(categories: Set<HealthSyncCategory>) {
        val sync = settingsRepository.settings.first().healthSync
        categories.forEach { category ->
            applyCategoryScheduling(category, enabled = sync.isEnabled(category))
        }
    }

    private suspend fun applyCategoryScheduling(category: HealthSyncCategory, enabled: Boolean) = when (category) {
        HealthSyncCategory.WORKOUTS_AND_CONFIGURATION -> {
            uploadScheduler.onCategoryChanged(enabled)
            routineUploadScheduler.onCategoryChanged(enabled)
            configurationUploadScheduler.onCategoryChanged(enabled)
        }
        HealthSyncCategory.MEASUREMENTS -> measurementUploadScheduler.onCategoryChanged(enabled)
        HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS,
        HealthSyncCategory.HEALTH_RESTRICTIONS,
        -> healthSyncScheduler.onCategoryChanged(category, enabled)
    }

    private fun remoteClearMessage(
        selected: List<HealthSyncCategory>,
        outcomes: List<Pair<HealthSyncCategory, RemoteClearResult>>,
    ): String {
        val failed = outcomes.firstOrNull { (_, result) -> result is RemoteClearResult.Failure }
        if (failed == null) {
            return "Управляемые данные удалены из Google Sheets. Локальные данные и настройки не менялись."
        }
        val cleared = outcomes.flatMap { (category, result) ->
            when (result) {
                is RemoteClearResult.Success -> result.clearedRanges.map { range -> "${category.healthSyncDisclosure().title}: $range" }
                is RemoteClearResult.Failure -> result.clearedRanges.map { range -> "${category.healthSyncDisclosure().title}: $range" }
            }
        }
        val notAttempted = selected.drop(outcomes.size).map { it.healthSyncDisclosure().title }
        val failedResult = failed.second as RemoteClearResult.Failure
        val notClearedRanges = buildList {
            failedResult.failedRange?.let(::add)
            addAll(failedResult.remainingRanges)
        }.map { range -> "${failed.first.healthSyncDisclosure().title}: $range" }
        return buildString {
            append("Очистка Google Sheets выполнена не полностью. ")
            if (cleared.isNotEmpty()) append("Уже очищено: ${cleared.joinToString()}. ")
            append("Не удалось очистить: ${failed.first.healthSyncDisclosure().title}.")
            if (notClearedRanges.isNotEmpty()) append(" Не очищено: ${notClearedRanges.joinToString()}.")
            if (notAttempted.isNotEmpty()) append(" Осталось: ${notAttempted.joinToString()}.")
            append(" Локальные данные и настройки не менялись — повторите через кнопку.")
        }
    }

    /** Returns a user-facing failure without changing consent or queue state. */
    private suspend fun importForEnable(category: HealthSyncCategory): String? = when (category) {
        HealthSyncCategory.WORKOUTS_AND_CONFIGURATION,
        HealthSyncCategory.MEASUREMENTS,
        -> when (val result = importRepository.import(setOf(category))) {
            is ImportResult.Failure -> result.reason
            else -> null
        }
        HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS,
        HealthSyncCategory.HEALTH_RESTRICTIONS,
        -> when (val result = healthSheetsRepository?.importForEnable(category) ?: HealthImportResult.NothingToImport) {
            is HealthImportResult.Failure -> result.message
            else -> null
        }
    }

    /** Settings are committed before WorkManager cancellation/rescheduling observes their effect. */
    private suspend fun applyHealthSyncSettings(sync: HealthSyncSettings) {
        HealthSyncCategory.entries.forEach { category ->
            applyCategoryScheduling(category, sync.isEnabled(category))
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
            try {
                clearDataUseCase()
                _messages.send("Данные очищены")
            } catch (error: PrivateOriginalsClearFailure) {
                _messages.send(error.message ?: "Данные очищены частично")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _messages.send("Не удалось очистить данные")
            }
        }
    }

    /**
     * Меняет акцент приложения. Иконку в лаунчере переключать отсюда не нужно: за ней следит
     * [com.valerochka1337.valerochkagym.data.appicon.AppIconManager], подписанный на настройку.
     */
    fun setAccent(accent: AccentColor) {
        viewModelScope.launch { settingsRepository.setAccent(accent) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setPaletteMode(mode: PaletteMode) {
        viewModelScope.launch { settingsRepository.setPaletteMode(mode) }
    }
}

private val MEDICAL_SYNC_CATEGORIES = setOf(
    HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS,
    HealthSyncCategory.HEALTH_RESTRICTIONS,
)
