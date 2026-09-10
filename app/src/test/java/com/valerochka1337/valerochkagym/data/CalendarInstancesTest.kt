package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.calendar.*
import com.valerochka1337.valerochkagym.data.db.entity.*
import com.valerochka1337.valerochkagym.data.db.relation.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class CalendarInstancesTest {
  private val utc = ZoneId.of("UTC")
  private val rule =
      CalendarRuleEntity("11111111-1111-4111-8111-111111111111", 1, 1, "08:30", "UTC", "2026-09-07")

  private fun read(
      start: String,
      end: String = start,
      changes: List<CalendarExceptionEntity> = emptyList(),
      source: CalendarRuleEntity = rule,
      display: ZoneId = utc,
  ) =
      CalendarInstances.resolveIn(
          emptyList(),
          listOf(CalendarRuleWithRoutine(source, "Программа")),
          changes,
          LocalDateRange(LocalDate.parse(start), LocalDate.parse(end)),
          display,
      )

  private fun change(
      day: String,
      kind: CalendarExceptionKind,
      at: Long? = null,
  ): CalendarExceptionEntity {
    val key =
        CalendarTimeResolver.instanceKey(LocalDate.parse(day), LocalTime.parse(rule.localTime), utc)
    return CalendarExceptionEntity(
        CalendarTimeResolver.exceptionId(rule.id, key),
        rule.id,
        key,
        kind,
        at,
    )
  }

  @Test
  fun `rule never appears before its anchor`() {
    assertTrue(read("2026-08-31").isEmpty())
    assertEquals(1, read("2026-09-07").size)
  }

  @Test
  fun `cancellation removes only its original occurrence`() {
    val c = change("2026-09-07", CalendarExceptionKind.CANCELLED)
    val result = read("2026-09-07", "2026-09-14", listOf(c))
    assertEquals(1, result.size)
    assertTrue(result.single().instanceKey!!.startsWith("2026-09-14"))
  }

  @Test
  fun `moved occurrence arrives from another month with unchanged identity`() {
    val at = Instant.parse("2026-10-02T10:00:00Z").toEpochMilli()
    val c = change("2026-09-07", CalendarExceptionKind.MOVED, at)
    assertTrue(read("2026-09-07", changes = listOf(c)).isEmpty())
    val moved = read("2026-10-02", changes = listOf(c)).single()
    assertEquals(c.id, moved.id)
    assertEquals(c.instanceKey, moved.instanceKey)
    assertEquals(at, moved.startsAtMillis)
    assertTrue(moved.moved)
  }

  @Test
  fun `two occurrences on one display day retain separate identities`() {
    val c =
        change(
            "2026-09-07",
            CalendarExceptionKind.MOVED,
            Instant.parse("2026-09-14T08:30:00Z").toEpochMilli(),
        )
    val rows = read("2026-09-14", changes = listOf(c))
    assertEquals(2, rows.size)
    assertEquals(2, rows.map { it.id }.distinct().size)
  }

  @Test
  fun `display zone uses resolved instant instead of original weekday`() {
    val tokyo = rule.copy(localTime = "00:30", timeZoneId = "Asia/Tokyo")
    assertEquals(1, read("2026-09-06", source = tokyo).size)
    assertTrue(read("2026-09-07", source = tokyo).isEmpty())
  }

  @Test
  fun `gap and overlap use the sole temporal resolver`() {
    val source =
        rule.copy(
            isoDay = 7,
            localTime = "02:30",
            timeZoneId = "America/New_York",
            startLocalDate = "2026-01-01",
        )
    assertEquals(
        Instant.parse("2026-03-08T07:00:00Z").toEpochMilli(),
        read("2026-03-08", source = source).single().startsAtMillis,
    )
    assertEquals(
        Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(),
        read("2026-11-01", source = source.copy(localTime = "01:30")).single().startsAtMillis,
    )
  }

  @Test
  fun `one off membership follows the display zone without changing its instant`() {
    val at = Instant.parse("2026-09-07T00:30:00Z").toEpochMilli()
    val plan = CalendarPlanWithRoutine(CalendarPlanEntity("p", 1, at, "UTC"), "Программа")
    val rows =
        CalendarInstances.resolveIn(
            listOf(plan),
            emptyList(),
            emptyList(),
            LocalDateRange(LocalDate.parse("2026-09-06"), LocalDate.parse("2026-09-06")),
            ZoneId.of("America/New_York"),
        )
    assertEquals(at, rows.single().startsAtMillis)
  }
}
