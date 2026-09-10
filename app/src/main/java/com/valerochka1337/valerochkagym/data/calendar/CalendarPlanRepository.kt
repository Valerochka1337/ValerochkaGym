package com.valerochka1337.valerochkagym.data.calendar

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.CalendarPlanDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionKind
import com.valerochka1337.valerochkagym.data.db.entity.CalendarMigrationPhase
import com.valerochka1337.valerochkagym.data.db.entity.CalendarPlanEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarRuleEntity
import com.valerochka1337.valerochkagym.data.db.relation.CalendarPlanWithRoutine
import com.valerochka1337.valerochkagym.data.db.relation.CalendarRuleWithRoutine
import com.valerochka1337.valerochkagym.data.schedule.DayRule
import com.valerochka1337.valerochkagym.data.schedule.WeeklySchedule
import com.valerochka1337.valerochkagym.di.ComputeDispatcher
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

sealed interface CalendarPlanResult {
  data object Success : CalendarPlanResult

  data class Failure(val message: String) : CalendarPlanResult
}

interface CalendarPlanRepository {
  fun observeInstancesIn(
      range: LocalDateRange,
      displayZoneSnapshot: ZoneId,
  ): Flow<CalendarPlanReadState>

  fun observePlans(): Flow<List<CalendarPlanWithRoutine>>

  fun observeRules(): Flow<List<CalendarRuleWithRoutine>>

  fun observeWeeklySchedule(): Flow<WeeklySchedule>

  suspend fun createPlan(
      commandId: String,
      routineId: Long,
      startsAtMillis: Long,
      zone: ZoneId,
  ): CalendarPlanResult

  suspend fun cancelPlan(planId: String): CalendarPlanResult

  suspend fun movePlan(planId: String, startsAtMillis: Long, zone: ZoneId): CalendarPlanResult

  suspend fun replaceWeeklySchedule(schedule: WeeklySchedule, zone: ZoneId): CalendarPlanResult

  suspend fun clearWeeklySchedule(): CalendarPlanResult

  suspend fun setException(
      ruleId: String,
      instanceDate: LocalDate,
      kind: CalendarExceptionKind,
      movedAtMillis: Long?,
  ): CalendarPlanResult
}

/** Room is authoritative; cloud and Google metadata have no place in this write path. */
@Singleton
class RoomCalendarPlanRepository
@Inject
constructor(
    private val database: GymDatabase,
    private val dao: CalendarPlanDao,
    private val routineDao: RoutineDao,
    @ComputeDispatcher private val computeDispatcher: CoroutineDispatcher,
    private val migrationGate: CalendarMigrationGate = PersistedCalendarMigrationGate(database),
    private val clock: CalendarMigrationClock = CalendarMigrationClock(),
) : CalendarPlanRepository {
  override fun observeInstancesIn(
      range: LocalDateRange,
      displayZoneSnapshot: ZoneId,
  ): Flow<CalendarPlanReadState> =
      combine(
              dao.observePlansWithRoutines(),
              dao.observeRulesWithRoutines(),
              dao.observeExceptions(),
              dao.observeMigrationState(),
          ) { _, _, _, _ ->
            // The flows are invalidation signals only: read a coherent multi-table snapshot in
            // Room.
            database.withTransaction {
              if (dao.migrationState()?.phase != CalendarMigrationPhase.READY)
                  CalendarPlanReadState.Migrating
              else
                  CalendarPlanReadState.Ready(
                      CalendarInstances.resolveIn(
                          dao.plansWithRoutines(),
                          dao.rulesWithRoutines(),
                          dao.allExceptions(),
                          range,
                          displayZoneSnapshot,
                      )
                  )
            }
          }
          .distinctUntilChanged()
          .catch { failure ->
            if (failure is CancellationException) throw failure
            emit(CalendarPlanReadState.Error("Не удалось прочитать календарь"))
          }
          .flowOn(computeDispatcher)

  override fun observePlans(): Flow<List<CalendarPlanWithRoutine>> = dao.observePlansWithRoutines()

  override fun observeRules(): Flow<List<CalendarRuleWithRoutine>> = dao.observeRulesWithRoutines()

  override fun observeWeeklySchedule(): Flow<WeeklySchedule> =
      dao.observeRules()
          .map { rules ->
            WeeklySchedule(
                rules.map {
                  val time = LocalTime.parse(it.localTime)
                  DayRule(it.isoDay, it.routineId, time.hour, time.minute)
                }
            )
          }
          .flowOn(computeDispatcher)

  override suspend fun createPlan(
      commandId: String,
      routineId: Long,
      startsAtMillis: Long,
      zone: ZoneId,
  ): CalendarPlanResult = write {
    require(UUID.fromString(commandId).toString() == commandId)
    CalendarTimeResolver.requirePlanInstant(startsAtMillis, zone)
    if (routineDao.getRoutineName(routineId) == null)
        return@write CalendarPlanResult.Failure("Программа не найдена")
    val existing = dao.plan(commandId)
    if (existing != null) {
      return@write if (
          existing.routineId == routineId &&
              existing.startsAtMillis == startsAtMillis &&
              existing.timeZoneId == zone.id &&
              existing.legacyScheduleId == null
      )
          CalendarPlanResult.Success
      else CalendarPlanResult.Failure("Команда планирования уже использована с другими данными")
    }
    dao.insertPlan(CalendarPlanEntity(commandId, routineId, startsAtMillis, zone.id))
    CalendarPlanResult.Success
  }

  override suspend fun cancelPlan(planId: String): CalendarPlanResult = write {
    dao.deletePlan(planId)
    CalendarPlanResult.Success
  }

  override suspend fun movePlan(
      planId: String,
      startsAtMillis: Long,
      zone: ZoneId,
  ): CalendarPlanResult = write {
    CalendarTimeResolver.requirePlanInstant(startsAtMillis, zone)
    val plan = dao.plan(planId) ?: return@write CalendarPlanResult.Failure("План не найден")
    dao.upsertPlan(plan.copy(startsAtMillis = startsAtMillis, timeZoneId = zone.id))
    CalendarPlanResult.Success
  }

  override suspend fun replaceWeeklySchedule(
      schedule: WeeklySchedule,
      zone: ZoneId,
  ): CalendarPlanResult = write {
    database.withTransaction {
      if (schedule.rules.map { it.isoDay }.distinct().size != schedule.rules.size)
          return@withTransaction CalendarPlanResult.Failure(
              "Для дня можно задать только одну тренировку"
          )
      val anchor = clock.localDate(zone)
      val validated =
          schedule.rules.map { rule ->
            val time = LocalTime.of(rule.hour, rule.minute)
            CalendarTimeResolver.requireRule(anchor, time)
            if (rule.isoDay !in 1..7 || routineDao.getRoutineWithExercises(rule.routineId) == null)
                return@withTransaction CalendarPlanResult.Failure("Программа не найдена")
            rule to time
          }
      val existing = dao.rules().toMutableList()
      val replacements = mutableListOf<CalendarRuleEntity>()
      validated.forEach { (rule, time) ->
        val localTime = "%02d:%02d".format(time.hour, time.minute)
        val unchangedIdentity =
            existing.firstOrNull {
              it.isoDay == rule.isoDay && it.localTime == localTime && it.timeZoneId == zone.id
            }
        if (unchangedIdentity != null) {
          existing.remove(unchangedIdentity)
          if (unchangedIdentity.routineId != rule.routineId)
              dao.upsertRule(unchangedIdentity.copy(routineId = rule.routineId))
        } else {
          // New UI rules are command identities, never migration's deterministic legacy IDs.
          replacements +=
              CalendarRuleEntity(
                  UUID.randomUUID().toString(),
                  rule.routineId,
                  rule.isoDay,
                  localTime,
                  zone.id,
                  anchor.toString(),
              )
        }
      }
      // Removing children first produces a portable tombstone graph that has no live orphan.
      existing.forEach { dao.deleteExceptions(it.id) }
      existing.forEach { dao.deleteRule(it.id) }
      replacements.forEach { dao.upsertRule(it) }
      CalendarPlanResult.Success
    }
  }

  override suspend fun clearWeeklySchedule(): CalendarPlanResult = write {
    database.withTransaction {
      dao.deleteAllExceptions()
      dao.deleteAllRules()
      CalendarPlanResult.Success
    }
  }

  override suspend fun setException(
      ruleId: String,
      instanceDate: LocalDate,
      kind: CalendarExceptionKind,
      movedAtMillis: Long?,
  ): CalendarPlanResult = write {
    val rule = dao.rule(ruleId) ?: return@write CalendarPlanResult.Failure("Правило не найдено")
    val time = LocalTime.parse(rule.localTime)
    val zone = ZoneId.of(rule.timeZoneId)
    CalendarTimeResolver.requireExceptionDate(instanceDate)
    require(
        instanceDate >= LocalDate.parse(rule.startLocalDate) &&
            instanceDate.dayOfWeek.value == rule.isoDay
    )
    if ((kind == CalendarExceptionKind.CANCELLED) != (movedAtMillis == null))
        return@write CalendarPlanResult.Failure("Некорректное исключение")
    movedAtMillis?.let { CalendarTimeResolver.requirePlanInstant(it, zone) }
    val key = CalendarTimeResolver.instanceKey(instanceDate, time, zone)
    dao.upsertException(
        CalendarExceptionEntity(
            CalendarTimeResolver.exceptionId(ruleId, key),
            ruleId,
            key,
            kind,
            movedAtMillis,
        )
    )
    CalendarPlanResult.Success
  }

  private suspend fun write(block: suspend () -> CalendarPlanResult): CalendarPlanResult =
      try {
        withContext(computeDispatcher) {
          // A migration may suspend while an account transition replaces the local personal
          // cache. Capture the owner epoch before it starts, then require the same epoch inside
          // the Room transaction so a reused numeric routine id cannot receive a stale write.
          val ownerEpoch = backendOwnerEpoch()
          if (!migrationGate.ensureReady())
              CalendarPlanResult.Failure("Подготовка календаря ещё не завершена")
          else
              database.withTransaction {
                if (backendOwnerEpoch() != ownerEpoch)
                    CalendarPlanResult.Failure("Аккаунт изменился — повторите действие")
                else block()
              }
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: IllegalArgumentException) {
        CalendarPlanResult.Failure("Некорректные данные календаря")
      } catch (_: Exception) {
        CalendarPlanResult.Failure("Не удалось сохранить календарь")
      }

  private fun backendOwnerEpoch(): Pair<String?, Long>? {
    val db = database.openHelper.writableDatabase
    val hasState =
        db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='backend_state'").use {
          it.moveToFirst()
        }
    if (!hasState) return null
    return db.query("SELECT owner,generation FROM backend_state WHERE id=1").use { cursor ->
      if (!cursor.moveToFirst()) null
      else (if (cursor.isNull(0)) null else cursor.getString(0)) to cursor.getLong(1)
    }
  }
}
