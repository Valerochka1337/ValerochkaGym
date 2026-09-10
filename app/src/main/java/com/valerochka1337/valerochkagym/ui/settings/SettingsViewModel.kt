package com.valerochka1337.valerochkagym.ui.settings

import android.app.Activity
import android.content.IntentSender
import android.net.Uri
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.backup.ClearDataUseCase
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.backup.ExportResult
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.ImportResult
import com.valerochka1337.valerochkagym.data.google.TokenResult
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepository
import com.valerochka1337.valerochkagym.data.google.spreadsheetIdFrom
import com.valerochka1337.valerochkagym.data.schedule.WeeklySchedule
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRecoveryResult
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRepository
import com.valerochka1337.valerochkagym.data.settings.CalendarAccountIdentity
import com.valerochka1337.valerochkagym.data.settings.GymSettings
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.data.settings.normalizeCalendarEmail
import com.valerochka1337.valerochkagym.ui.theme.AccentColor
import com.valerochka1337.valerochkagym.ui.theme.PaletteMode
import com.valerochka1337.valerochkagym.ui.theme.ThemeMode
import com.valerochka1337.valerochkagym.worker.ConfigurationUploadScheduler
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import com.valerochka1337.valerochkagym.worker.NoOpConfigurationUploadScheduler
import com.valerochka1337.valerochkagym.worker.NoOpRoutineUploadScheduler
import com.valerochka1337.valerochkagym.worker.RoutineUploadScheduler
import com.valerochka1337.valerochkagym.worker.UploadScheduler
import com.valerochka1337.valerochkagym.worker.WeeklyScheduleRecoveryScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Шаг изменения отдыха по умолчанию и его нижняя граница (в секундах). */
private const val MIN_REST_SECONDS = 15
private const val MIN_HEART_RATE_REST_THRESHOLD_BPM = 40
private const val MAX_HEART_RATE_REST_THRESHOLD_BPM = 220
private const val MIN_HEART_RATE_REST_HOLD_SECONDS = 5
private const val MAX_HEART_RATE_REST_HOLD_SECONDS = 60

/** Сообщение об ошибке настройки OAuth-доступа. */
private const val AUTH_ERROR_MESSAGE = "Не удалось настроить доступ — попробуйте ещё раз"
private const val AUTH_KIND_KEY = "calendar_auth_kind"
private const val AUTH_TARGET_KEY = "calendar_auth_target"
private const val AUTH_NONCE_KEY = "calendar_auth_nonce"
private const val AUTH_BUSY_KEY = "calendar_auth_busy"

data class CalendarConsentRequest(val intentSender: IntentSender, val operationNonce: String)

private enum class PendingCalendarAction {
  SELECT_OTHER,
  AUTHORIZE_PREFERRED,
  AUTHORIZE_OTHER,
  DISCONNECT,
}

private data class CalendarOperation(
    val kind: PendingCalendarAction,
    val target: String?,
    val nonce: String,
)

/** Совместимый с прямыми unit-тестами no-op; Hilt всегда внедряет реальный планировщик. */
private object NoOpMeasurementUploadScheduler : MeasurementUploadScheduler {
  override fun schedule(measurementId: String) = Unit

  override suspend fun retry(measurementId: String) = Unit

  override suspend fun scheduleAllPending(): Int = 0
}

/** Совместимый с прямыми unit-тестами no-op; Hilt внедряет WorkManager-планировщик. */
private object NoOpWeeklyScheduleRecoveryScheduler : WeeklyScheduleRecoveryScheduler {
  override fun enqueue() = Unit
}

private object NoOpWeeklyScheduleRepository : WeeklyScheduleRepository {
  override fun observe() = MutableStateFlow(WeeklySchedule())

  override suspend fun save(schedule: WeeklySchedule) =
      com.valerochka1337.valerochkagym.data.google.ScheduleResult.Success

  override suspend fun clear() = com.valerochka1337.valerochkagym.data.google.ScheduleResult.Success

  override suspend fun resumePendingOperation() = WeeklyScheduleRecoveryResult.NothingPending

  override suspend fun hasRecoverableWorkForAccount(email: String) = true
}

private data class SettingsInputErrors(
    val spreadsheet: Boolean,
)

private data class SettingsAuxiliaryState(
    val authBusy: Boolean,
    val inputErrors: SettingsInputErrors,
    val authError: String?,
)

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
class SettingsViewModel
@Inject
constructor(
    private val settingsRepository: SettingsRepository,
    private val googleAuth: GoogleAuth,
    private val uploadScheduler: UploadScheduler,
    private val importRepository: WorkoutImportRepository,
    private val databaseExporter: DatabaseExporter,
    private val clearDataUseCase: ClearDataUseCase,
    private val measurementUploadScheduler: MeasurementUploadScheduler =
        NoOpMeasurementUploadScheduler,
    private val routineUploadScheduler: RoutineUploadScheduler = NoOpRoutineUploadScheduler,
    private val configurationUploadScheduler: ConfigurationUploadScheduler =
        NoOpConfigurationUploadScheduler,
    private val weeklyScheduleRecoveryScheduler: WeeklyScheduleRecoveryScheduler =
        NoOpWeeklyScheduleRecoveryScheduler,
    private val weeklyScheduleRepository: WeeklyScheduleRepository = NoOpWeeklyScheduleRepository,
    private val calendarIdentity: CalendarAccountIdentity = settingsRepository,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

  private val authBusy = MutableStateFlow(savedStateHandle[AUTH_BUSY_KEY] ?: false)
  private val spreadsheetError = MutableStateFlow(false)
  private val authError = MutableStateFlow<String?>(null)
  private val calendarOperationLock = Any()
  private var runningCalendarAttemptNonce: String? = null
  private var resolvingConsentNonce: String? = null

  private val inputErrors = spreadsheetError.map { SettingsInputErrors(spreadsheet = it) }

  private val settingsAuxiliaryState: Flow<SettingsAuxiliaryState> =
      combine(
          authBusy,
          inputErrors,
          authError,
      ) { busy, currentInputErrors, currentAuthError ->
        SettingsAuxiliaryState(
            authBusy = busy,
            inputErrors = currentInputErrors,
            authError = currentAuthError,
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
                authError = auxiliary.authError,
            )
          }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(5_000),
              initialValue = SettingsUiState(),
          )

  private val _consentRequests = Channel<CalendarConsentRequest>(Channel.CONFLATED)

  /** Запросы согласия на OAuth-доступ, которые экран должен запустить через launcher. */
  val consentRequests: Flow<CalendarConsentRequest> = _consentRequests.receiveAsFlow()

  private val _messages = Channel<String>(Channel.BUFFERED)

  /** Короткие уведомления для snackbar (например, результат «Выгрузить всё»). */
  val messages: Flow<String> = _messages.receiveAsFlow()

  fun connectPreferred(activity: Activity) {
    val operation = beginCalendarOperation(PendingCalendarAction.AUTHORIZE_PREFERRED) ?: return
    launchCalendarAttempt(operation) {
      val target = normalizeCalendarEmail(calendarIdentity.preferredCalendarEmail.first())
      if (!isCurrent(operation)) return@launchCalendarAttempt
      if (target == null) {
        selectOtherAndAuthorize(activity, operation)
      } else {
        val targeted = updateCurrent(operation, PendingCalendarAction.AUTHORIZE_PREFERRED, target)
        if (targeted != null) requestAuthorize(activity, targeted)
      }
    }
  }

  fun connectOther(activity: Activity) {
    val operation = beginCalendarOperation(PendingCalendarAction.SELECT_OTHER) ?: return
    launchCalendarAttempt(operation) { selectOtherAndAuthorize(activity, operation) }
  }

  /** Reissues only the exact target saved before Activity-result recreation. */
  fun resumePendingCalendarOperation(activity: Activity) {
    val operation = claimSavedCalendarOperation() ?: return clearMalformedSavedOperation()
    launchCalendarAttempt(operation, alreadyClaimed = true) {
      when (operation.kind) {
        PendingCalendarAction.SELECT_OTHER -> selectOtherAndAuthorize(activity, operation)
        PendingCalendarAction.AUTHORIZE_PREFERRED,
        PendingCalendarAction.AUTHORIZE_OTHER -> {
          if (operation.target == null) failCurrent(operation)
          else requestAuthorize(activity, operation)
        }
        PendingCalendarAction.DISCONNECT -> disconnectCalendar(operation)
      }
    }
  }

  fun consentResolved(activity: Activity, operationNonce: String, granted: Boolean) {
    if (!granted) {
      val operation =
          readSavedCalendarOperation(operationNonce)
              ?: run {
                clearMalformedSavedOperation()
                return
              }
      completeCurrent(operation)
      return
    }
    val operation =
        claimSavedCalendarOperation(operationNonce, isConsentResult = true)
            ?: run {
              clearMalformedSavedOperation()
              return
            }
    launchCalendarAttempt(operation, alreadyClaimed = true) {
      if (operation.target == null) failCurrent(operation)
      else requestAuthorize(activity, operation)
    }
  }

  fun disconnectCalendar() {
    val operation = beginCalendarOperation(PendingCalendarAction.DISCONNECT) ?: return
    launchCalendarAttempt(operation) { disconnectCalendar(operation) }
  }

  private fun launchCalendarAttempt(
      operation: CalendarOperation,
      alreadyClaimed: Boolean = false,
      block: suspend () -> Unit,
  ) {
    if (!alreadyClaimed && !claimAttempt(operation)) return
    viewModelScope.launch {
      try {
        block()
      } catch (cancellation: CancellationException) {
        completeCurrent(operation)
        throw cancellation
      } catch (cancellation: GetCredentialCancellationException) {
        completeCurrent(operation)
      } catch (_: Exception) {
        failCurrent(operation)
      } finally {
        releaseAttempt(operation)
      }
    }
  }

  private suspend fun selectOtherAndAuthorize(activity: Activity, operation: CalendarOperation) {
    if (!isCurrent(operation)) return
    val result = googleAuth.selectAccount(activity)
    if (!isCurrent(operation)) return
    val selected =
        result.getOrElse { error ->
          if (error is GetCredentialCancellationException) {
            completeCurrent(operation)
            return
          }
          return failCurrent(operation)
        }
    val target = normalizeCalendarEmail(selected) ?: return failCurrent(operation)
    val targeted = updateCurrent(operation, PendingCalendarAction.AUTHORIZE_OTHER, target) ?: return
    if (!isCurrent(targeted)) return
    calendarIdentity.setPreferredCalendarEmail(target)
    if (!isCurrent(targeted)) return
    requestAuthorize(activity, targeted)
  }

  private suspend fun requestAuthorize(activity: Activity, operation: CalendarOperation) {
    val target = operation.target ?: return failCurrent(operation)
    if (!isCurrent(operation)) return
    when (val outcome = googleAuth.authorizeForAccount(activity, target)) {
      is AuthorizeOutcome.NeedsConsent -> {
        if (!isCurrent(operation)) return
        val consentOperation = renewForConsent(operation) ?: return
        try {
          _consentRequests.send(
              CalendarConsentRequest(outcome.pendingIntent.intentSender, consentOperation.nonce)
          )
        } catch (cancellation: CancellationException) {
          completeCurrent(consentOperation)
          throw cancellation
        }
        if (!isCurrent(consentOperation)) return
      }
      is AuthorizeOutcome.Failed -> failCurrent(operation)
      AuthorizeOutcome.Granted -> {
        if (!isCurrent(operation)) return
        when (val token = googleAuth.getAccessTokenForAccount(target)) {
          is TokenResult.Success -> {
            if (!isCurrent(operation)) return
            val shouldWake = weeklyScheduleRepository.hasRecoverableWorkForAccount(target)
            if (!isCurrent(operation)) return
            val committed =
                calendarIdentity.commitConnectedCalendarEmail(target) { completeCurrent(operation) }
            if (committed && shouldWake) {
              weeklyScheduleRecoveryScheduler.wake()
            }
          }
          TokenResult.NeedsConsent -> failCurrent(operation)
          is TokenResult.Failed -> failCurrent(operation)
        }
      }
    }
  }

  private suspend fun disconnectCalendar(operation: CalendarOperation) {
    val target =
        operation.target
            ?: normalizeCalendarEmail(calendarIdentity.connectedCalendarEmail.first()).also {
              if (!isCurrent(operation)) return
            }
    if (target == null) {
      completeCurrent(operation)
      return
    }
    val targeted = updateCurrent(operation, PendingCalendarAction.DISCONNECT, target) ?: return
    if (!isCurrent(targeted)) return
    val result = googleAuth.revokeCalendarAccess(target)
    if (!isCurrent(targeted)) return
    if (result.isFailure) return failCurrent(targeted)
    if (!isCurrent(targeted)) return
    calendarIdentity.clearConnectedCalendarEmail(target)
    if (!isCurrent(targeted)) return
    completeCurrent(targeted)
  }

  private fun beginCalendarOperation(kind: PendingCalendarAction): CalendarOperation? =
      synchronized(calendarOperationLock) {
        if (authBusy.value || savedStateHandle.get<Boolean>(AUTH_BUSY_KEY) == true) return null
        val operation = CalendarOperation(kind, null, UUID.randomUUID().toString())
        persistOperation(operation)
        authError.value = null
        operation
      }

  private fun claimSavedCalendarOperation(
      expectedNonce: String? = null,
      isConsentResult: Boolean = false,
  ): CalendarOperation? =
      synchronized(calendarOperationLock) {
        val operation = readSavedOperation()
        if (operation == null || expectedNonce?.let { it != operation.nonce } == true) {
          return null
        }
        if (isConsentResult && resolvingConsentNonce == operation.nonce) return null
        if (runningCalendarAttemptNonce != null && runningCalendarAttemptNonce != operation.nonce) {
          return null
        }
        if (!isConsentResult && runningCalendarAttemptNonce == operation.nonce) return null
        runningCalendarAttemptNonce = operation.nonce
        if (isConsentResult) resolvingConsentNonce = operation.nonce
        operation
      }

  private fun readSavedCalendarOperation(expectedNonce: String): CalendarOperation? =
      synchronized(calendarOperationLock) {
        readSavedOperation()?.takeIf { it.nonce == expectedNonce }
      }

  private fun claimAttempt(operation: CalendarOperation): Boolean =
      synchronized(calendarOperationLock) {
        if (!isCurrentLocked(operation) || runningCalendarAttemptNonce == operation.nonce) {
          false
        } else {
          runningCalendarAttemptNonce = operation.nonce
          true
        }
      }

  private fun releaseAttempt(operation: CalendarOperation) =
      synchronized(calendarOperationLock) {
        if (runningCalendarAttemptNonce == operation.nonce) runningCalendarAttemptNonce = null
      }

  private fun updateCurrent(
      operation: CalendarOperation,
      kind: PendingCalendarAction,
      target: String,
  ): CalendarOperation? =
      synchronized(calendarOperationLock) {
        if (!isCurrentLocked(operation)) return null
        CalendarOperation(kind, target, operation.nonce).also(::persistOperation)
      }

  /** A fresh nonce makes a repeated result for an earlier consent request safely stale. */
  private fun renewForConsent(operation: CalendarOperation): CalendarOperation? =
      synchronized(calendarOperationLock) {
        if (!isCurrentLocked(operation)) return null
        CalendarOperation(operation.kind, operation.target, UUID.randomUUID().toString()).also {
            consentOperation ->
          persistOperation(consentOperation)
          if (runningCalendarAttemptNonce == operation.nonce) {
            runningCalendarAttemptNonce = consentOperation.nonce
          }
          if (resolvingConsentNonce == operation.nonce) resolvingConsentNonce = null
        }
      }

  private fun isCurrent(operation: CalendarOperation): Boolean =
      synchronized(calendarOperationLock) { isCurrentLocked(operation) }

  private fun isCurrentLocked(operation: CalendarOperation): Boolean =
      savedStateHandle.get<Boolean>(AUTH_BUSY_KEY) == true &&
          savedStateHandle.get<String>(AUTH_NONCE_KEY) == operation.nonce

  private fun completeCurrent(operation: CalendarOperation): Boolean =
      synchronized(calendarOperationLock) {
        if (!isCurrentLocked(operation)) return false
        clearPersistedOperation()
        true
      }

  private fun failCurrent(operation: CalendarOperation) {
    synchronized(calendarOperationLock) {
      if (!isCurrentLocked(operation)) return
      authError.value = AUTH_ERROR_MESSAGE
      clearPersistedOperation()
    }
  }

  private fun persistOperation(operation: CalendarOperation) {
    savedStateHandle[AUTH_KIND_KEY] = operation.kind.name
    if (operation.target == null) savedStateHandle.remove<String>(AUTH_TARGET_KEY)
    else savedStateHandle[AUTH_TARGET_KEY] = operation.target
    savedStateHandle[AUTH_NONCE_KEY] = operation.nonce
    savedStateHandle[AUTH_BUSY_KEY] = true
    authBusy.value = true
  }

  private fun readSavedOperation(): CalendarOperation? {
    if (savedStateHandle.get<Boolean>(AUTH_BUSY_KEY) != true) return null
    val kind =
        savedStateHandle.get<String>(AUTH_KIND_KEY)?.let {
          runCatching { PendingCalendarAction.valueOf(it) }.getOrNull()
        } ?: return null
    val nonce =
        savedStateHandle.get<String>(AUTH_NONCE_KEY)?.takeIf(String::isNotBlank) ?: return null
    return CalendarOperation(kind, savedStateHandle[AUTH_TARGET_KEY], nonce)
  }

  private fun clearPersistedOperation() {
    val nonce = savedStateHandle.get<String>(AUTH_NONCE_KEY)
    savedStateHandle.remove<String>(AUTH_KIND_KEY)
    savedStateHandle.remove<String>(AUTH_TARGET_KEY)
    savedStateHandle.remove<String>(AUTH_NONCE_KEY)
    savedStateHandle[AUTH_BUSY_KEY] = false
    authBusy.value = false
    if (runningCalendarAttemptNonce == nonce) runningCalendarAttemptNonce = null
    if (resolvingConsentNonce == nonce) resolvingConsentNonce = null
  }

  private fun clearMalformedSavedOperation() {
    synchronized(calendarOperationLock) {
      if (
          savedStateHandle.get<Boolean>(AUTH_BUSY_KEY) == true &&
              readSavedOperation() == null &&
              runningCalendarAttemptNonce == null
      ) {
        clearPersistedOperation()
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
      settingsRepository.setSpreadsheetId(id)
      importHistory()
    }
  }

  /** Разово восстанавливает все app-managed данные из таблицы и уведомляет о результате. */
  private suspend fun importHistory() {
    val message =
        when (val result = importRepository.importAll()) {
          is ImportResult.Success -> buildImportMessage(result)
          ImportResult.NothingToImport -> "Нечего импортировать"
          is ImportResult.Failure -> result.reason
        }
    _messages.send(message)
  }

  /**
   * После входа восстанавливаем данные, только если ID таблицы уже вернулся из backup/DataStore.
   */
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
      val count =
          uploadScheduler.scheduleAllPending() +
              measurementUploadScheduler.scheduleAllPending() +
              routineUploadScheduler.scheduleAll() +
              configurationUploadScheduler.scheduleAll()
      _messages.send("Поставлено в очередь: $count")
    }
  }

  /**
   * Меняет отдых по умолчанию на [delta] секунд (обычно ±[REST_STEP_SECONDS]), не ниже минимума.
   */
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
    val next =
        (current + delta).coerceIn(
            MIN_HEART_RATE_REST_THRESHOLD_BPM,
            MAX_HEART_RATE_REST_THRESHOLD_BPM,
        )
    if (next == current) return
    viewModelScope.launch { settingsRepository.setHeartRateRestThresholdBpm(next) }
  }

  fun changeHeartRateRestHoldSeconds(delta: Int) {
    val current = uiState.value.settings?.heartRateRestHoldSeconds ?: return
    val next =
        (current + delta).coerceIn(
            MIN_HEART_RATE_REST_HOLD_SECONDS,
            MAX_HEART_RATE_REST_HOLD_SECONDS,
        )
    if (next == current) return
    viewModelScope.launch { settingsRepository.setHeartRateRestHoldSeconds(next) }
  }

  /** Копирует базу в выбранный пользователем документ (SAF) и сообщает итог снэкбаром. */
  fun exportDatabase(target: Uri) {
    viewModelScope.launch {
      val message =
          when (val result = databaseExporter.export(target)) {
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
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        _messages.send(e.message ?: "Не удалось очистить данные")
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
