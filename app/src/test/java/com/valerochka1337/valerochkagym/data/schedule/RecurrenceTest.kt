package com.valerochka1337.valerochkagym.data.schedule

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure unit tests for the RRULE helpers — no Android, no Robolectric. */
class RecurrenceTest {

  @Test
  fun `byDayOf maps all seven iso days`() {
    assertEquals("MO", byDayOf(1))
    assertEquals("TU", byDayOf(2))
    assertEquals("WE", byDayOf(3))
    assertEquals("TH", byDayOf(4))
    assertEquals("FR", byDayOf(5))
    assertEquals("SA", byDayOf(6))
    assertEquals("SU", byDayOf(7))
  }

  @Test
  fun `nextOccurrence returns from itself when the weekday already matches`() {
    // 2026-08-03 is a Monday (isoDay 1).
    val from = LocalDate.of(2026, 8, 3)
    val millis =
        nextOccurrenceMillis(isoDay = 1, hour = 18, minute = 30, zone = ZoneOffset.UTC, from = from)

    val dt = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneOffset.UTC)
    assertEquals(LocalDate.of(2026, 8, 3), dt.toLocalDate())
    assertEquals(18, dt.hour)
    assertEquals(30, dt.minute)
  }

  @Test
  fun `nextOccurrence advances to the next matching weekday`() {
    // From Monday 2026-08-03, the next Wednesday (isoDay 3) is 2026-08-05.
    val from = LocalDate.of(2026, 8, 3)
    val millis =
        nextOccurrenceMillis(isoDay = 3, hour = 9, minute = 0, zone = ZoneOffset.UTC, from = from)

    val dt = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneOffset.UTC)
    assertEquals(LocalDate.of(2026, 8, 5), dt.toLocalDate())
    assertEquals(9, dt.hour)
    assertEquals(0, dt.minute)
  }

  @Test
  fun `nextOccurrence wraps to next week for an earlier weekday`() {
    // From Wednesday 2026-08-05, the next Monday (isoDay 1) is 2026-08-10.
    val from = LocalDate.of(2026, 8, 5)
    val millis =
        nextOccurrenceMillis(isoDay = 1, hour = 7, minute = 15, zone = ZoneOffset.UTC, from = from)

    val dt = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneOffset.UTC)
    assertEquals(LocalDate.of(2026, 8, 10), dt.toLocalDate())
  }
}
