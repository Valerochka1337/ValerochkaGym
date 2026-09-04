package com.valerochka1337.valerochkagym.ui.measurements

import com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementMetric
import com.valerochka1337.valerochkagym.domain.measurements.MeasurementPeriod
import com.valerochka1337.valerochkagym.ui.analysis.formatDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val HISTORY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val CHART_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")

fun MeasurementPeriod.displayName(): String =
    when (this) {
      MeasurementPeriod.MONTHS_3 -> "3 мес."
      MeasurementPeriod.MONTHS_6 -> "6 мес."
      MeasurementPeriod.YEAR -> "Год"
      MeasurementPeriod.ALL -> "Всё"
    }

/** Подпись значения включает единицу: таблица под графиком не должна зависеть только от оси. */
fun formatMeasurementValue(metric: BodyMeasurementMetric, value: Double): String =
    when (metric) {
      BodyMeasurementMetric.WAIST_HIP_RATIO -> formatDecimal(value, digits = 2)
      BodyMeasurementMetric.VISCERAL_FAT -> "${formatDecimal(value, digits = 0)} ур."
      BodyMeasurementMetric.BODY_FAT_PERCENTAGE -> "${formatDecimal(value)} %"
      else -> "${formatDecimal(value)} ${metric.unit}"
    }

fun formatMeasurementDelta(metric: BodyMeasurementMetric, delta: Double): String {
  val sign = if (delta >= 0.0) "+" else "−"
  return sign + formatMeasurementValue(metric, abs(delta))
}

fun formatMeasurementDate(millis: Long, zone: ZoneId): String =
    HISTORY_DATE_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(zone))

fun formatMeasurementChartDate(millis: Long, zone: ZoneId): String =
    CHART_DATE_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(zone))
