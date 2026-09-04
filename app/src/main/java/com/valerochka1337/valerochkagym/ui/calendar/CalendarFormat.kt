package com.valerochka1337.valerochkagym.ui.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU: Locale = Locale.forLanguageTag("ru")

/** Кол-во ячеек сетки месяца: 6 недель × 7 дней. Фиксированная высота — сетка не «прыгает». */
const val CALENDAR_CELLS: Int = 42

/** Заголовок месяца в именительном падеже с заглавной буквы: «Август 2026». */
fun monthTitle(yearMonth: YearMonth): String {
  val raw = yearMonth.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", RU))
  return raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(RU) else it.toString() }
}

/** Дата в родительном падеже для заголовка шторки дня: «3 августа». */
fun dayTitle(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMMM", RU))

/** Время «HH:mm» момента [millis] в зоне [zone]. */
fun timeLabel(millis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))

/**
 * 42 ячейки сетки месяца [yearMonth], начиная с понедельника недели, содержащей 1-е число, и
 * заканчивая заполнением до 6 недель. Ячейки вне [yearMonth] помечены `inMonth = false`.
 *
 * Точка ячейки: [DotStyle.Completed] (залитая), если в этот день была завершённая тренировка
 * ([completedDays]); иначе [DotStyle.Planned] (контурная) для сегодня/будущего, если день
 * запланирован — есть ad-hoc ([adHocDays]) ИЛИ его день недели входит в недельное расписание
 * ([weeklyIsoDays]); иначе [DotStyle.None]. Точки считаются только для дней внутри месяца.
 */
fun buildMonthCells(
    yearMonth: YearMonth,
    today: LocalDate,
    completedDays: Set<LocalDate>,
    adHocDays: Set<LocalDate>,
    weeklyIsoDays: Set<Int>,
): List<DayCellUi> {
  val firstOfMonth = yearMonth.atDay(1)
  val leadingBlanks = firstOfMonth.dayOfWeek.value - 1 // Пн = 0 пустых слева
  val start = firstOfMonth.minusDays(leadingBlanks.toLong())
  return (0 until CALENDAR_CELLS).map { offset ->
    val date = start.plusDays(offset.toLong())
    val inMonth = YearMonth.from(date) == yearMonth
    DayCellUi(
        date = date,
        inMonth = inMonth,
        isToday = date == today,
        dot =
            if (!inMonth) DotStyle.None
            else dotFor(date, today, completedDays, adHocDays, weeklyIsoDays),
    )
  }
}

private fun dotFor(
    date: LocalDate,
    today: LocalDate,
    completedDays: Set<LocalDate>,
    adHocDays: Set<LocalDate>,
    weeklyIsoDays: Set<Int>,
): DotStyle =
    when {
      date in completedDays -> DotStyle.Completed
      !date.isBefore(today) && (date in adHocDays || date.dayOfWeek.value in weeklyIsoDays) ->
          DotStyle.Planned
      else -> DotStyle.None
    }
