package com.valerochka1337.valerochkagym.data

import android.app.Activity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.ScheduledWorkoutEntity
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.CalendarApi
import com.valerochka1337.valerochkagym.data.google.CalendarEventDto
import com.valerochka1337.valerochkagym.data.google.CalendarEventResponseDto
import com.valerochka1337.valerochkagym.data.google.CalendarRepositoryImpl
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.ScheduleResult
import com.valerochka1337.valerochkagym.data.google.TokenResult
import java.io.IOException
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Tests for [CalendarRepositoryImpl] over a real in-memory GymDatabase (via [RoomDaoTest]) so that
 * [ScheduledWorkoutEntity] rows are persisted and read back from genuine Room DAOs. Only the Google
 * side is faked: [FakeCalendarApi] captures the inserted event body, records deleted event ids and
 * exposes insert/delete failure knobs, while [FakeGoogleAuth] returns a programmable [TokenResult].
 */
class CalendarRepositoryTest : RoomDaoTest() {

  // region schedule success

  @Test
  fun `schedule sends the correct event body and persists the scheduled workout`() = runTest {
    val routineId = seedRoutine("Ноги")
    val api = FakeCalendarApi(eventId = "evt-123")

    val result = repository(api).schedule(routineId, START_MILLIS)

    assertEquals(ScheduleResult.Success, result)

    val body = api.insertedBodies.single()
    assertEquals("Тренировка: Ноги", body.summary)
    assertFalse(body.reminders.useDefault)
    val reminder = body.reminders.overrides.single()
    assertEquals("popup", reminder.method)
    assertEquals(30, reminder.minutes)

    // start dateTime is ISO-8601 with an offset and denotes exactly the scheduled instant.
    val start = OffsetDateTime.parse(body.start.dateTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    assertEquals(START_MILLIS, start.toInstant().toEpochMilli())
    // end is exactly one hour after start.
    val end = OffsetDateTime.parse(body.end.dateTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    assertEquals(3600L, Duration.between(start, end).seconds)

    val persisted = db.scheduledWorkoutDao().getById(1L)!!
    assertEquals("evt-123", persisted.calendarEventId)
    assertEquals(START_MILLIS, persisted.dateTimeMillis)
    assertEquals(routineId, persisted.routineId)
  }

  // endregion

  // region schedule token classification

  @Test
  fun `schedule with NeedsConsent token returns NeedsConsent without touching API or DB`() =
      runTest {
        val routineId = seedRoutine("Ноги")
        val api = FakeCalendarApi()

        val result =
            repository(api, FakeGoogleAuth(TokenResult.NeedsConsent))
                .schedule(routineId, START_MILLIS)

        assertEquals(ScheduleResult.NeedsConsent, result)
        assertTrue(api.insertedBodies.isEmpty())
        assertEquals(0, tableCount("scheduled_workouts"))
      }

  @Test
  fun `schedule with Failed token returns connection failure without local write`() = runTest {
    val routineId = seedRoutine("Ноги")
    val api = FakeCalendarApi()

    val result =
        repository(api, FakeGoogleAuth(TokenResult.Failed(IOException("no network"))))
            .schedule(routineId, START_MILLIS)

    assertEquals(ScheduleResult.Failure("Нет соединения с Google — попробуйте ещё раз"), result)
    assertTrue(api.insertedBodies.isEmpty())
    assertEquals(0, tableCount("scheduled_workouts"))
  }

  // endregion

  // region schedule HTTP classification

  @Test
  fun `schedule insert 403 is a calendar access failure with no local write`() = runTest {
    val routineId = seedRoutine("Ноги")
    val api = FakeCalendarApi(failInsert = httpException(403))

    val result = repository(api).schedule(routineId, START_MILLIS)

    assertEquals(ScheduleResult.Failure("Нет доступа к календарю"), result)
    assertEquals(0, tableCount("scheduled_workouts"))
  }

  @Test
  fun `schedule insert 500 is a failure carrying the code with no local write`() = runTest {
    val routineId = seedRoutine("Ноги")
    val api = FakeCalendarApi(failInsert = httpException(500))

    val result = repository(api).schedule(routineId, START_MILLIS)

    assertEquals(ScheduleResult.Failure("Не удалось создать событие (HTTP 500)"), result)
    assertEquals(0, tableCount("scheduled_workouts"))
  }

  @Test
  fun `schedule insert IOException is a no-network failure with no local write`() = runTest {
    val routineId = seedRoutine("Ноги")
    val api = FakeCalendarApi(failInsert = IOException("timeout"))

    val result = repository(api).schedule(routineId, START_MILLIS)

    assertEquals(ScheduleResult.Failure("Нет сети"), result)
    assertEquals(0, tableCount("scheduled_workouts"))
  }

  // endregion

  // region schedule missing routine

  @Test
  fun `schedule for a missing routine fails before any API call`() = runTest {
    val api = FakeCalendarApi()

    val result = repository(api).schedule(routineId = 999L, dateTimeMillis = START_MILLIS)

    assertEquals(ScheduleResult.Failure("Программа не найдена"), result)
    assertTrue(api.insertedBodies.isEmpty())
    assertEquals(0, tableCount("scheduled_workouts"))
  }

  // endregion

  // region cancel

  @Test
  fun `cancel with no local record succeeds without calling the API`() = runTest {
    val api = FakeCalendarApi()

    val result = repository(api).cancel(scheduledId = 777L)

    assertEquals(ScheduleResult.Success, result)
    assertTrue(api.deletedEventIds.isEmpty())
  }

  @Test
  fun `cancel success deletes the event and removes the local record`() = runTest {
    val scheduledId = seedScheduled("evt-abc")
    val api = FakeCalendarApi()

    val result = repository(api).cancel(scheduledId)

    assertEquals(ScheduleResult.Success, result)
    assertEquals(listOf("evt-abc"), api.deletedEventIds)
    assertNull(db.scheduledWorkoutDao().getById(scheduledId))
  }

  @Test
  fun `cancel treats a 404 delete response as success and removes the record`() = runTest {
    val scheduledId = seedScheduled("evt-abc")
    val api = FakeCalendarApi(deleteCode = 404)

    val result = repository(api).cancel(scheduledId)

    assertEquals(ScheduleResult.Success, result)
    assertNull(db.scheduledWorkoutDao().getById(scheduledId))
  }

  @Test
  fun `cancel treats a thrown 404 HttpException as success and removes the record`() = runTest {
    val scheduledId = seedScheduled("evt-abc")
    val api = FakeCalendarApi(failDelete = httpException(404))

    val result = repository(api).cancel(scheduledId)

    assertEquals(ScheduleResult.Success, result)
    assertNull(db.scheduledWorkoutDao().getById(scheduledId))
  }

  @Test
  fun `cancel treats a 410 delete response as success and removes the record`() = runTest {
    val scheduledId = seedScheduled("evt-abc")
    val api = FakeCalendarApi(deleteCode = 410)

    val result = repository(api).cancel(scheduledId)

    assertEquals(ScheduleResult.Success, result)
    assertNull(db.scheduledWorkoutDao().getById(scheduledId))
  }

  @Test
  fun `cancel with a 500 delete response fails and retains the local record`() = runTest {
    val scheduledId = seedScheduled("evt-abc")
    val api = FakeCalendarApi(deleteCode = 500)

    val result = repository(api).cancel(scheduledId)

    assertEquals(ScheduleResult.Failure("Не удалось удалить событие (HTTP 500)"), result)
    assertEquals(scheduledId, db.scheduledWorkoutDao().getById(scheduledId)?.id)
  }

  @Test
  fun `cancel with NeedsConsent token returns NeedsConsent and retains the record`() = runTest {
    val scheduledId = seedScheduled("evt-abc")
    val api = FakeCalendarApi()

    val result = repository(api, FakeGoogleAuth(TokenResult.NeedsConsent)).cancel(scheduledId)

    assertEquals(ScheduleResult.NeedsConsent, result)
    assertTrue(api.deletedEventIds.isEmpty())
    assertEquals(scheduledId, db.scheduledWorkoutDao().getById(scheduledId)?.id)
  }

  // endregion

  // region helpers

  private fun repository(
      api: FakeCalendarApi,
      auth: GoogleAuth = FakeGoogleAuth(TokenResult.Success("token")),
  ): CalendarRepositoryImpl =
      CalendarRepositoryImpl(api, auth, db.routineDao(), db.scheduledWorkoutDao())

  private suspend fun seedRoutine(name: String): Long =
      db.routineDao().upsertRoutine(RoutineEntity(name = name))

  private suspend fun seedScheduled(eventId: String, routineName: String = "Ноги"): Long {
    val routineId = seedRoutine(routineName)
    return db.scheduledWorkoutDao()
        .insert(
            ScheduledWorkoutEntity(
                routineId = routineId,
                dateTimeMillis = START_MILLIS,
                calendarEventId = eventId,
            ),
        )
  }

  private fun httpException(code: Int): HttpException =
      HttpException(Response.error<Unit>(code, "".toResponseBody()))

  /**
   * In-memory [CalendarApi]. [insertedBodies] captures each event body passed to [insertEvent]; on
   * success the event id returned is [eventId]. [failInsert], when set, is thrown from
   * [insertEvent]. [deletedEventIds] records every id passed to [deleteEvent]; [failDelete], when
   * set, is thrown from it, otherwise the response is a success unless [deleteCode] selects an
   * error code via [Response.error].
   */
  private class FakeCalendarApi(
      private val eventId: String = "event-id",
      private val failInsert: Exception? = null,
      private val failDelete: Exception? = null,
      private val deleteCode: Int? = null,
  ) : CalendarApi {

    val insertedBodies: MutableList<CalendarEventDto> = mutableListOf()
    val deletedEventIds: MutableList<String> = mutableListOf()

    override suspend fun insertEvent(
        bearer: String,
        body: CalendarEventDto,
    ): CalendarEventResponseDto {
      failInsert?.let { throw it }
      insertedBodies.add(body)
      return CalendarEventResponseDto(id = eventId)
    }

    override suspend fun deleteEvent(bearer: String, eventId: String): Response<Unit> {
      failDelete?.let { throw it }
      deletedEventIds.add(eventId)
      return if (deleteCode == null) Response.success(Unit)
      else Response.error(deleteCode, "".toResponseBody())
    }
  }

  /** [GoogleAuth] whose only relevant method returns the configured [token]. */
  private class FakeGoogleAuth(private val token: TokenResult) : GoogleAuth {
    override suspend fun signIn(activity: Activity): Result<String> =
        Result.success("user@example.com")

    override suspend fun authorize(activity: Activity): AuthorizeOutcome = AuthorizeOutcome.Granted

    override suspend fun getAccessToken(): TokenResult = token

    override suspend fun signOut() = Unit
  }

  // endregion

  private companion object {
    const val START_MILLIS = 1_700_000_000_000L
  }
}
