package com.valerochka1337.valerochkagym.data.schedule

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.google.CalendarApi
import com.valerochka1337.valerochkagym.data.google.CalendarEventDto
import com.valerochka1337.valerochkagym.data.google.EventDateTimeDto
import com.valerochka1337.valerochkagym.data.google.EventReminderOverrideDto
import com.valerochka1337.valerochkagym.data.google.EventRemindersDto
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.GoogleErrorMessages
import com.valerochka1337.valerochkagym.data.google.ScheduleResult
import com.valerochka1337.valerochkagym.data.google.TokenResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Недельное расписание тренировок: хранимый в DataStore шаблон [WeeklySchedule] и соответствующие ему
 * повторяющиеся (RRULE) события Google Calendar. В отличие от одиночных ad-hoc тренировок
 * ([com.valerochka1337.valerochkagym.data.google.CalendarRepository]) серия удаляется только целиком.
 */
interface WeeklyScheduleRepository {

    /** Текущий сохранённый шаблон; пустой, если расписание не задано. */
    fun observe(): Flow<WeeklySchedule>

    /**
     * Применяет [schedule] с семантикой замены: удаляет ранее созданные RRULE-события, создаёт новые
     * по одному на активный [DayRule] и сохраняет шаблон с id созданных событий. Если доступ к Google
     * ещё не выдан — [ScheduleResult.NeedsConsent] без каких-либо изменений.
     */
    suspend fun save(schedule: WeeklySchedule): ScheduleResult

    /** Удаляет все созданные серии и очищает хранимый шаблон. */
    suspend fun clear(): ScheduleResult
}

/**
 * Реализация недельного расписания над Google Calendar `primary` и DataStore.
 *
 * Токен и классификация ошибок повторяют [com.valerochka1337.valerochkagym.data.google.CalendarRepositoryImpl]
 * (тот же [ScheduleResult]/[GoogleErrorMessages]). Шаблон целиком сериализуется JSON в один ключ
 * [Keys.WEEKLY_SCHEDULE], поэтому шаблон и id событий всегда согласованы.
 */
class WeeklyScheduleRepositoryImpl @Inject constructor(
    private val api: CalendarApi,
    private val googleAuth: GoogleAuth,
    private val routineDao: RoutineDao,
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) : WeeklyScheduleRepository {

    override fun observe(): Flow<WeeklySchedule> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[Keys.WEEKLY_SCHEDULE]?.let(::decode) ?: WeeklySchedule() }

    override suspend fun save(schedule: WeeklySchedule): ScheduleResult {
        val bearer = when (val token = accessToken()) {
            is Token.Ok -> token.bearer
            Token.Consent -> return ScheduleResult.NeedsConsent
            Token.Transient -> return ScheduleResult.Failure(GoogleErrorMessages.NO_CONNECTION)
        }

        // Удаляем ранее созданную серию (её id лежат в сохранённом шаблоне).
        val previous = readPersisted()
        val deleteError = try {
            deleteAll(bearer, previous)
        } catch (e: IOException) {
            return ScheduleResult.Failure(GoogleErrorMessages.NO_NETWORK)
        }
        if (deleteError != null) return ScheduleResult.Failure(deleteFailureMessage(deleteError))
        // Старые события удалены — обнуляем шаблон, чтобы при обрыве не осталось «висящих» id.
        persist(WeeklySchedule())

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val created = mutableListOf<DayRule>()
        for (rule in schedule.rules) {
            val routineName = routineDao.getRoutineName(rule.routineId) ?: continue
            val request = recurringRequest(rule, routineName, zone, today)
            try {
                val eventId = api.insertEvent(bearer, request).id
                created += rule.copy(calendarEventId = eventId)
            } catch (e: HttpException) {
                // Сохраняем созданное до сбоя, чтобы orphan-события были отслеживаемы.
                persist(WeeklySchedule(created))
                return ScheduleResult.Failure(insertFailureMessage(e.code()))
            } catch (e: IOException) {
                persist(WeeklySchedule(created))
                return ScheduleResult.Failure(GoogleErrorMessages.NO_NETWORK)
            }
        }
        persist(WeeklySchedule(created))
        return ScheduleResult.Success
    }

    override suspend fun clear(): ScheduleResult {
        val previous = readPersisted()
        if (previous.rules.isEmpty()) {
            persist(WeeklySchedule())
            return ScheduleResult.Success
        }

        val bearer = when (val token = accessToken()) {
            is Token.Ok -> token.bearer
            Token.Consent -> return ScheduleResult.NeedsConsent
            Token.Transient -> return ScheduleResult.Failure(GoogleErrorMessages.NO_CONNECTION)
        }

        val deleteError = try {
            deleteAll(bearer, previous)
        } catch (e: IOException) {
            return ScheduleResult.Failure(GoogleErrorMessages.NO_NETWORK)
        }
        if (deleteError != null) return ScheduleResult.Failure(deleteFailureMessage(deleteError))
        persist(WeeklySchedule())
        return ScheduleResult.Success
    }

    /**
     * Удаляет все события серии; 404/410 (события уже нет) считаются успехом. Возвращает код первой
     * непреодолимой ошибки удаления или null, если все события удалены успешно. [IOException]
     * пробрасывается вызывающему.
     */
    private suspend fun deleteAll(bearer: String, schedule: WeeklySchedule): Int? {
        for (rule in schedule.rules) {
            val eventId = rule.calendarEventId ?: continue
            val code = try {
                val response = api.deleteEvent(bearer, eventId)
                if (response.isSuccessful) continue else response.code()
            } catch (e: HttpException) {
                e.code()
            }
            if (code != HTTP_NOT_FOUND && code != HTTP_GONE) return code
        }
        return null
    }

    private fun recurringRequest(
        rule: DayRule,
        routineName: String,
        zone: ZoneId,
        today: LocalDate,
    ): CalendarEventDto {
        val startMillis = nextOccurrenceMillis(rule.isoDay, rule.hour, rule.minute, zone, today)
        val start = Instant.ofEpochMilli(startMillis).atZone(zone)
        val end = start.plusSeconds(EVENT_DURATION_SECONDS)
        // Для RRULE-события Google требует timeZone в start/end — иначе 400.
        return CalendarEventDto(
            summary = "Тренировка: $routineName",
            start = EventDateTimeDto(start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), zone.id),
            end = EventDateTimeDto(end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), zone.id),
            reminders = EventRemindersDto(
                useDefault = false,
                overrides = listOf(EventReminderOverrideDto(method = "popup", minutes = REMINDER_MINUTES)),
            ),
            recurrence = listOf("RRULE:FREQ=WEEKLY;BYDAY=${byDayOf(rule.isoDay)}"),
        )
    }

    private suspend fun readPersisted(): WeeklySchedule = observe().first()

    private suspend fun persist(schedule: WeeklySchedule) {
        dataStore.edit { prefs -> prefs[Keys.WEEKLY_SCHEDULE] = json.encodeToString(schedule) }
    }

    private fun decode(raw: String): WeeklySchedule =
        runCatching { json.decodeFromString<WeeklySchedule>(raw) }.getOrDefault(WeeklySchedule())

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

    private object Keys {
        val WEEKLY_SCHEDULE = stringPreferencesKey("weekly_schedule")
    }

    private companion object {
        const val EVENT_DURATION_SECONDS = 60L * 60L
        const val REMINDER_MINUTES = 30
        const val HTTP_NOT_FOUND = 404
        const val HTTP_GONE = 410

        const val NO_CALENDAR_ACCESS = "Нет доступа к календарю"
    }
}
