package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.ui.common.formatDuration
import com.valerochka1337.valerochkagym.ui.common.formatRestClock
import com.valerochka1337.valerochkagym.ui.common.formatVolume
import com.valerochka1337.valerochkagym.ui.common.formatWorkoutDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Общие форматтеры тренировок: дата, длительность, часы отдыха и объём. */
class WorkoutFormatTest {

    private fun millisOf(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun `date-time in the current year omits the year`() {
        val currentYear = LocalDate.now().year
        val formatted = formatWorkoutDateTime(millisOf(currentYear, 7, 31, 19, 30))
        assertEquals("31 июля, 19:30", formatted)
    }

    @Test
    fun `date-time in a past year keeps the year`() {
        val pastYear = LocalDate.now().year - 1
        val formatted = formatWorkoutDateTime(millisOf(pastYear, 7, 31, 19, 30))
        assertTrue(formatted, formatted.startsWith("31 июля $pastYear"))
    }

    @Test
    fun `duration shows hours only when they exist`() {
        assertEquals("45 мин", formatDuration(45 * 60_000L))
        assertEquals("1 ч 05 мин", formatDuration(65 * 60_000L))
        assertEquals("0 мин", formatDuration(-1_000L))
    }

    @Test
    fun `rest clock is minutes and zero-padded seconds`() {
        assertEquals("2:05", formatRestClock(125))
        assertEquals("0:00", formatRestClock(0))
        assertEquals("0:00", formatRestClock(-10))
    }

    @Test
    fun `volume groups thousands with spaces`() {
        assertEquals("950 кг", formatVolume(950.0))
        assertEquals("2 340 кг", formatVolume(2_340.4))
        assertEquals("12 345 678 кг", formatVolume(12_345_678.0))
    }

    @Test
    fun `non-positive volume yields nothing`() {
        assertNull(formatVolume(0.0))
        assertNull(formatVolume(-5.0))
        assertNull(formatVolume(0.4))
    }
}
