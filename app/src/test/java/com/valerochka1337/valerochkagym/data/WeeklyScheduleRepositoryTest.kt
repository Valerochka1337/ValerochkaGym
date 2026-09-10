package com.valerochka1337.valerochkagym.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.calendar.CalendarMigrationGate
import com.valerochka1337.valerochkagym.data.google.ScheduleResult
import com.valerochka1337.valerochkagym.data.schedule.DayRule
import com.valerochka1337.valerochkagym.data.schedule.WeeklySchedule
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRecoveryResult
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRepositoryImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyScheduleRepositoryTest {
  private val json = Json { encodeDefaults = true }

  @Test
  fun `observing a known legacy owner preserves its raw weekly source`() = runTest {
    val schedule = WeeklySchedule(listOf(DayRule(1, 7L, 18, 0, "event")), "owner@example.com")
    val settings = FakeDataStore(schedule = json.encodeToString(schedule))

    val repository = repository(settings, FakeDataStore(), ReadyGate)

    assertEquals(schedule, repository.observe().first())
    assertEquals(json.encodeToString(schedule), settings.value(WEEKLY_SCHEDULE))
    assertEquals(0, settings.updateCalls)
  }

  @Test
  fun `observing a null legacy owner does not adopt the connected account`() = runTest {
    val schedule = WeeklySchedule(listOf(DayRule(2, 8L, 7, 30, "event")), ownerEmail = null)
    val raw = json.encodeToString(schedule)
    val settings = FakeDataStore(schedule = raw, connectedEmail = "current@example.com")

    val observed = repository(settings, FakeDataStore(), ReadyGate).observe().first()

    assertEquals(schedule, observed)
    assertEquals(raw, settings.value(WEEKLY_SCHEDULE))
    assertEquals("current@example.com", settings.value(CONNECTED_EMAIL))
    assertEquals(0, settings.updateCalls)
  }

  @Test
  fun `pending migration pauses legacy actions without changing either source`() = runTest {
    val settings = FakeDataStore(schedule = "{malformed")
    val operations = FakeDataStore(journal = "legacy-journal")
    val repository = repository(settings, operations, PendingGate)

    assertEquals(
        ScheduleResult.Failure("Подготовка календаря ещё не завершена"),
        repository.save(WeeklySchedule()),
    )
    assertEquals(
        WeeklyScheduleRecoveryResult.Paused("Подготовка календаря ещё не завершена"),
        repository.resumePendingOperation(),
    )
    assertEquals("{malformed", settings.value(WEEKLY_SCHEDULE))
    assertEquals("legacy-journal", operations.value(PENDING_OPERATION))
    assertEquals(0, settings.updateCalls)
    assertEquals(0, operations.updateCalls)
  }

  @Test
  fun `ready migration quarantines every legacy action and retains the journal`() = runTest {
    val settings = FakeDataStore(schedule = json.encodeToString(WeeklySchedule()))
    val operations = FakeDataStore(journal = "legacy-journal")
    val repository = repository(settings, operations, ReadyGate)

    assertEquals(
        ScheduleResult.Failure("Старый журнал календаря изолирован"),
        repository.clear(),
    )
    assertEquals(
        WeeklyScheduleRecoveryResult.Paused("Старый журнал календаря изолирован"),
        repository.resumePendingOperation(),
    )
    assertEquals("legacy-journal", operations.value(PENDING_OPERATION))
    assertEquals(0, settings.updateCalls)
    assertEquals(0, operations.updateCalls)
  }

  @Test
  fun `legacy sources never request account recovery`() = runTest {
    val settings =
        FakeDataStore(
            schedule = json.encodeToString(WeeklySchedule(ownerEmail = "owner@example.com")),
        )
    val repository = repository(settings, FakeDataStore(journal = "legacy-journal"), ReadyGate)

    assertFalse(repository.hasRecoverableWorkForAccount("owner@example.com"))
    assertFalse(repository.hasRecoverableWorkForAccount("other@example.com"))
    assertTrue(settings.updateCalls == 0)
  }

  private fun repository(
      settings: FakeDataStore,
      operations: FakeDataStore,
      gate: CalendarMigrationGate,
  ) = WeeklyScheduleRepositoryImpl(settings, operations, json, gate)

  private class FakeDataStore(
      schedule: String? = null,
      journal: String? = null,
      connectedEmail: String? = null,
  ) : DataStore<Preferences> {
    private val state =
        MutableStateFlow<Preferences>(
            mutablePreferencesOf().apply {
              schedule?.let { this[WEEKLY_SCHEDULE] = it }
              journal?.let { this[PENDING_OPERATION] = it }
              connectedEmail?.let { this[CONNECTED_EMAIL] = it }
            }
        )
    var updateCalls = 0
      private set

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
      updateCalls++
      return transform(state.value).also { state.value = it }
    }

    fun value(key: Preferences.Key<String>): String? = state.value[key]
  }

  private object ReadyGate : CalendarMigrationGate {
    override suspend fun ensureReady(): Boolean = true
  }

  private object PendingGate : CalendarMigrationGate {
    override suspend fun ensureReady(): Boolean = false
  }

  private companion object {
    val WEEKLY_SCHEDULE = stringPreferencesKey("weekly_schedule")
    val PENDING_OPERATION = stringPreferencesKey("pending_operation")
    val CONNECTED_EMAIL = stringPreferencesKey("connected_calendar_email")
  }
}
