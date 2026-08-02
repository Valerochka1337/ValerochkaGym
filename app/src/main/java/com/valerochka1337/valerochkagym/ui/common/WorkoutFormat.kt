package com.valerochka1337.valerochkagym.ui.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

private val RU: Locale = Locale.forLanguageTag("ru")

/** Дата тренировки в родительном падеже: «31 июля», с годом — «31 июля 2025», если год не текущий. */
internal fun formatWorkoutDate(startedAt: Long): String {
    val date = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val pattern = if (date.year == LocalDate.now().year) "d MMMM" else "d MMMM yyyy"
    return date.format(DateTimeFormatter.ofPattern(pattern, RU))
}

/** Дата и время начала тренировки: «31 июля, 19:30» (с годом, если год не текущий). */
internal fun formatWorkoutDateTime(startedAt: Long): String {
    val dateTime = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault())
    val datePattern = if (dateTime.year == LocalDate.now().year) "d MMMM" else "d MMMM yyyy"
    return dateTime.format(DateTimeFormatter.ofPattern("$datePattern, HH:mm", RU))
}

/** Длительность тренировки: «1 ч 05 мин» при наличии часов, иначе «45 мин». */
internal fun formatDuration(durationMs: Long): String {
    val totalMinutes = (durationMs.coerceAtLeast(0)) / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours ч %02d мин".format(minutes) else "$minutes мин"
}

/** Остаток отдыха как M:SS — пилюля на экране тренировки и плашка на вкладках. */
internal fun formatRestClock(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    return "%d:%02d".format(safe / 60, safe % 60)
}

/** Объём с пробелом-разделителем тысяч: «2 340 кг». Возвращает null, если объём не положителен. */
internal fun formatVolume(volume: Double): String? {
    val kg = volume.roundToLong()
    if (kg <= 0) return null
    val grouped = kg.toString()
        .reversed()
        .chunked(3)
        .joinToString(" ")
        .reversed()
    return "$grouped кг"
}
