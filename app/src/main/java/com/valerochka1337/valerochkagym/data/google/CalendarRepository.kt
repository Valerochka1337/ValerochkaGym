package com.valerochka1337.valerochkagym.data.google

import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.ScheduledWorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ScheduledWorkoutEntity
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Результат интерактивной операции с календарём (пользователь ждёт ответа).
 *
 * [Success] — событие создано/удалено, локальная запись синхронизирована.
 * [NeedsConsent] — доступ к Google ещё не выдан; UI отправляет пользователя в настройки.
 * [Failure] — операция не удалась ([message] показывается пользователю как есть).
 */
sealed interface ScheduleResult {
    data object Success : ScheduleResult
    data object NeedsConsent : ScheduleResult
    data class Failure(val message: String) : ScheduleResult
}

/** Планирование тренировки в Google Calendar и отмена запланированной. */
interface CalendarRepository {

    /**
     * Создаёт событие в календаре `primary` на [dateTimeMillis] (+1 час) и — только при успехе
     * запроса — локальную запись [ScheduledWorkoutEntity]. При ошибке API локально ничего не
     * пишется (операция интерактивная, ошибка показывается сразу).
     */
    suspend fun schedule(routineId: Long, dateTimeMillis: Long): ScheduleResult

    /**
     * Удаляет событие календаря и локальную запись запланированной тренировки [scheduledId].
     * Отсутствие записи или события (404/410) считается успехом. При иной ошибке удаления
     * события локальная запись сохраняется.
     */
    suspend fun cancel(scheduledId: Long): ScheduleResult
}

/**
 * Реализация планирования в календарь `primary`.
 *
 * Порядок [schedule]: токен → имя программы → `events.insert` (start=[dateTimeMillis],
 * end=+1ч, ISO-8601 со смещением зоны устройства) → на успех вставка [ScheduledWorkoutEntity].
 * HTTP-ошибки классифицируются в понятные сообщения (см. [insertFailureMessage]).
 */
class CalendarRepositoryImpl @Inject constructor(
    private val api: CalendarApi,
    private val googleAuth: GoogleAuth,
    private val routineDao: RoutineDao,
    private val scheduledWorkoutDao: ScheduledWorkoutDao,
) : CalendarRepository {

    override suspend fun schedule(routineId: Long, dateTimeMillis: Long): ScheduleResult {
        val bearer = when (val token = accessToken()) {
            is Token.Ok -> token.bearer
            Token.Consent -> return ScheduleResult.NeedsConsent
            Token.Transient -> return ScheduleResult.Failure(NO_CONNECTION)
        }

        val routineName = routineDao.getRoutineName(routineId)
            ?: return ScheduleResult.Failure("Программа не найдена")

        val start = Instant.ofEpochMilli(dateTimeMillis).atZone(ZoneId.systemDefault())
        val end = start.plusSeconds(EVENT_DURATION_SECONDS)
        val request = CalendarEventDto(
            summary = "Тренировка: $routineName",
            start = EventDateTimeDto(start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
            end = EventDateTimeDto(end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
            reminders = EventRemindersDto(
                useDefault = false,
                overrides = listOf(EventReminderOverrideDto(method = "popup", minutes = REMINDER_MINUTES)),
            ),
        )

        return try {
            val eventId = api.insertEvent(bearer, request).id
            scheduledWorkoutDao.insert(
                ScheduledWorkoutEntity(
                    routineId = routineId,
                    dateTimeMillis = dateTimeMillis,
                    calendarEventId = eventId,
                ),
            )
            ScheduleResult.Success
        } catch (e: HttpException) {
            ScheduleResult.Failure(insertFailureMessage(e.code()))
        } catch (e: IOException) {
            ScheduleResult.Failure(NO_NETWORK)
        }
    }

    override suspend fun cancel(scheduledId: Long): ScheduleResult {
        // Записи уже нет — считаем отменённой (идемпотентная повторная отмена).
        val scheduled = scheduledWorkoutDao.getById(scheduledId) ?: return ScheduleResult.Success

        val bearer = when (val token = accessToken()) {
            is Token.Ok -> token.bearer
            Token.Consent -> return ScheduleResult.NeedsConsent
            Token.Transient -> return ScheduleResult.Failure(NO_CONNECTION)
        }

        return try {
            val response = api.deleteEvent(bearer, scheduled.calendarEventId)
            // 404/410 — события уже нет на стороне Google; для отмены это успех.
            if (response.isSuccessful || response.code() == HTTP_NOT_FOUND || response.code() == HTTP_GONE) {
                scheduledWorkoutDao.delete(scheduledId)
                ScheduleResult.Success
            } else {
                ScheduleResult.Failure(deleteFailureMessage(response.code()))
            }
        } catch (e: HttpException) {
            if (e.code() == HTTP_NOT_FOUND || e.code() == HTTP_GONE) {
                scheduledWorkoutDao.delete(scheduledId)
                ScheduleResult.Success
            } else {
                ScheduleResult.Failure(deleteFailureMessage(e.code()))
            }
        } catch (e: IOException) {
            ScheduleResult.Failure(NO_NETWORK)
        }
    }

    /** Мостик от [TokenResult] к внутренней троичной логике планировщика. */
    private suspend fun accessToken(): Token = when (val result = googleAuth.getAccessToken()) {
        is TokenResult.Success -> Token.Ok("Bearer ${result.token}")
        TokenResult.NeedsConsent -> Token.Consent
        is TokenResult.Failed -> Token.Transient
    }

    private fun insertFailureMessage(code: Int): String = when (code) {
        401, 403 -> NO_CALENDAR_ACCESS
        else -> "Не удалось создать событие (HTTP $code)"
    }

    private fun deleteFailureMessage(code: Int): String = when (code) {
        401, 403 -> NO_CALENDAR_ACCESS
        else -> "Не удалось удалить событие (HTTP $code)"
    }

    private sealed interface Token {
        data class Ok(val bearer: String) : Token
        data object Consent : Token
        data object Transient : Token
    }

    private companion object {
        const val EVENT_DURATION_SECONDS = 60L * 60L
        const val REMINDER_MINUTES = 30
        const val HTTP_NOT_FOUND = 404
        const val HTTP_GONE = 410

        const val NO_CONNECTION = "Нет соединения с Google — попробуйте ещё раз"
        const val NO_NETWORK = "Нет сети"
        const val NO_CALENDAR_ACCESS = "Нет доступа к календарю"
    }
}
