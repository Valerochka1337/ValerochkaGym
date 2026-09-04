package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.ui.calendar.DotStyle
import com.valerochka1337.valerochkagym.ui.calendar.buildMonthCells
import com.valerochka1337.valerochkagym.ui.calendar.monthTitle
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the calendar grid builder — no Android, no ViewModel. */
class CalendarFormatTest {

  private val august = YearMonth.of(2026, 8)

  @Test
  fun `monthTitle is nominative and capitalised`() {
    assertEquals("Август 2026", monthTitle(august))
  }

  @Test
  fun `grid has 42 cells, Monday-first, with adjacent-month leading blanks`() {
    val cells =
        buildMonthCells(
            august,
            today = LocalDate.of(2026, 8, 15),
            completedDays = emptySet(),
            adHocDays = emptySet(),
            weeklyIsoDays = emptySet(),
        )

    assertEquals(42, cells.size)
    // 2026-08-01 is a Saturday → 5 leading days from July, first cell is Monday 2026-07-27.
    assertEquals(LocalDate.of(2026, 7, 27), cells.first().date)
    assertFalse(cells.first().inMonth)
    assertTrue(cell(cells, LocalDate.of(2026, 8, 1)).inMonth)
  }

  @Test
  fun `completed day gets a filled dot regardless of today`() {
    val cells =
        buildMonthCells(
            august,
            today = LocalDate.of(2026, 8, 15),
            completedDays = setOf(LocalDate.of(2026, 8, 10)),
            adHocDays = emptySet(),
            weeklyIsoDays = emptySet(),
        )

    assertEquals(DotStyle.Completed, cell(cells, LocalDate.of(2026, 8, 10)).dot)
  }

  @Test
  fun `future ad-hoc day gets an outline planned dot, past ad-hoc does not`() {
    val cells =
        buildMonthCells(
            august,
            today = LocalDate.of(2026, 8, 15),
            completedDays = emptySet(),
            adHocDays = setOf(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 5)),
            weeklyIsoDays = emptySet(),
        )

    assertEquals(DotStyle.Planned, cell(cells, LocalDate.of(2026, 8, 20)).dot)
    assertEquals(DotStyle.None, cell(cells, LocalDate.of(2026, 8, 5)).dot) // in the past
  }

  @Test
  fun `future weekday matching a weekly rule is planned`() {
    // Wednesday is isoDay 3. 2026-08-19 is a future Wednesday; 2026-08-05 is a past Wednesday.
    val cells =
        buildMonthCells(
            august,
            today = LocalDate.of(2026, 8, 15),
            completedDays = emptySet(),
            adHocDays = emptySet(),
            weeklyIsoDays = setOf(3),
        )

    assertEquals(DotStyle.Planned, cell(cells, LocalDate.of(2026, 8, 19)).dot)
    assertEquals(DotStyle.None, cell(cells, LocalDate.of(2026, 8, 5)).dot)
  }

  @Test
  fun `completed takes precedence over planned on the same day`() {
    val day = LocalDate.of(2026, 8, 19) // future Wednesday
    val cells =
        buildMonthCells(
            august,
            today = LocalDate.of(2026, 8, 15),
            completedDays = setOf(day),
            adHocDays = setOf(day),
            weeklyIsoDays = setOf(3),
        )

    assertEquals(DotStyle.Completed, cell(cells, day).dot)
  }

  @Test
  fun `today is flagged`() {
    val cells =
        buildMonthCells(
            august,
            today = LocalDate.of(2026, 8, 15),
            completedDays = emptySet(),
            adHocDays = emptySet(),
            weeklyIsoDays = emptySet(),
        )

    assertTrue(cell(cells, LocalDate.of(2026, 8, 15)).isToday)
    assertFalse(cell(cells, LocalDate.of(2026, 8, 14)).isToday)
  }

  @Test
  fun `out-of-month cells never carry a dot`() {
    // 2026-07-27 (leading blank) is a completed day but out of month → no dot.
    val cells =
        buildMonthCells(
            august,
            today = LocalDate.of(2026, 8, 15),
            completedDays = setOf(LocalDate.of(2026, 7, 27)),
            adHocDays = emptySet(),
            weeklyIsoDays = emptySet(),
        )

    assertEquals(DotStyle.None, cells.first().dot)
  }

  private fun cell(
      cells: List<com.valerochka1337.valerochkagym.ui.calendar.DayCellUi>,
      date: LocalDate,
  ) = cells.first { it.date == date }
}
