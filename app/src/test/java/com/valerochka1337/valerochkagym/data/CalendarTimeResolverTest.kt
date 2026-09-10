package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.calendar.CalendarTimeResolver
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarTimeResolverTest {
  @Test
  fun `rule identity uses normalized owner and unit separator namespace`() {
    val id =
        CalendarTimeResolver.ruleId(
            " User@Example.com ",
            "A0B1C2D3-E4F5-6789-ABCD-0123456789EF",
            1,
            "08:30",
        )
    assertEquals(
        id,
        CalendarTimeResolver.ruleId(
            "user@example.com",
            "a0b1c2d3-e4f5-6789-abcd-0123456789ef",
            1,
            "08:30",
        ),
    )
    assertTrue(id.matches(Regex("[0-9a-f-]{36}")))
  }

  @Test
  fun `exception keeps original instance key and identity`() {
    val key =
        CalendarTimeResolver.instanceKey(
            LocalDate.of(2030, 3, 31),
            LocalTime.of(2, 30),
            ZoneId.of("Europe/Berlin"),
        )
    assertEquals("2030-03-31T02:30[Europe/Berlin]", key)
    assertEquals(
        CalendarTimeResolver.exceptionId("a", key),
        CalendarTimeResolver.exceptionId("a", key),
    )
  }

  @Test
  fun `gap resolves after transition and overlap chooses earlier offset`() {
    val zone = ZoneId.of("Europe/Berlin")
    val gap = CalendarTimeResolver.resolve(LocalDate.of(2030, 3, 31), LocalTime.of(2, 30), zone)
    val overlap =
        CalendarTimeResolver.resolve(LocalDate.of(2030, 10, 27), LocalTime.of(2, 30), zone)
    assertEquals(LocalTime.of(3, 0), java.time.Instant.ofEpochMilli(gap).atZone(zone).toLocalTime())
    assertEquals("+02:00", java.time.Instant.ofEpochMilli(overlap).atZone(zone).offset.id)
  }
}
