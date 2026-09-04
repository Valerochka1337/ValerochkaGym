package com.valerochka1337.valerochkagym.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.google.CalendarEventDto
import com.valerochka1337.valerochkagym.data.google.EventDateTimeDto
import com.valerochka1337.valerochkagym.data.google.EventRemindersDto
import com.valerochka1337.valerochkagym.data.schedule.DayRule
import com.valerochka1337.valerochkagym.data.schedule.PreparedCalendarEvent
import com.valerochka1337.valerochkagym.data.schedule.WeeklySchedule
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleOperation
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleOperationJournal
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleOperationKind
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleOperationPhase
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleOperationRead
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyScheduleOperationTest {
  private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  @Test
  fun `old weekly schedule json decodes with no owner`() {
    val schedule =
        json.decodeFromString<WeeklySchedule>(
            """{"rules":[{"isoDay":1,"routineId":2,"hour":18,"minute":0}]}""",
        )
    assertEquals(null, schedule.ownerEmail)
  }

  @Test
  fun `journal round trip preserves attempted pending create checkpoint`() = runTest {
    val store = FakeDataStore()
    val journal = WeeklyScheduleOperationJournal(store, json)
    val operation = operation()

    journal.write(operation)

    assertEquals(WeeklyScheduleOperationRead.Present(operation), journal.read())
  }

  @Test
  fun `malformed journal is unreadable rather than absent`() = runTest {
    val store = FakeDataStore(mutablePreferencesOf(PENDING to "not-json"))
    val read = WeeklyScheduleOperationJournal(store, json).read()
    assertTrue(read is WeeklyScheduleOperationRead.Unreadable)
  }

  @Test
  fun `missing journal is typed absent`() = runTest {
    assertEquals(
        WeeklyScheduleOperationRead.Absent,
        WeeklyScheduleOperationJournal(FakeDataStore(), json).read(),
    )
  }

  @Test
  fun `journal datastore io is typed retryable`() = runTest {
    val store =
        object : DataStore<Preferences> {
          override val data: Flow<Preferences> = flow { throw IOException("read failed") }

          override suspend fun updateData(
              transform: suspend (Preferences) -> Preferences,
          ): Preferences = throw IOException("write failed")
        }

    assertTrue(
        WeeklyScheduleOperationJournal(store, json).read() is WeeklyScheduleOperationRead.Retryable
    )
  }

  private fun operation(): WeeklyScheduleOperation {
    val id = "0123456789abcdef0123456789abcdef"
    val rule = DayRule(1, 2, 18, 0, id)
    val request =
        CalendarEventDto(
            id = id,
            summary = "Тренировка: Ноги",
            start = EventDateTimeDto("2026-09-07T18:00:00+03:00", "Europe/Moscow"),
            end = EventDateTimeDto("2026-09-07T19:00:00+03:00", "Europe/Moscow"),
            reminders = EventRemindersDto(false, emptyList()),
            recurrence = listOf("RRULE:FREQ=WEEKLY;BYDAY=MO"),
        )
    return WeeklyScheduleOperation(
        kind = WeeklyScheduleOperationKind.REPLACE,
        phase = WeeklyScheduleOperationPhase.CREATE_NEW,
        accountEmail = "owner@example.com",
        oldSchedule = WeeklySchedule(),
        targetSchedule = WeeklySchedule(listOf(rule), "owner@example.com"),
        preparedEvents = listOf(PreparedCalendarEvent(id, rule, request)),
        pendingCreateIds = listOf(id),
        cleanupNewIds = listOf(id),
        pendingDeleteIds = emptyList(),
    )
  }

  private class FakeDataStore(
      initial: Preferences = mutablePreferencesOf(),
  ) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
      state.value = transform(state.value)
      return state.value
    }
  }

  private companion object {
    val PENDING = stringPreferencesKey("pending_operation")
  }
}
