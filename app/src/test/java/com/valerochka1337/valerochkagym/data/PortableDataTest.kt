package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.backend.PortableData
import com.valerochka1337.valerochkagym.data.db.entity.CalendarEventAccountLinkEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarEventAccountLinkState
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.ScheduledWorkoutEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test

class PortableDataTest : RoomDaoTest() {
  @Test
  fun `calendar owner links remain device local and absent from wire snapshots`() = runTest {
    val routineId = db.routineDao().upsertRoutine(RoutineEntity(name = "Ноги"))
    val scheduledId =
        db.scheduledWorkoutDao()
            .insert(
                ScheduledWorkoutEntity(
                    routineId = routineId,
                    dateTimeMillis = 1,
                    calendarEventId = "event",
                )
            )
    db.calendarEventAccountLinkDao()
        .insert(
            CalendarEventAccountLinkEntity(
                scheduledId,
                "private-owner@example.com",
                CalendarEventAccountLinkState.OWNED,
            )
        )

    val wire =
        PortableData(db.openHelper.writableDatabase).snapshot(includeStandard = true).toString()

    assertFalse(wire.contains("private-owner@example.com"))
    assertFalse(wire.contains("calendar_event_account_links"))
  }
}
