package com.valerochka1337.valerochkagym.ui.analysis

import com.valerochka1337.valerochkagym.domain.analysis.AnalysisPeriod
import com.valerochka1337.valerochkagym.domain.analysis.BalanceId
import com.valerochka1337.valerochkagym.domain.analysis.TrendVerdict
import com.valerochka1337.valerochkagym.domain.analysis.VolumeZone
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Подписи и форматирование чисел вкладки «Анализы».
 *
 * Числа форматируются локаль-независимо через [BigDecimal] (как в итогах тренировки): результат
 * не зависит от языка устройства, а хвостовые нули не превращают «12» в «12.0».
 */
private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")
private val DATE_YEAR_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy")

/** Число с [digits] знаками после запятой, без хвостовых нулей. */
fun formatDecimal(value: Double, digits: Int = 1): String =
    BigDecimal.valueOf(value)
        .setScale(digits, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()

/** Вес в килограммах, округлённый до целого — доли килограмма в аналитике не значимы. */
fun formatKg(value: Double): String = "${value.roundToInt()} кг"

/** Тоннаж: до тонны — в килограммах, дальше в тоннах, иначе число не читается. */
fun formatTonnage(kg: Double): String =
    if (kg < 1_000) "${kg.roundToInt()} кг" else "${formatDecimal(kg / 1_000)} т"

/** Длительность в минутах как «1 ч 05 мин» / «45 мин». */
fun formatMinutes(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60} ч ${"%02d".format(minutes % 60)} мин" else "$minutes мин"

fun formatDate(millis: Long, zone: ZoneId): String =
    DATE_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(zone))

fun formatDateWithYear(millis: Long, zone: ZoneId): String =
    DATE_YEAR_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(zone))

/** Отношение как «1.2 : 1» — так виден перекос, в отличие от «1.2». */
fun formatRatio(ratio: Double): String = "${formatDecimal(ratio)} : 1"

/** Знаковое изменение, например «+2.5 кг/мес». */
fun formatSigned(value: Double, unit: String): String {
    val sign = if (value >= 0) "+" else "−"
    return "$sign${formatDecimal(abs(value))} $unit"
}

fun AnalysisPeriod.displayName(): String = when (this) {
    AnalysisPeriod.WEEKS_4 -> "4 недели"
    AnalysisPeriod.WEEKS_12 -> "12 недель"
    AnalysisPeriod.YEAR -> "Год"
    AnalysisPeriod.ALL -> "Всё"
}

fun VolumeZone.displayName(): String = when (this) {
    VolumeZone.NONE -> "нет нагрузки"
    VolumeZone.BELOW_MEV -> "мало"
    VolumeZone.MAINTENANCE -> "поддержка"
    VolumeZone.OPTIMAL -> "норма"
    VolumeZone.EXCESSIVE -> "перебор"
}

fun TrendVerdict.displayName(): String = when (this) {
    TrendVerdict.NOT_ENOUGH_DATA -> "мало данных"
    TrendVerdict.GROWING -> "растёт"
    TrendVerdict.STALLED -> "плато"
    TrendVerdict.REGRESSING -> "снижается"
}

fun BalanceId.title(): String = when (this) {
    BalanceId.PUSH_PULL -> "Жим / тяга"
    BalanceId.ANTERIOR_POSTERIOR -> "Перёд / зад"
    BalanceId.UPPER_LOWER -> "Верх / низ"
    BalanceId.QUAD_HAMSTRING -> "Бицепс бедра / квадрицепс"
}

/** Что означает перекос влево и вправо — без этого diverging-график не прочитать. */
fun BalanceId.sideLabels(): Pair<String, String> = when (this) {
    BalanceId.PUSH_PULL -> "больше тяги" to "больше жима"
    BalanceId.ANTERIOR_POSTERIOR -> "больше зада" to "больше переда"
    BalanceId.UPPER_LOWER -> "больше низа" to "больше верха"
    BalanceId.QUAD_HAMSTRING -> "больше квадрицепса" to "больше бицепса бедра"
}
