package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.db.entity.CalendarEventAccountLinkEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarEventAccountLinkState
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.ScheduledWorkoutEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CalendarEventAccountLinkDaoTest : RoomDaoTest() {
  @Test
  fun `owner link is immutable and follows scheduled workout deletion`() = runTest {
    val routineId = db.routineDao().upsertRoutine(RoutineEntity(name = "Ноги"))
    val scheduledId =
        db.scheduledWorkoutDao()
            .insert(
                ScheduledWorkoutEntity(
                    routineId = routineId,
                    dateTimeMillis = 1,
                    calendarEventId = "e",
                )
            )
    val dao = db.calendarEventAccountLinkDao()
    dao.insert(
        CalendarEventAccountLinkEntity(
            scheduledId,
            "a@example.com",
            CalendarEventAccountLinkState.OWNED,
        )
    )

    assertEquals("a@example.com", dao.getByScheduledId(scheduledId)?.ownerEmail)
    try {
      dao.insert(
          CalendarEventAccountLinkEntity(
              scheduledId,
              "b@example.com",
              CalendarEventAccountLinkState.OWNED,
          )
      )
      fail("duplicate owner link must abort")
    } catch (_: android.database.sqlite.SQLiteConstraintException) {
      assertEquals("a@example.com", dao.getByScheduledId(scheduledId)?.ownerEmail)
    }
    db.scheduledWorkoutDao().delete(scheduledId)
    assertEquals(null, dao.getByScheduledId(scheduledId))
  }

  @Test
  fun `database rejects malformed ownership and raw owner replacement`() = runTest {
    val routineId = db.routineDao().upsertRoutine(RoutineEntity(name = "Ноги"))
    val first =
        db.scheduledWorkoutDao()
            .insert(
                ScheduledWorkoutEntity(
                    routineId = routineId,
                    dateTimeMillis = 1,
                    calendarEventId = "first",
                )
            )
    val second =
        db.scheduledWorkoutDao()
            .insert(
                ScheduledWorkoutEntity(
                    routineId = routineId,
                    dateTimeMillis = 2,
                    calendarEventId = "second",
                )
            )
    val raw = db.openHelper.writableDatabase

    assertFailure {
      raw.execSQL(
          "INSERT INTO calendar_event_account_links(scheduledWorkoutId,ownerEmail,state) " +
              "VALUES($first,' Owner@Example.COM ','OWNED')"
      )
    }
    db.calendarEventAccountLinkDao()
        .insert(
            CalendarEventAccountLinkEntity(
                second,
                "owner@example.com",
                CalendarEventAccountLinkState.OWNED,
            )
        )
    assertFailure {
      raw.execSQL(
          "UPDATE calendar_event_account_links SET ownerEmail='other@example.com' " +
              "WHERE scheduledWorkoutId=$second"
      )
    }
    assertEquals(
        "owner@example.com",
        db.calendarEventAccountLinkDao().getByScheduledId(second)?.ownerEmail,
    )
  }

  private inline fun assertFailure(block: () -> Unit) {
    try {
      block()
      fail("database write must be rejected")
    } catch (_: android.database.sqlite.SQLiteException) {
      // Expected database invariant violation.
    }
  }
}
