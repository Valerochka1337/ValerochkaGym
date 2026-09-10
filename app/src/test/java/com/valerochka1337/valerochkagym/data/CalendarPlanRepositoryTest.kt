package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.backend.SyncSchema
import com.valerochka1337.valerochkagym.data.calendar.CalendarMigrationGate
import com.valerochka1337.valerochkagym.data.calendar.CalendarPlanResult
import com.valerochka1337.valerochkagym.data.calendar.RoomCalendarPlanRepository
import com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionKind
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.schedule.DayRule
import com.valerochka1337.valerochkagym.data.schedule.WeeklySchedule
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarPlanRepositoryTest : RoomDaoTest() {
  private val zone = ZoneId.of("UTC")

  @Test
  fun `replayed plan command keeps one Room row and rejects a different payload`() = runTest {
    val routine = db.routineDao().upsertRoutine(RoutineEntity(syncId = "routine-a", name = "Ноги"))
    val repository = repository()
    val at = LocalDate.of(2026, 9, 10).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()

    assertTrue(
        repository.createPlan("11111111-1111-4111-8111-111111111111", routine, at, zone)
            is CalendarPlanResult.Success
    )
    assertTrue(
        repository.createPlan("11111111-1111-4111-8111-111111111111", routine, at, zone)
            is CalendarPlanResult.Success
    )
    assertTrue(
        repository.createPlan("11111111-1111-4111-8111-111111111111", routine, at + 60_000, zone)
            is CalendarPlanResult.Failure
    )
    assertEquals(1, db.calendarPlanDao().planCount())
  }

  @Test
  fun `invalid calendar instant becomes a recoverable repository failure`() = runTest {
    val routine = db.routineDao().upsertRoutine(RoutineEntity(syncId = "routine-a", name = "Ноги"))
    val beforeBounds =
        LocalDate.of(1969, 12, 31).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    assertTrue(
        repository().createPlan("22222222-2222-4222-8222-222222222222", routine, beforeBounds, zone)
            is CalendarPlanResult.Failure
    )
    assertEquals(0, db.calendarPlanDao().planCount())
  }

  @Test
  fun `owner transition during migration wait rejects a stale routine write`() = runTest {
    SyncSchema.install(db.openHelper.writableDatabase)
    db.openHelper.writableDatabase.execSQL(
        "UPDATE backend_state SET owner='owner-a',generation=1 WHERE id=1"
    )
    val routine = db.routineDao().upsertRoutine(RoutineEntity(syncId = "routine-a", name = "Ноги"))
    val gate = WaitingGate()
    val repository =
        RoomCalendarPlanRepository(
            db,
            db.calendarPlanDao(),
            db.routineDao(),
            Dispatchers.Unconfined,
            migrationGate = gate,
        )

    val result = async {
      repository.createPlan(
          "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
          routine,
          InstantForTest(),
          zone,
      )
    }
    gate.entered.await()
    db.openHelper.writableDatabase.execSQL(
        "UPDATE backend_state SET owner='owner-b',generation=2 WHERE id=1"
    )
    gate.release.complete(Unit)

    assertTrue(result.await() is CalendarPlanResult.Failure)
    assertEquals(0, db.calendarPlanDao().planCount())
  }

  @Test
  fun `routine-only weekly edit retains rule UUID and its exception`() = runTest {
    val firstRoutine =
        db.routineDao().upsertRoutine(RoutineEntity(syncId = "routine-a", name = "Ноги"))
    val secondRoutine =
        db.routineDao().upsertRoutine(RoutineEntity(syncId = "routine-b", name = "Спина"))
    val repository = repository()
    val date =
        LocalDate.now(zone)
            .with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY))
    assertTrue(
        repository.replaceWeeklySchedule(
            WeeklySchedule(listOf(DayRule(1, firstRoutine, 8, 30))),
            zone,
        ) is CalendarPlanResult.Success
    )
    val original = db.calendarPlanDao().rules().single()
    assertTrue(
        repository.setException(original.id, date, CalendarExceptionKind.CANCELLED, null)
            is CalendarPlanResult.Success
    )

    assertTrue(
        repository.replaceWeeklySchedule(
            WeeklySchedule(listOf(DayRule(1, secondRoutine, 8, 30))),
            zone,
        ) is CalendarPlanResult.Success
    )
    val edited = db.calendarPlanDao().rules().single()
    assertEquals(original.id, edited.id)
    assertEquals(secondRoutine, edited.routineId)
    assertEquals(1, db.calendarPlanDao().exceptions(edited.id).size)
  }

  @Test
  fun `wall-time weekly edit replaces UUID after deleting child exceptions`() = runTest {
    val routine = db.routineDao().upsertRoutine(RoutineEntity(syncId = "routine-a", name = "Ноги"))
    val repository = repository()
    val date =
        LocalDate.now(zone)
            .with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY))
    repository.replaceWeeklySchedule(WeeklySchedule(listOf(DayRule(1, routine, 8, 30))), zone)
    val original = db.calendarPlanDao().rules().single()
    repository.setException(original.id, date, CalendarExceptionKind.CANCELLED, null)

    assertTrue(
        repository.replaceWeeklySchedule(WeeklySchedule(listOf(DayRule(1, routine, 9, 0))), zone)
            is CalendarPlanResult.Success
    )
    val replacement = db.calendarPlanDao().rules().single()
    assertTrue(replacement.id != original.id)
    assertEquals(0, tableCount("calendar_exceptions"))
  }

  @Test
  fun `routine edit on a later day preserves anchor UUID and exception`() = runTest {
    val a = db.routineDao().upsertRoutine(RoutineEntity(syncId = "a", name = "A"))
    val b = db.routineDao().upsertRoutine(RoutineEntity(syncId = "b", name = "B"))
    val clock =
        object : com.valerochka1337.valerochkagym.data.calendar.CalendarMigrationClock() {
          var day = LocalDate.of(2026, 9, 7)

          override fun localDate(zone: ZoneId) = day
        }
    val repo =
        RoomCalendarPlanRepository(
            db,
            db.calendarPlanDao(),
            db.routineDao(),
            Dispatchers.Unconfined,
            clock = clock,
        )
    repo.replaceWeeklySchedule(WeeklySchedule(listOf(DayRule(1, a, 8, 30))), zone)
    val original = db.calendarPlanDao().rules().single()
    repo.setException(original.id, clock.day, CalendarExceptionKind.CANCELLED, null)
    clock.day = clock.day.plusDays(3)
    assertTrue(
        repo.replaceWeeklySchedule(WeeklySchedule(listOf(DayRule(1, b, 8, 30))), zone)
            is CalendarPlanResult.Success
    )
    assertEquals(original.id, db.calendarPlanDao().rules().single().id)
    assertEquals(original.startLocalDate, db.calendarPlanDao().rules().single().startLocalDate)
    assertEquals(1, db.calendarPlanDao().exceptions(original.id).size)
  }

  @Test
  fun `exception rejects a different weekday and a date before the rule anchor`() = runTest {
    val routine = db.routineDao().upsertRoutine(RoutineEntity(syncId = "a", name = "A"))
    val repo = repository()
    repo.replaceWeeklySchedule(WeeklySchedule(listOf(DayRule(1, routine, 8, 30))), zone)
    val rule = db.calendarPlanDao().rules().single()
    val monday =
        LocalDate.parse(rule.startLocalDate)
            .with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY))
    assertTrue(
        repo.setException(rule.id, monday.plusDays(1), CalendarExceptionKind.CANCELLED, null)
            is CalendarPlanResult.Failure
    )
    assertTrue(
        repo.setException(rule.id, monday.minusWeeks(1), CalendarExceptionKind.CANCELLED, null)
            is CalendarPlanResult.Failure
    )
    assertEquals(0, tableCount("calendar_exceptions"))
  }

  @Test
  fun `plan and live rule references reject routine deletion and leave completed history`() =
      runTest {
        val routine = db.routineDao().upsertRoutine(RoutineEntity(syncId = "a", name = "A"))
        insertWorkout("completed", finishedAt = 2000)
        val repo = repository()
        val id = "33333333-3333-4333-8333-333333333333"
        repo.createPlan(id, routine, InstantForTest(), zone)
        assertTrue(runCatching { db.routineDao().deleteRoutine(routine) }.isFailure)
        repo.cancelPlan(id)
        repo.replaceWeeklySchedule(WeeklySchedule(listOf(DayRule(1, routine, 8, 30))), zone)
        assertTrue(runCatching { db.routineDao().deleteRoutine(routine) }.isFailure)
        repo.clearWeeklySchedule()
        db.routineDao().deleteRoutine(routine)
        assertEquals(1, tableCount("workouts"))
      }

  private fun InstantForTest() =
      LocalDate.of(2026, 9, 10).atStartOfDay(zone).toInstant().toEpochMilli()

  private fun repository() =
      RoomCalendarPlanRepository(db, db.calendarPlanDao(), db.routineDao(), Dispatchers.Unconfined)

  private class WaitingGate : CalendarMigrationGate {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun ensureReady(): Boolean {
      entered.complete(Unit)
      release.await()
      return true
    }
  }
}
