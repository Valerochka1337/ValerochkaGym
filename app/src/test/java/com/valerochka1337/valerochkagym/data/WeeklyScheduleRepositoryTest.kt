package com.valerochka1337.valerochkagym.data

import android.app.Activity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.CalendarApi
import com.valerochka1337.valerochkagym.data.google.CalendarEventDto
import com.valerochka1337.valerochkagym.data.google.CalendarEventResponseDto
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.ScheduleResult
import com.valerochka1337.valerochkagym.data.google.TokenResult
import com.valerochka1337.valerochkagym.data.schedule.DayRule
import com.valerochka1337.valerochkagym.data.schedule.WeeklySchedule
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRepositoryImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Tests for [WeeklyScheduleRepositoryImpl] over a real in-memory [GymDatabase] (via [RoomDaoTest]) for
 * a genuine [com.valerochka1337.valerochkagym.data.db.dao.RoutineDao], with the Google side faked
 * ([FakeCalendarApi], [FakeGoogleAuth]) and an in-memory [FakeDataStore] persisting the template.
 */
class WeeklyScheduleRepositoryTest : RoomDaoTest() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scheduleKey = stringPreferencesKey("weekly_schedule")

    @Test
    fun `save creates one RRULE event per rule and persists the returned ids`() = runTest {
        val legs = seedRoutine("Ноги")
        val chest = seedRoutine("Грудь")
        val api = FakeCalendarApi(eventIds = listOf("evt-mon", "evt-wed"))
        val store = FakeDataStore()

        val result = repository(api, store).save(
            WeeklySchedule(
                listOf(
                    DayRule(isoDay = 1, routineId = legs, hour = 18, minute = 30),
                    DayRule(isoDay = 3, routineId = chest, hour = 9, minute = 0),
                ),
            ),
        )

        assertEquals(ScheduleResult.Success, result)
        assertEquals(2, api.insertedBodies.size)

        val monday = api.insertedBodies[0]
        assertEquals("Тренировка: Ноги", monday.summary)
        assertEquals(listOf("RRULE:FREQ=WEEKLY;BYDAY=MO"), monday.recurrence)
        // RRULE-события обязаны нести timeZone, иначе Google отвечает 400.
        assertEquals(java.time.ZoneId.systemDefault().id, monday.start.timeZone)
        assertEquals(java.time.ZoneId.systemDefault().id, monday.end.timeZone)
        assertEquals("popup", monday.reminders.overrides.single().method)
        assertEquals(30, monday.reminders.overrides.single().minutes)
        val start = OffsetDateTime.parse(monday.start.dateTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val end = OffsetDateTime.parse(monday.end.dateTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        assertEquals(3600L, Duration.between(start, end).seconds)
        assertEquals(1, start.dayOfWeek.value) // Monday
        assertEquals(18, start.hour)
        assertEquals(30, start.minute)

        assertEquals(listOf("RRULE:FREQ=WEEKLY;BYDAY=WE"), api.insertedBodies[1].recurrence)

        val persisted = repository(api, store).observe().first()
        assertEquals(listOf("evt-mon", "evt-wed"), persisted.rules.map { it.calendarEventId })
    }

    @Test
    fun `save with NeedsConsent leaves the API and stored template untouched`() = runTest {
        val legs = seedRoutine("Ноги")
        val api = FakeCalendarApi(eventIds = listOf("evt-new"))
        val existing = WeeklySchedule(listOf(DayRule(isoDay = 2, routineId = legs, hour = 7, minute = 0, calendarEventId = "old")))
        val store = FakeDataStore(existing)

        val result = repository(api, store, FakeGoogleAuth(TokenResult.NeedsConsent))
            .save(WeeklySchedule(listOf(DayRule(isoDay = 1, routineId = legs, hour = 18, minute = 0))))

        assertEquals(ScheduleResult.NeedsConsent, result)
        assertTrue(api.insertedBodies.isEmpty())
        assertTrue(api.deletedEventIds.isEmpty())
        assertEquals(existing, repository(api, store).observe().first())
    }

    @Test
    fun `save deletes the old series then fails mid-loop keeping only what was created`() = runTest {
        val legs = seedRoutine("Ноги")
        val chest = seedRoutine("Грудь")
        val existing = WeeklySchedule(listOf(DayRule(isoDay = 5, routineId = legs, hour = 8, minute = 0, calendarEventId = "old-1")))
        val store = FakeDataStore(existing)
        // First insert succeeds, second throws 500.
        val api = FakeCalendarApi(eventIds = listOf("evt-mon"), failInsertOnCall = 1)

        val result = repository(api, store).save(
            WeeklySchedule(
                listOf(
                    DayRule(isoDay = 1, routineId = legs, hour = 18, minute = 0),
                    DayRule(isoDay = 3, routineId = chest, hour = 9, minute = 0),
                ),
            ),
        )

        assertEquals(ScheduleResult.Failure("Не удалось создать событие (HTTP 500)"), result)
        assertEquals(listOf("old-1"), api.deletedEventIds) // old series removed
        val persisted = repository(api, store).observe().first()
        assertEquals(1, persisted.rules.size)
        assertEquals("evt-mon", persisted.rules.single().calendarEventId)
    }

    @Test
    fun `clear deletes all persisted series and empties the template`() = runTest {
        val legs = seedRoutine("Ноги")
        val existing = WeeklySchedule(
            listOf(
                DayRule(isoDay = 1, routineId = legs, hour = 18, minute = 0, calendarEventId = "e1"),
                DayRule(isoDay = 4, routineId = legs, hour = 19, minute = 0, calendarEventId = "e2"),
            ),
        )
        val store = FakeDataStore(existing)
        val api = FakeCalendarApi()

        val result = repository(api, store).clear()

        assertEquals(ScheduleResult.Success, result)
        assertEquals(listOf("e1", "e2"), api.deletedEventIds)
        assertTrue(repository(api, store).observe().first().rules.isEmpty())
    }

    @Test
    fun `observe reflects the persisted template`() = runTest {
        val legs = seedRoutine("Ноги")
        val schedule = WeeklySchedule(listOf(DayRule(isoDay = 6, routineId = legs, hour = 10, minute = 15, calendarEventId = "e")))
        val store = FakeDataStore(schedule)

        assertEquals(schedule, repository(FakeCalendarApi(), store).observe().first())
    }

    @Test
    fun `clear on an empty template succeeds without touching the API`() = runTest {
        val api = FakeCalendarApi()
        val store = FakeDataStore()

        val result = repository(api, store).clear()

        assertEquals(ScheduleResult.Success, result)
        assertTrue(api.deletedEventIds.isEmpty())
    }

    // region helpers

    private fun repository(
        api: FakeCalendarApi,
        store: FakeDataStore,
        auth: GoogleAuth = FakeGoogleAuth(TokenResult.Success("token")),
    ) = WeeklyScheduleRepositoryImpl(api, auth, db.routineDao(), store, json)

    private suspend fun seedRoutine(name: String): Long =
        db.routineDao().upsertRoutine(RoutineEntity(name = name))

    private fun httpException(code: Int): HttpException =
        HttpException(Response.error<Unit>(code, "".toResponseBody()))

    private inner class FakeCalendarApi(
        private val eventIds: List<String> = emptyList(),
        private val failInsertOnCall: Int? = null,
        private val failDelete: Exception? = null,
        private val deleteCode: Int? = null,
    ) : CalendarApi {

        val insertedBodies: MutableList<CalendarEventDto> = mutableListOf()
        val deletedEventIds: MutableList<String> = mutableListOf()

        override suspend fun insertEvent(bearer: String, body: CalendarEventDto): CalendarEventResponseDto {
            val call = insertedBodies.size
            if (failInsertOnCall == call) throw httpException(500)
            insertedBodies.add(body)
            val id = eventIds.getOrNull(call) ?: "event-$call"
            return CalendarEventResponseDto(id = id)
        }

        override suspend fun deleteEvent(bearer: String, eventId: String): Response<Unit> {
            failDelete?.let { throw it }
            deletedEventIds.add(eventId)
            return if (deleteCode == null) Response.success(Unit) else Response.error(deleteCode, "".toResponseBody())
        }
    }

    private class FakeGoogleAuth(private val token: TokenResult) : GoogleAuth {
        override suspend fun signIn(activity: Activity): Result<String> = Result.success("user@example.com")
        override suspend fun authorize(activity: Activity): AuthorizeOutcome = AuthorizeOutcome.Granted
        override suspend fun getAccessToken(): TokenResult = token
        override suspend fun signOut() = Unit
    }

    private inner class FakeDataStore(initial: WeeklySchedule? = null) : DataStore<Preferences> {
        private val state = MutableStateFlow(
            if (initial == null) emptyPreferences()
            else mutablePreferencesOf(scheduleKey to json.encodeToString(initial)),
        )
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            state.value = transform(state.value)
            return state.value
        }
    }

    // endregion
}
