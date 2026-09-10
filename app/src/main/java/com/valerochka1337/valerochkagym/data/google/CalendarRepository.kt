package com.valerochka1337.valerochkagym.data.google

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.CalendarEventAccountLinkDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.ScheduledWorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.CalendarEventAccountLinkEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarEventAccountLinkState
import com.valerochka1337.valerochkagym.data.db.entity.ScheduledWorkoutEntity
import com.valerochka1337.valerochkagym.data.settings.CalendarAccountIdentity
import com.valerochka1337.valerochkagym.data.settings.normalizeCalendarEmail
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import retrofit2.HttpException

/**
 * Результат интерактивной операции с календарём (пользователь ждёт ответа).
 *
 * [Success] — событие создано/удалено, локальная запись синхронизирована. [NeedsConsent] — доступ к
 * Google ещё не выдан; UI отправляет пользователя в настройки. [Failure] — операция не удалась
 * ([Failure.message] показывается пользователю как есть).
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
   * запроса — локальную запись [ScheduledWorkoutEntity]. При ошибке API локально ничего не пишется
   * (операция интерактивная, ошибка показывается сразу).
   */
  suspend fun schedule(routineId: Long, dateTimeMillis: Long): ScheduleResult

  /**
   * Удаляет событие календаря и локальную запись запланированной тренировки [scheduledId].
   * Отсутствие записи или события (404/410) считается успехом. При иной ошибке удаления события
   * локальная запись сохраняется.
   */
  suspend fun cancel(scheduledId: Long): ScheduleResult
}

/**
 * Реализация планирования в календарь `primary`.
 *
 * Порядок [schedule]: токен → имя программы → `events.insert` (start=[], end=+1ч, ISO-8601 со
 * смещением зоны устройства) → на успех вставка dateTimeMillis. HTTP-ошибки классифицируются в
 * понятные сообщения (см. [insertFailureMessage]).
 */
class CalendarRepositoryImpl
@Inject
constructor(
    private val api: CalendarApi,
    private val googleAuth: AccountBoundGoogleAuth,
    private val calendarIdentity: CalendarAccountIdentity,
    private val database: GymDatabase,
    private val routineDao: RoutineDao,
    private val scheduledWorkoutDao: ScheduledWorkoutDao,
    private val linkDao: CalendarEventAccountLinkDao,
) : CalendarRepository {

  override suspend fun schedule(routineId: Long, dateTimeMillis: Long): ScheduleResult {
    val owner = connectedEmail() ?: return ScheduleResult.NeedsConsent
    val bearer =
        when (val token = accessToken(owner)) {
          is Token.Ok -> token.bearer
          Token.Consent -> return ScheduleResult.NeedsConsent
          Token.Transient -> return ScheduleResult.Failure(GoogleErrorMessages.NO_CONNECTION)
        }
    if (connectedEmail() != owner) return ScheduleResult.Failure(ACCOUNT_CHANGED)

    val routineName =
        routineDao.getRoutineName(routineId)
            ?: return ScheduleResult.Failure("Программа не найдена")
    if (connectedEmail() != owner) return ScheduleResult.Failure(ACCOUNT_CHANGED)

    val start = Instant.ofEpochMilli(dateTimeMillis).atZone(ZoneId.systemDefault())
    val end = start.plusSeconds(EVENT_DURATION_SECONDS)
    val request =
        CalendarEventDto(
            summary = "Тренировка: $routineName",
            start = EventDateTimeDto(start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
            end = EventDateTimeDto(end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
            reminders =
                EventRemindersDto(
                    useDefault = false,
                    overrides =
                        listOf(
                            EventReminderOverrideDto(method = "popup", minutes = REMINDER_MINUTES)
                        ),
                ),
        )

    return try {
      if (connectedEmail() != owner) return ScheduleResult.Failure(ACCOUNT_CHANGED)
      val eventId = api.insertEvent(bearer, request).id
      if (connectedEmail() != owner) return ScheduleResult.Failure(ACCOUNT_CHANGED)
      database.withTransaction {
        if (connectedEmail() != owner) throw StaleCalendarOperationException
        val scheduledId =
            scheduledWorkoutDao.insert(
                ScheduledWorkoutEntity(
                    routineId = routineId,
                    dateTimeMillis = dateTimeMillis,
                    calendarEventId = eventId,
                ),
            )
        linkDao.insert(
            CalendarEventAccountLinkEntity(
                scheduledWorkoutId = scheduledId,
                ownerEmail = owner,
                state = CalendarEventAccountLinkState.OWNED,
            )
        )
      }
      ScheduleResult.Success
    } catch (e: HttpException) {
      ScheduleResult.Failure(insertFailureMessage(e.code()))
    } catch (_: IOException) {
      ScheduleResult.Failure(GoogleErrorMessages.NO_NETWORK)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: StaleCalendarOperationException) {
      ScheduleResult.Failure(ACCOUNT_CHANGED)
    } catch (_: Exception) {
      ScheduleResult.Failure(LOCAL_LINK_FAILURE)
    }
  }

  override suspend fun cancel(scheduledId: Long): ScheduleResult {
    // Записи уже нет — считаем отменённой (идемпотентная повторная отмена).
    val owned = ownedTuple(scheduledId) ?: return missingOrUnknownResult(scheduledId)
    if (connectedEmail() != owned.owner) return ScheduleResult.Failure(ACCOUNT_CHANGED)

    val bearer =
        when (val token = accessToken(owned.owner)) {
          is Token.Ok -> token.bearer
          Token.Consent -> return ScheduleResult.NeedsConsent
          Token.Transient -> return ScheduleResult.Failure(GoogleErrorMessages.NO_CONNECTION)
        }

    if (!tupleIsCurrent(owned)) return ScheduleResult.Failure(ACCOUNT_CHANGED)

    return try {
      val response = api.deleteEvent(bearer, owned.scheduled.calendarEventId)
      // 404/410 — события уже нет на стороне Google; для отмены это успех.
      if (
          response.isSuccessful || response.code() == HTTP_NOT_FOUND || response.code() == HTTP_GONE
      ) {
        deleteCurrentTuple(owned)
      } else {
        ScheduleResult.Failure(deleteFailureMessage(response.code()))
      }
    } catch (e: HttpException) {
      if (e.code() == HTTP_NOT_FOUND || e.code() == HTTP_GONE) {
        deleteCurrentTuple(owned)
      } else {
        ScheduleResult.Failure(deleteFailureMessage(e.code()))
      }
    } catch (_: IOException) {
      ScheduleResult.Failure(GoogleErrorMessages.NO_NETWORK)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      ScheduleResult.Failure(LOCAL_LINK_FAILURE)
    }
  }

  /** Мостик от [TokenResult] к внутренней троичной логике планировщика. */
  private suspend fun accessToken(owner: String): Token =
      when (val result = googleAuth.getAccessTokenForAccount(owner)) {
        is TokenResult.Success -> Token.Ok("Bearer ${result.token}")
        TokenResult.NeedsConsent -> Token.Consent
        is TokenResult.Failed -> Token.Transient
      }

  private suspend fun connectedEmail(): String? =
      normalizeCalendarEmail(calendarIdentity.connectedCalendarEmail.first())

  private suspend fun missingOrUnknownResult(scheduledId: Long): ScheduleResult =
      if (scheduledWorkoutDao.getById(scheduledId) == null) ScheduleResult.Success
      else ScheduleResult.Failure(OWNER_UNKNOWN)

  private suspend fun ownedTuple(scheduledId: Long): OwnedTuple? =
      database.withTransaction {
        val scheduled = scheduledWorkoutDao.getById(scheduledId) ?: return@withTransaction null
        val link = linkDao.getByScheduledId(scheduledId) ?: return@withTransaction null
        val owner = normalizeCalendarEmail(link.ownerEmail) ?: return@withTransaction null
        if (link.state != CalendarEventAccountLinkState.OWNED) return@withTransaction null
        OwnedTuple(scheduled, link, owner)
      }

  private suspend fun tupleIsCurrent(tuple: OwnedTuple): Boolean =
      database.withTransaction {
        connectedEmail() == tuple.owner &&
            scheduledWorkoutDao.getById(tuple.scheduled.id) == tuple.scheduled &&
            linkDao.getByScheduledId(tuple.scheduled.id) == tuple.link
      }

  private suspend fun deleteCurrentTuple(tuple: OwnedTuple): ScheduleResult =
      database.withTransaction {
        if (
            connectedEmail() != tuple.owner ||
                scheduledWorkoutDao.getById(tuple.scheduled.id) != tuple.scheduled ||
                linkDao.getByScheduledId(tuple.scheduled.id) != tuple.link
        ) {
          return@withTransaction ScheduleResult.Failure(ACCOUNT_CHANGED)
        }
        scheduledWorkoutDao.delete(tuple.scheduled.id)
        ScheduleResult.Success
      }

  private fun insertFailureMessage(code: Int): String =
      when (code) {
        401,
        403 -> NO_CALENDAR_ACCESS
        else -> "Не удалось создать событие (HTTP $code)"
      }

  private fun deleteFailureMessage(code: Int): String =
      when (code) {
        401,
        403 -> NO_CALENDAR_ACCESS
        else -> "Не удалось удалить событие (HTTP $code)"
      }

  private sealed interface Token {
    data class Ok(val bearer: String) : Token

    data object Consent : Token

    data object Transient : Token
  }

  private data class OwnedTuple(
      val scheduled: ScheduledWorkoutEntity,
      val link: CalendarEventAccountLinkEntity,
      val owner: String,
  )

  private data object StaleCalendarOperationException : RuntimeException()

  private companion object {
    const val EVENT_DURATION_SECONDS = 60L * 60L
    const val REMINDER_MINUTES = 30
    const val HTTP_NOT_FOUND = 404
    const val HTTP_GONE = 410

    const val NO_CALENDAR_ACCESS = "Нет доступа к календарю"
    const val OWNER_UNKNOWN = "Владелец события неизвестен — отмените его в Google Календаре"
    const val ACCOUNT_CHANGED = "Подключите Google-аккаунт владельца события"
    const val LOCAL_LINK_FAILURE = "Не удалось сохранить связь с событием — повторите позже"
  }
}
