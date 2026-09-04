package com.valerochka1337.valerochkagym.data.schedule

import java.time.LocalDate
import java.time.ZoneId

/** Аббревиатуры дней недели для RRULE `BYDAY`, индекс = `DayOfWeek.value - 1` (Пн..Вс). */
private val BY_DAY = arrayOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

/**
 * RRULE-код дня недели по [isoDay] (1 = понедельник .. 7 = воскресенье), например 1 → "MO".
 * Значения вне диапазона отсекаются исключением — вызывающий обязан передавать `DayOfWeek.value`.
 */
fun byDayOf(isoDay: Int): String {
  require(isoDay in 1..7) { "isoDay must be in 1..7, was $isoDay" }
  return BY_DAY[isoDay - 1]
}

/**
 * Момент (epoch millis) ближайшего наступления дня недели [isoDay] в [hour]:[minute] в зоне [zone],
 * начиная с даты [from] включительно. Если [from] сам приходится на нужный день недели — берётся
 * именно [from] (в этот день, независимо от того, прошло уже время суток или нет — DTSTART серии
 * фиксирует лишь стартовую дату, дальше повтор идёт по RRULE). Используется как `start.dateTime`
 * повторяющегося события: DTSTART подразумевается из этого значения.
 */
fun nextOccurrenceMillis(
    isoDay: Int,
    hour: Int,
    minute: Int,
    zone: ZoneId,
    from: LocalDate,
): Long {
  require(isoDay in 1..7) { "isoDay must be in 1..7, was $isoDay" }
  var date = from
  // Не более 7 шагов: дойти до ближайшего дня недели, совпадающего с isoDay.
  while (date.dayOfWeek.value != isoDay) {
    date = date.plusDays(1)
  }
  return date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}
