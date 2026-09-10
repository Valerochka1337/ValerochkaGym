package com.valerochka1337.valerochkagym.data.calendar

import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Frozen CAL-01 temporal and deterministic-identity rules. */
object CalendarTimeResolver {
  private val minimum = LocalDate.of(1970, 1, 1)
  private val maximum = LocalDate.of(2100, 12, 31)

  fun requirePlanInstant(startsAtMillis: Long, zone: ZoneId) {
    require(inBounds(java.time.Instant.ofEpochMilli(startsAtMillis).atZone(zone).toLocalDate()))
  }

  fun requireRule(date: LocalDate, time: LocalTime) {
    require(inBounds(date))
    require(!time.nano.let { it != 0 })
  }

  fun requireExceptionDate(date: LocalDate) = require(inBounds(date))

  fun resolve(date: LocalDate, time: LocalTime, zone: ZoneId): Long {
    val local = LocalDateTime.of(date, time)
    val offsets = zone.rules.getValidOffsets(local)
    return when {
      offsets.isNotEmpty() -> local.atOffset(offsets.first()).toInstant().toEpochMilli()
      else ->
          zone.rules.getTransition(local)!!.dateTimeAfter.atZone(zone).toInstant().toEpochMilli()
    }
  }

  fun instanceKey(date: LocalDate, time: LocalTime, zone: ZoneId): String =
      "${date}T${time}[${zone.id}]"

  fun exceptionId(ruleId: String, instanceKey: String): String =
      UUID.nameUUIDFromBytes(
              "ValerochkaGym.calendar-exception:v1:$ruleId:$instanceKey".toByteArray(UTF_8)
          )
          .toString()

  fun planId(legacyScheduleId: String): String =
      UUID.nameUUIDFromBytes("ValerochkaGym.calendar-plan:v1:$legacyScheduleId".toByteArray(UTF_8))
          .toString()

  fun ruleId(owner: String?, routineSyncId: String, isoDay: Int, localTime: String): String {
    val normalizedOwner = owner?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "∅"
    return UUID.nameUUIDFromBytes(
            listOf(
                    "ValerochkaGym.calendar-rule:v1",
                    normalizedOwner,
                    routineSyncId.lowercase(),
                    isoDay.toString(),
                    localTime,
                )
                .joinToString("\u001F")
                .toByteArray(UTF_8)
        )
        .toString()
  }

  private fun inBounds(date: LocalDate): Boolean = date in minimum..maximum
}
