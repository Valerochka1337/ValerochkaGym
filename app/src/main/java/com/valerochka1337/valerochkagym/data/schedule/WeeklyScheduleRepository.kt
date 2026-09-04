package com.valerochka1337.valerochkagym.data.schedule

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.google.AccountBoundGoogleAuth
import com.valerochka1337.valerochkagym.data.google.CalendarApi
import com.valerochka1337.valerochkagym.data.google.CalendarEventDto
import com.valerochka1337.valerochkagym.data.google.EventDateTimeDto
import com.valerochka1337.valerochkagym.data.google.EventReminderOverrideDto
import com.valerochka1337.valerochkagym.data.google.EventRemindersDto
import com.valerochka1337.valerochkagym.data.google.GoogleErrorMessages
import com.valerochka1337.valerochkagym.data.google.ScheduleResult
import com.valerochka1337.valerochkagym.data.google.TokenResult
import com.valerochka1337.valerochkagym.di.WeeklyScheduleOperations
import com.valerochka1337.valerochkagym.worker.WeeklyScheduleRecoveryScheduler
import java.io.IOException
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import retrofit2.HttpException

sealed interface WeeklyScheduleRecoveryResult {
  data object Completed : WeeklyScheduleRecoveryResult

  data object NothingPending : WeeklyScheduleRecoveryResult

  data class Retry(val message: String) : WeeklyScheduleRecoveryResult

  data class Paused(val message: String) : WeeklyScheduleRecoveryResult
}

interface WeeklyScheduleRepository {
  fun observe(): Flow<WeeklySchedule>

  suspend fun save(schedule: WeeklySchedule): ScheduleResult

  suspend fun clear(): ScheduleResult

  suspend fun resumePendingOperation(): WeeklyScheduleRecoveryResult
}

@Singleton
class WeeklyScheduleRepositoryImpl
@Inject
constructor(
    private val api: CalendarApi,
    private val googleAuth: AccountBoundGoogleAuth,
    private val routineDao: RoutineDao,
    private val dataStore: DataStore<Preferences>,
    @WeeklyScheduleOperations operationsDataStore: DataStore<Preferences>,
    private val json: Json,
    private val recoveryScheduler: WeeklyScheduleRecoveryScheduler,
) : WeeklyScheduleRepository {
  private val mutex = Mutex()
  private val journal = WeeklyScheduleOperationJournal(operationsDataStore, json)
  private val secureRandom = SecureRandom()

  override fun observe(): Flow<WeeklySchedule> =
      dataStore.data
          .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
          .map { preferences -> decodeSchedule(preferences[WEEKLY_SCHEDULE]) }

  override suspend fun save(schedule: WeeklySchedule): ScheduleResult = interactiveBoundary {
    if (schedule.rules.isEmpty()) return@interactiveBoundary clearLocked()
    finishPendingInteractive()?.let {
      return@interactiveBoundary it
    }

    val namedRules = mutableListOf<Pair<DayRule, String>>()
    for (rule in schedule.rules) {
      val name =
          routineDao.getRoutineName(rule.routineId)
              ?: return@interactiveBoundary ScheduleResult.Failure("Программа не найдена")
      namedRules += rule to name
    }

    val account = adoptLegacyOwnerOrReadActive()
    accountGate(account.active, account.currentEmail)?.let {
      return@interactiveBoundary it
    }
    val owner = normalizeEmail(account.active.ownerEmail).ifEmpty { account.currentEmail }
    val prepared =
        namedRules.map { (rule, name) ->
          val eventId = newEventId()
          val preparedRule = rule.copy(calendarEventId = eventId)
          PreparedCalendarEvent(
              eventId,
              preparedRule,
              recurringRequest(preparedRule, name, eventId),
          )
        }
    val target = WeeklySchedule(prepared.map { it.rule }, ownerEmail = owner)
    val operation =
        WeeklyScheduleOperation(
            kind = WeeklyScheduleOperationKind.REPLACE,
            phase = WeeklyScheduleOperationPhase.CREATE_NEW,
            accountEmail = owner,
            oldSchedule = account.active,
            targetSchedule = target,
            preparedEvents = prepared,
            pendingCreateIds = prepared.map { it.eventId },
            cleanupNewIds = emptyList(),
            pendingDeleteIds = account.active.eventIds(),
        )
    journal.write(operation)
    executionToScheduleResult(execute(operation, ExecutionOrigin.INTERACTIVE))
  }

  override suspend fun clear(): ScheduleResult = interactiveBoundary { clearLocked() }

  override suspend fun resumePendingOperation(): WeeklyScheduleRecoveryResult =
      mutex.withLock {
        try {
          when (val read = journal.read()) {
            WeeklyScheduleOperationRead.Absent -> WeeklyScheduleRecoveryResult.NothingPending
            is WeeklyScheduleOperationRead.Retryable ->
                WeeklyScheduleRecoveryResult.Retry(LOCAL_STATE_ERROR)
            is WeeklyScheduleOperationRead.Unreadable ->
                WeeklyScheduleRecoveryResult.Paused(JOURNAL_UNREADABLE)
            is WeeklyScheduleOperationRead.Present ->
                executionToRecoveryResult(
                    execute(read.operation, ExecutionOrigin.RECOVERY),
                )
          }
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (_: IOException) {
          WeeklyScheduleRecoveryResult.Retry(LOCAL_STATE_ERROR)
        } catch (_: Exception) {
          WeeklyScheduleRecoveryResult.Paused(LOCAL_STATE_ERROR)
        }
      }

  private suspend inline fun interactiveBoundary(
      crossinline action: suspend () -> ScheduleResult,
  ): ScheduleResult =
      try {
        mutex.withLock { action() }
      } catch (cancellation: CancellationException) {
        recoveryScheduler.enqueue()
        throw cancellation
      } catch (_: Exception) {
        ScheduleResult.Failure(LOCAL_STATE_ERROR)
      }

  private suspend fun clearLocked(): ScheduleResult {
    finishPendingInteractive()?.let {
      return it
    }
    val account = adoptLegacyOwnerOrReadActive()
    if (account.active.rules.isEmpty()) return ScheduleResult.Success
    accountGate(account.active, account.currentEmail)?.let {
      return it
    }
    val owner = normalizeEmail(account.active.ownerEmail).ifEmpty { account.currentEmail }
    val operation =
        WeeklyScheduleOperation(
            kind = WeeklyScheduleOperationKind.CLEAR,
            phase = WeeklyScheduleOperationPhase.DELETE_OLD,
            accountEmail = owner,
            oldSchedule = account.active,
            targetSchedule = WeeklySchedule(),
            preparedEvents = emptyList(),
            pendingCreateIds = emptyList(),
            cleanupNewIds = emptyList(),
            pendingDeleteIds = account.active.eventIds(),
        )
    journal.write(operation)
    return executionToScheduleResult(execute(operation, ExecutionOrigin.INTERACTIVE))
  }

  /** null means the previous journal was absent or completed and a new operation may start. */
  private suspend fun finishPendingInteractive(): ScheduleResult? =
      when (val read = journal.read()) {
        WeeklyScheduleOperationRead.Absent -> null
        is WeeklyScheduleOperationRead.Retryable ->
            ScheduleResult.Failure(LOCAL_STATE_ERROR).also { recoveryScheduler.enqueue() }
        is WeeklyScheduleOperationRead.Unreadable -> ScheduleResult.Failure(JOURNAL_UNREADABLE)
        is WeeklyScheduleOperationRead.Present ->
            when (val result = execute(read.operation, ExecutionOrigin.INTERACTIVE)) {
              Execution.Completed -> null
              Execution.Consent -> ScheduleResult.NeedsConsent
              is Execution.InsertFailed -> ScheduleResult.Failure(result.message)
              is Execution.Retry -> ScheduleResult.Failure(result.message)
              is Execution.Paused -> ScheduleResult.Failure(result.message)
            }
      }

  private suspend fun execute(
      initial: WeeklyScheduleOperation,
      origin: ExecutionOrigin,
  ): Execution {
    var operation = initial
    try {
      if (
          operation.phase == WeeklyScheduleOperationPhase.CLEANUP_NEW &&
              operation.cleanupNewIds.isEmpty()
      ) {
        journal.clear()
        return Execution.Completed
      }
      if (
          operation.phase == WeeklyScheduleOperationPhase.DELETE_OLD &&
              operation.pendingDeleteIds.isEmpty()
      ) {
        commitTargetAndClear(operation)
        return Execution.Completed
      }
      if (
          operation.phase == WeeklyScheduleOperationPhase.CREATE_NEW &&
              operation.pendingCreateIds.isEmpty()
      ) {
        operation =
            operation.copy(
                phase = WeeklyScheduleOperationPhase.DELETE_OLD,
                cleanupNewIds = emptyList(),
                pendingDeleteIds = operation.oldSchedule.eventIds(),
            )
        journal.write(operation)
        if (operation.pendingDeleteIds.isEmpty()) {
          commitTargetAndClear(operation)
          return Execution.Completed
        }
      }

      val expectedEmail = apiGate(operation) ?: return Execution.Paused(ACCOUNT_MISMATCH)
      val bearer =
          when (val token = googleAuth.getAccessTokenForAccount(expectedEmail)) {
            is TokenResult.Success -> "Bearer ${token.token}"
            TokenResult.NeedsConsent -> return Execution.Consent
            is TokenResult.Failed ->
                return Execution.Retry(GoogleErrorMessages.NO_CONNECTION).also {
                  enqueueFromInteractive(origin)
                }
          }

      if (operation.phase == WeeklyScheduleOperationPhase.CREATE_NEW) {
        for (eventId in operation.pendingCreateIds.toList()) {
          // Attempted is durable while the ID remains pending until 2xx/409 confirmation.
          operation = operation.copy(cleanupNewIds = (operation.cleanupNewIds + eventId).distinct())
          journal.write(operation)
          val prepared = operation.preparedEvents.first { it.eventId == eventId }
          try {
            api.insertEvent(bearer, prepared.request)
          } catch (error: HttpException) {
            if (error.code() != HTTP_CONFLICT) {
              return failInsertAndCleanup(
                  operation,
                  bearer,
                  insertFailureMessage(error.code()),
                  origin,
              )
            }
          } catch (_: IOException) {
            return failInsertAndCleanup(
                operation,
                bearer,
                INSERT_FAILED_OLD_PRESERVED,
                origin,
            )
          }
          operation = operation.copy(pendingCreateIds = operation.pendingCreateIds - eventId)
          journal.write(operation)
        }
        operation =
            operation.copy(
                phase = WeeklyScheduleOperationPhase.DELETE_OLD,
                cleanupNewIds = emptyList(),
                pendingDeleteIds = operation.oldSchedule.eventIds(),
            )
        journal.write(operation)
      }

      if (operation.phase == WeeklyScheduleOperationPhase.CLEANUP_NEW) {
        return cleanupNew(operation, bearer, origin)
      }

      for (eventId in operation.pendingDeleteIds.toList()) {
        when (val deletion = deleteOne(bearer, eventId)) {
          DeleteOutcome.Confirmed -> {
            operation = operation.copy(pendingDeleteIds = operation.pendingDeleteIds - eventId)
            journal.write(operation)
          }
          is DeleteOutcome.Paused -> return Execution.Paused(deletion.message)
          is DeleteOutcome.Retry ->
              return Execution.Retry(deletion.message).also { enqueueFromInteractive(origin) }
        }
      }
      commitTargetAndClear(operation)
      return Execution.Completed
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: IOException) {
      enqueueFromInteractive(origin)
      return Execution.Retry(GoogleErrorMessages.NO_NETWORK)
    }
  }

  private suspend fun failInsertAndCleanup(
      operation: WeeklyScheduleOperation,
      bearer: String,
      failure: String,
      origin: ExecutionOrigin,
  ): Execution {
    val cleanup =
        operation.copy(
            phase = WeeklyScheduleOperationPhase.CLEANUP_NEW,
            failureMessage = failure,
        )
    journal.write(cleanup)
    return when (val cleanupResult = cleanupNew(cleanup, bearer, origin)) {
      Execution.Completed -> Execution.InsertFailed(INSERT_FAILED_OLD_PRESERVED)
      is Execution.Retry -> cleanupResult
      is Execution.Paused -> Execution.Paused(INSERT_FAILED_CLEANUP_DEFERRED)
      else -> error("Unexpected cleanup result: $cleanupResult")
    }
  }

  private suspend fun cleanupNew(
      initial: WeeklyScheduleOperation,
      bearer: String,
      origin: ExecutionOrigin,
  ): Execution {
    var operation = initial
    for (eventId in operation.cleanupNewIds.toList()) {
      when (deleteOne(bearer, eventId)) {
        DeleteOutcome.Confirmed -> {
          operation = operation.copy(cleanupNewIds = operation.cleanupNewIds - eventId)
          journal.write(operation)
        }
        is DeleteOutcome.Paused -> {
          return Execution.Paused(CLEANUP_DEFERRED)
        }
        is DeleteOutcome.Retry -> {
          enqueueFromInteractive(origin)
          return Execution.Retry(CLEANUP_DEFERRED)
        }
      }
    }
    journal.clear()
    return Execution.Completed
  }

  private suspend fun deleteOne(bearer: String, eventId: String): DeleteOutcome =
      try {
        val response = api.deleteEvent(bearer, eventId)
        when {
          response.isSuccessful || response.code() in DELETE_CONFIRMED_CODES ->
              DeleteOutcome.Confirmed
          response.code() == 429 || response.code() >= 500 -> DeleteOutcome.Retry(DELETE_DEFERRED)
          else -> DeleteOutcome.Paused(deleteFailureMessage(response.code()))
        }
      } catch (error: HttpException) {
        when {
          error.code() in DELETE_CONFIRMED_CODES -> DeleteOutcome.Confirmed
          error.code() == 429 || error.code() >= 500 -> DeleteOutcome.Retry(DELETE_DEFERRED)
          else -> DeleteOutcome.Paused(deleteFailureMessage(error.code()))
        }
      } catch (_: IOException) {
        DeleteOutcome.Retry(DELETE_DEFERRED)
      }

  private suspend fun commitTargetAndClear(operation: WeeklyScheduleOperation) {
    persist(operation.targetSchedule)
    journal.clear()
  }

  private suspend fun apiGate(operation: WeeklyScheduleOperation): String? {
    val currentEmail = normalizeEmail(dataStore.data.first()[GOOGLE_EMAIL])
    return operation.accountEmail.takeIf { it.isNotEmpty() && it == currentEmail }
  }

  private suspend fun adoptLegacyOwnerOrReadActive(): ActiveAccount {
    lateinit var result: ActiveAccount
    dataStore.edit { preferences ->
      var active = decodeSchedule(preferences[WEEKLY_SCHEDULE])
      val currentEmail = normalizeEmail(preferences[GOOGLE_EMAIL])
      if (active.rules.isNotEmpty() && active.ownerEmail == null && currentEmail.isNotEmpty()) {
        active = active.copy(ownerEmail = currentEmail)
        preferences[WEEKLY_SCHEDULE] = json.encodeToString(active)
      }
      result = ActiveAccount(active, currentEmail)
    }
    return result
  }

  private fun accountGate(active: WeeklySchedule, currentEmail: String): ScheduleResult? {
    if (currentEmail.isEmpty()) return ScheduleResult.Failure(SIGN_IN_REQUIRED)
    val owner = normalizeEmail(active.ownerEmail)
    if (active.rules.isNotEmpty() && owner != currentEmail)
        return ScheduleResult.Failure(ACCOUNT_MISMATCH)
    return null
  }

  private fun recurringRequest(
      rule: DayRule,
      routineName: String,
      eventId: String,
  ): CalendarEventDto {
    val zone = ZoneId.systemDefault()
    val startMillis =
        nextOccurrenceMillis(rule.isoDay, rule.hour, rule.minute, zone, LocalDate.now(zone))
    val start = Instant.ofEpochMilli(startMillis).atZone(zone)
    return CalendarEventDto(
        id = eventId,
        summary = "Тренировка: $routineName",
        start = EventDateTimeDto(start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), zone.id),
        end =
            EventDateTimeDto(
                start
                    .plusSeconds(EVENT_DURATION_SECONDS)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                zone.id,
            ),
        reminders =
            EventRemindersDto(
                useDefault = false,
                overrides = listOf(EventReminderOverrideDto("popup", REMINDER_MINUTES)),
            ),
        recurrence = listOf("RRULE:FREQ=WEEKLY;BYDAY=${byDayOf(rule.isoDay)}"),
    )
  }

  private fun newEventId(): String =
      ByteArray(16).also(secureRandom::nextBytes).joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
      }

  private suspend fun persist(schedule: WeeklySchedule) {
    dataStore.edit { it[WEEKLY_SCHEDULE] = json.encodeToString(schedule) }
  }

  private fun decodeSchedule(raw: String?): WeeklySchedule =
      raw?.let { runCatching { json.decodeFromString<WeeklySchedule>(it) }.getOrNull() }
          ?: WeeklySchedule()

  private fun executionToRecoveryResult(execution: Execution): WeeklyScheduleRecoveryResult =
      when (execution) {
        Execution.Completed -> WeeklyScheduleRecoveryResult.Completed
        Execution.Consent -> WeeklyScheduleRecoveryResult.Paused(NEEDS_CONSENT)
        is Execution.InsertFailed -> WeeklyScheduleRecoveryResult.Paused(execution.message)
        is Execution.Paused -> WeeklyScheduleRecoveryResult.Paused(execution.message)
        is Execution.Retry -> WeeklyScheduleRecoveryResult.Retry(execution.message)
      }

  private fun executionToScheduleResult(execution: Execution): ScheduleResult =
      when (execution) {
        Execution.Completed -> ScheduleResult.Success
        Execution.Consent -> ScheduleResult.NeedsConsent
        is Execution.InsertFailed -> ScheduleResult.Failure(execution.message)
        is Execution.Paused -> ScheduleResult.Failure(execution.message)
        is Execution.Retry -> ScheduleResult.Failure(execution.message)
      }

  private fun enqueueFromInteractive(origin: ExecutionOrigin) {
    if (origin == ExecutionOrigin.INTERACTIVE) recoveryScheduler.enqueue()
  }

  private fun insertFailureMessage(code: Int): String =
      when (code) {
        401,
        403 -> NO_CALENDAR_ACCESS
        else -> "$INSERT_FAILED_OLD_PRESERVED (HTTP $code)"
      }

  private fun deleteFailureMessage(code: Int): String =
      when (code) {
        401,
        403 -> NO_CALENDAR_ACCESS
        else -> "Не удалось удалить старое расписание (HTTP $code)"
      }

  private data class ActiveAccount(val active: WeeklySchedule, val currentEmail: String)

  private sealed interface Execution {
    data object Completed : Execution

    data object Consent : Execution

    data class Retry(val message: String) : Execution

    data class Paused(val message: String) : Execution

    data class InsertFailed(val message: String) : Execution
  }

  private sealed interface DeleteOutcome {
    data object Confirmed : DeleteOutcome

    data class Retry(val message: String) : DeleteOutcome

    data class Paused(val message: String) : DeleteOutcome
  }

  private enum class ExecutionOrigin {
    INTERACTIVE,
    RECOVERY,
  }

  private companion object {
    val WEEKLY_SCHEDULE = stringPreferencesKey("weekly_schedule")
    val GOOGLE_EMAIL = stringPreferencesKey("google_email")
    val DELETE_CONFIRMED_CODES = setOf(404, 410)
    const val HTTP_CONFLICT = 409
    const val EVENT_DURATION_SECONDS = 60L * 60L
    const val REMINDER_MINUTES = 30
    const val NO_CALENDAR_ACCESS = "Нет доступа к календарю"
    const val SIGN_IN_REQUIRED = "Войдите в нужный Google-аккаунт"
    const val ACCOUNT_MISMATCH =
        "Выбран другой Google-аккаунт — переключитесь на владельца расписания"
    const val NEEDS_CONSENT = "Настройте доступ к Google в настройках"
    const val JOURNAL_UNREADABLE = "Не удалось прочитать незавершённую операцию расписания"
    const val INSERT_FAILED_OLD_PRESERVED =
        "Новое расписание не создано — старое расписание сохранено"
    const val INSERT_FAILED_CLEANUP_DEFERRED =
        "Старое расписание сохранено; очистка новых событий будет продолжена позже"
    const val CLEANUP_DEFERRED = "Очистка новых событий будет продолжена позже"
    const val DELETE_DEFERRED = "Удаление старого расписания будет продолжено позже"
    const val LOCAL_STATE_ERROR =
        "Не удалось сохранить состояние расписания — старое расписание сохранено"
  }
}
