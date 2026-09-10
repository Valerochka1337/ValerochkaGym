package com.valerochka1337.valerochkagym.data.calendar

import com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarExceptionKind
import com.valerochka1337.valerochkagym.data.db.relation.CalendarPlanWithRoutine
import com.valerochka1337.valerochkagym.data.db.relation.CalendarRuleWithRoutine
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Inclusive display dates; recurrence expansion is always bounded to the requested surface. */
data class LocalDateRange(val start: LocalDate, val endInclusive: LocalDate) {
  init {
    require(!endInclusive.isBefore(start))
    require(ChronoUnit.DAYS.between(start, endInclusive) <= 366)
  }
}

data class ResolvedCalendarInstance(
    val id: String,
    val planId: String?,
    val ruleId: String?,
    val instanceKey: String?,
    val routineId: Long,
    val routineName: String,
    val startsAtMillis: Long,
    val moved: Boolean = false,
)

sealed interface CalendarPlanReadState {
  data object Migrating : CalendarPlanReadState

  data class Ready(val instances: List<ResolvedCalendarInstance>) : CalendarPlanReadState

  data class Error(val message: String) : CalendarPlanReadState
}

/** One resolver for the current calendar and later presentation surfaces; never reads Google. */
object CalendarInstances {
  fun resolveIn(
      plans: List<CalendarPlanWithRoutine>,
      rules: List<CalendarRuleWithRoutine>,
      exceptions: List<CalendarExceptionEntity>,
      range: LocalDateRange,
      displayZone: ZoneId,
  ): List<ResolvedCalendarInstance> {
    val from = range.start.atStartOfDay(displayZone).toInstant()
    val until = range.endInclusive.plusDays(1).atStartOfDay(displayZone).toInstant()
    fun includes(millis: Long): Boolean {
      val instant = Instant.ofEpochMilli(millis)
      return instant >= from && instant < until
    }
    val result = mutableListOf<ResolvedCalendarInstance>()
    plans
        .filter { includes(it.plan.startsAtMillis) }
        .forEach {
          result +=
              ResolvedCalendarInstance(
                  it.plan.id,
                  it.plan.id,
                  null,
                  null,
                  it.plan.routineId,
                  it.routineName,
                  it.plan.startsAtMillis,
              )
        }
    val byRule = exceptions.groupBy { it.ruleId }
    rules.forEach { row ->
      val rule = row.rule
      val zone = ZoneId.of(rule.timeZoneId)
      val time = LocalTime.parse(rule.localTime)
      val anchor = LocalDate.parse(rule.startLocalDate)
      val changes = byRule[rule.id].orEmpty().associateBy { it.instanceKey }
      fun add(date: LocalDate, at: Long, moved: Boolean) {
        if (!includes(at)) return
        val key = CalendarTimeResolver.instanceKey(date, time, zone)
        result +=
            ResolvedCalendarInstance(
                CalendarTimeResolver.exceptionId(rule.id, key),
                null,
                rule.id,
                key,
                rule.routineId,
                row.routineName,
                at,
                moved,
            )
      }
      // Only original dates whose instants can belong to the display interval are expanded.
      var date = maxOf(from.atZone(zone).toLocalDate(), anchor, LocalDate.of(1970, 1, 1))
      val last = minOf(until.minusNanos(1).atZone(zone).toLocalDate(), LocalDate.of(2100, 12, 31))
      while (date <= last) {
        if (date.dayOfWeek.value == rule.isoDay) {
          val key = CalendarTimeResolver.instanceKey(date, time, zone)
          if (key !in changes) add(date, CalendarTimeResolver.resolve(date, time, zone), false)
        }
        date = date.plusDays(1)
      }
      // Moved instances can arrive from outside the original range. Their identity never moves.
      changes.values
          .filter { it.kind == CalendarExceptionKind.MOVED }
          .forEach { change ->
            val original = LocalDate.parse(change.instanceKey.substringBefore('T'))
            require(change.instanceKey == CalendarTimeResolver.instanceKey(original, time, zone))
            require(original >= anchor && original.dayOfWeek.value == rule.isoDay)
            CalendarTimeResolver.requireExceptionDate(original)
            val at = requireNotNull(change.movedAtMillis)
            CalendarTimeResolver.requirePlanInstant(at, zone)
            add(original, at, true)
          }
    }
    return result.sortedWith(
        compareBy<ResolvedCalendarInstance> { it.startsAtMillis }.thenBy { it.id }
    )
  }
}
