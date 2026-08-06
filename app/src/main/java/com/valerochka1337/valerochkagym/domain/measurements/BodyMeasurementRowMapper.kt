package com.valerochka1337.valerochkagym.domain.measurements

import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Одна строка `Measurements` в Google Sheets на один локальный замер.
 *
 * `measurement_id` — ключ идемпотентности: перед append репозиторий читает колонку A и не
 * добавляет повторную строку, если воркер перезапустился после успешного запроса. Правки и
 * удаление локальной записи намеренно не меняют уже добавленную строку (append-only экспорт).
 */
object BodyMeasurementRowMapper {

    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    val HEADER_ROW: List<String> = listOf(
        "measurement_id",
        "date",
        "time",
        "weight_kg",
        "skeletal_muscle_mass_kg",
        "body_fat_percentage",
        "body_fat_mass_kg",
        "visceral_fat_level",
        "whr",
        "waist_cm",
        "chest_cm",
        "hips_cm",
        "right_relaxed_arm_cm",
        "right_thigh_cm",
    )

    fun row(measurement: BodyMeasurementEntity, zone: ZoneId = ZoneId.systemDefault()): List<Any?> {
        val zoned = Instant.ofEpochMilli(measurement.measuredAt).atZone(zone)
        return listOf(
            measurement.id,
            DATE_FORMATTER.format(zoned),
            TIME_FORMATTER.format(zoned),
            measurement.weightKg,
            measurement.skeletalMuscleMassKg,
            measurement.bodyFatPercentage,
            calculateBodyFatMassKg(measurement.weightKg, measurement.bodyFatPercentage),
            measurement.visceralFatLevel,
            measurement.effectiveWaistHipRatio(),
            measurement.waistCm,
            measurement.chestCm,
            measurement.hipsCm,
            measurement.rightRelaxedArmCm,
            measurement.rightThighCm,
        )
    }
}
