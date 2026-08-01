package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.google.CalendarEventDto
import com.valerochka1337.valerochkagym.data.google.EventDateTimeDto
import com.valerochka1337.valerochkagym.data.google.EventReminderOverrideDto
import com.valerochka1337.valerochkagym.data.google.EventRemindersDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for [CalendarEventDto] serialization. The shared `Json` in `NetworkModule` uses
 * `encodeDefaults = true`, so without the `@EncodeDefault(NEVER)` on [CalendarEventDto.recurrence]
 * a single (ad-hoc) event would emit `"recurrence": null` and break Google's `events.insert`. These
 * tests pin the behaviour: single events omit the field, recurring events include it.
 */
class CalendarEventDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun event(recurrence: List<String>? = null) = CalendarEventDto(
        summary = "Тренировка: Ноги",
        start = EventDateTimeDto("2026-08-03T18:00:00+03:00"),
        end = EventDateTimeDto("2026-08-03T19:00:00+03:00"),
        reminders = EventRemindersDto(
            useDefault = false,
            overrides = listOf(EventReminderOverrideDto(method = "popup", minutes = 30)),
        ),
        recurrence = recurrence,
    )

    @Test
    fun `single event omits recurrence and timeZone entirely`() {
        val encoded = json.encodeToString(event(recurrence = null))

        assertFalse("recurrence must not appear for single events: $encoded", encoded.contains("recurrence"))
        assertFalse("timeZone must not appear for single events: $encoded", encoded.contains("timeZone"))
    }

    @Test
    fun `recurring event includes the RRULE`() {
        val encoded = json.encodeToString(event(recurrence = listOf("RRULE:FREQ=WEEKLY;BYDAY=MO")))

        assertTrue(encoded.contains("\"recurrence\""))
        assertTrue(encoded.contains("RRULE:FREQ=WEEKLY;BYDAY=MO"))
    }
}
