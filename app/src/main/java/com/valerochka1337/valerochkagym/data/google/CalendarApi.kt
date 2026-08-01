package com.valerochka1337.valerochkagym.data.google

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Минимальный клиент Google Calendar API v3 для планирования тренировок. Токен передаётся
 * явным заголовком `Authorization` в каждом методе (как в [SheetsApi]) — access-токен
 * добывается suspend-операцией [GoogleAuth.getAccessToken], поэтому OkHttp-интерсептор
 * не подходит.
 *
 * Base URL — `https://www.googleapis.com/` (отдельный `@Named("calendar")` Retrofit в
 * NetworkModule, т.к. отличается от Sheets-базы). Событие всегда создаётся в календаре
 * `primary`. Неизвестные поля ответов игнорируются (см. настройку `Json` в DI).
 */
interface CalendarApi {

    /** Создаёт событие в календаре `primary`; из ответа нужен только `id`. */
    @POST("calendar/v3/calendars/primary/events")
    suspend fun insertEvent(
        @Header("Authorization") bearer: String,
        @Body body: CalendarEventDto,
    ): CalendarEventResponseDto

    /**
     * Удаляет событие календаря `primary`. Ответ без тела (`Response<Unit>`), чтобы вызывающий
     * мог по коду отличить успех/404/410 от прочих ошибок.
     */
    @DELETE("calendar/v3/calendars/primary/events/{eventId}")
    suspend fun deleteEvent(
        @Header("Authorization") bearer: String,
        @Path("eventId") eventId: String,
    ): Response<Unit>
}

/**
 * Тело `events.insert`. [start]/[end] задаются как `{"dateTime": "<ISO-8601 со смещением>"}`;
 * при наличии offset поле `timeZone` не требуется. [reminders] отключает дефолтные напоминания
 * и оставляет один popup за 30 минут до начала.
 *
 * [recurrence] — список RRULE-строк (например `"RRULE:FREQ=WEEKLY;BYDAY=MO"`) для повторяющихся
 * событий недельного расписания; у одиночных ad-hoc событий = null. Общий `Json` в NetworkModule
 * настроен с `encodeDefaults = true`, поэтому поле помечено `@EncodeDefault(NEVER)` — при null оно
 * НЕ сериализуется, и одиночные события (а также все Sheets-DTO) остаются без `recurrence`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CalendarEventDto(
    val summary: String,
    val start: EventDateTimeDto,
    val end: EventDateTimeDto,
    val reminders: EventRemindersDto,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val recurrence: List<String>? = null,
)

/**
 * `{"dateTime": "<ISO-8601 со смещением>"}`. [timeZone] (IANA, напр. `Europe/Moscow`) обязателен
 * для повторяющихся (RRULE) событий — без него Google отвечает 400; у одиночных событий = null и
 * не сериализуется (см. `@EncodeDefault(NEVER)`).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class EventDateTimeDto(
    val dateTime: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val timeZone: String? = null,
)

@Serializable
data class EventRemindersDto(
    val useDefault: Boolean,
    val overrides: List<EventReminderOverrideDto>,
)

@Serializable
data class EventReminderOverrideDto(
    val method: String,
    val minutes: Int,
)

/** Ответ `events.insert`; используется только `id` созданного события. */
@Serializable
data class CalendarEventResponseDto(
    val id: String,
)
