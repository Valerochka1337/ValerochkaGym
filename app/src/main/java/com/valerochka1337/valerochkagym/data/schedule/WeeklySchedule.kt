package com.valerochka1337.valerochkagym.data.schedule

import kotlinx.serialization.Serializable

/**
 * Одно правило недельного расписания: в день недели [isoDay] в [hour]:[minute] проводится
 * тренировка по программе [routineId]. [calendarEventId] заполняется после успешной вставки
 * повторяющегося (RRULE) события в Google Calendar — по нему «Сохранить»/«Очистить» знают, какую
 * серию удалять при следующем сохранении. Отсутствие правила на день недели = в этот день тренировки
 * не запланировано (неактивные дни в [WeeklySchedule.rules] просто отсутствуют).
 *
 * [isoDay] — 1..7 в формате `java.time.DayOfWeek.value` (1 = понедельник, 7 = воскресенье).
 */
@Serializable
data class DayRule(
    val isoDay: Int,
    val routineId: Long,
    val hour: Int,
    val minute: Int,
    val calendarEventId: String? = null,
)

/**
 * Недельный шаблон расписания, целиком сериализуемый в один ключ DataStore. Содержит только активные
 * дни ([rules]); порядок не важен, но не должно быть двух правил на один [DayRule.isoDay].
 */
@Serializable
data class WeeklySchedule(
    val rules: List<DayRule> = emptyList(),
    val ownerEmail: String? = null,
)
