package com.valerochka1337.valerochkagym.data.schedule

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.calendar.CalendarMigrationGate
import com.valerochka1337.valerochkagym.data.google.ScheduleResult
import com.valerochka1337.valerochkagym.di.WeeklyScheduleOperations
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

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

  /** CAL-01 has no legacy Calendar work that can safely be resumed for any account. */
  suspend fun hasRecoverableWorkForAccount(email: String): Boolean = false
}

/**
 * Read-only compatibility facade for the retired Google Calendar weekly schedule.
 *
 * The two legacy DataStore values remain available to [CalendarLegacyMigration] and the journal
 * codec remains intact for forensic reads, but CAL-01 never adopts an owner, calls Google, clears a
 * journal, or schedules recovery from this boundary. New calendar editing is Room-authoritative.
 */
@Singleton
class WeeklyScheduleRepositoryImpl
@Inject
constructor(
    private val dataStore: DataStore<Preferences>,
    @WeeklyScheduleOperations operationsDataStore: DataStore<Preferences>,
    json: Json,
    private val migrationGate: CalendarMigrationGate,
) : WeeklyScheduleRepository {
  // Keep the legacy journal's typed codec constructed with its original store. No public action
  // reads, writes, clears, or replays it after CAL-01.
  @Suppress("unused")
  private val quarantinedJournal = WeeklyScheduleOperationJournal(operationsDataStore, json)

  override fun observe(): Flow<WeeklySchedule> =
      dataStore.data
          .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
          .map { preferences -> decodeSchedule(preferences[WEEKLY_SCHEDULE]) }

  override suspend fun save(schedule: WeeklySchedule): ScheduleResult = unavailableResult()

  override suspend fun clear(): ScheduleResult = unavailableResult()

  override suspend fun resumePendingOperation(): WeeklyScheduleRecoveryResult =
      try {
        if (!migrationGate.ensureReady()) {
          WeeklyScheduleRecoveryResult.Paused(PREPARING_CALENDAR)
        } else {
          WeeklyScheduleRecoveryResult.Paused(LEGACY_JOURNAL_QUARANTINED)
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: IOException) {
        WeeklyScheduleRecoveryResult.Retry(LOCAL_STATE_ERROR)
      } catch (_: Exception) {
        WeeklyScheduleRecoveryResult.Paused(LOCAL_STATE_ERROR)
      }

  override suspend fun hasRecoverableWorkForAccount(email: String): Boolean = false

  private suspend fun unavailableResult(): ScheduleResult =
      try {
        if (!migrationGate.ensureReady()) ScheduleResult.Failure(PREPARING_CALENDAR)
        else ScheduleResult.Failure(LEGACY_JOURNAL_QUARANTINED)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        ScheduleResult.Failure(LOCAL_STATE_ERROR)
      }

  private fun decodeSchedule(raw: String?): WeeklySchedule =
      raw?.let { runCatching { codec.decodeFromString<WeeklySchedule>(it) }.getOrNull() }
          ?: WeeklySchedule()

  private companion object {
    val codec = Json { ignoreUnknownKeys = true }
    val WEEKLY_SCHEDULE = stringPreferencesKey("weekly_schedule")
    const val PREPARING_CALENDAR = "Подготовка календаря ещё не завершена"
    const val LEGACY_JOURNAL_QUARANTINED = "Старый журнал календаря изолирован"
    const val LOCAL_STATE_ERROR = "Не удалось прочитать состояние календаря"
  }
}
