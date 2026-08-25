package com.valerochka1337.valerochkagym.domain.measurements

import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Разобранные строки `Measurements` и число строк с UUID, которые нельзя восстановить. */
data class ParsedMeasurements(
    val measurements: List<BodyMeasurementEntity>,
    val skippedRows: Int,
)

/**
 * Обратный к [BodyMeasurementRowMapper]. Поддерживает как полный формат A:AP, так и ранний
 * A:N: отсутствующие после N показатели остаются null. UUID — ключ дедупликации, поэтому
 * импортированные записи сразу получают [UploadStatus.UPLOADED] и не отправляются обратно.
 */
object BodyMeasurementRowParser {

    /** Строгий ISO-форматтер не превращает 2026-02-30 молча в другую дату. */
    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun parse(rows: List<List<String>>): ParsedMeasurements {
        val zone = ZoneId.systemDefault()
        var skippedRows = 0
        val parsedById = LinkedHashMap<String, BodyMeasurementEntity>()

        rows.forEach { row ->
            val id = row.cell(MEASUREMENT_ID)
            if (id.isEmpty() || id == "measurement_id") return@forEach
            val measuredAt = parseMillis(row.cell(DATE), row.cell(TIME), zone)
            if (measuredAt == null) {
                skippedRows++
                return@forEach
            }
            parsedById[id] = BodyMeasurementEntity(
                id = id,
                measuredAt = measuredAt,
                weightKg = row.cell(WEIGHT_KG).toDoubleLoose(),
                skeletalMuscleMassKg = row.cell(SKELETAL_MUSCLE_MASS_KG).toDoubleLoose(),
                bodyFatPercentage = row.cell(BODY_FAT_PERCENTAGE).toDoubleLoose(),
                bodyFatMassKg = row.cell(BODY_FAT_MASS_KG).toDoubleLoose(),
                visceralFatLevel = row.cell(VISCERAL_FAT_LEVEL).toIntLoose(),
                waistHipRatio = row.cell(WHR).toDoubleLoose(),
                waistCm = row.cell(WAIST_CM).toDoubleLoose(),
                chestCm = row.cell(CHEST_CM).toDoubleLoose(),
                hipsCm = row.cell(HIPS_CM).toDoubleLoose(),
                rightRelaxedArmCm = row.cell(RIGHT_RELAXED_ARM_CM).toDoubleLoose(),
                rightThighCm = row.cell(RIGHT_THIGH_CM).toDoubleLoose(),
                inBodyScore = row.cell(INBODY_SCORE).toIntLoose(),
                totalBodyWaterLiters = row.cell(TOTAL_BODY_WATER_L).toDoubleLoose(),
                proteinKg = row.cell(PROTEIN_KG).toDoubleLoose(),
                mineralsKg = row.cell(MINERALS_KG).toDoubleLoose(),
                bodyMassIndex = row.cell(BODY_MASS_INDEX).toDoubleLoose(),
                fatFreeMassKg = row.cell(FAT_FREE_MASS_KG).toDoubleLoose(),
                basalMetabolicRateKcal = row.cell(BASAL_METABOLIC_RATE_KCAL).toIntLoose(),
                recommendedCalorieIntakeKcal = row.cell(RECOMMENDED_CALORIE_INTAKE_KCAL).toIntLoose(),
                leftArmLeanMassKg = row.cell(LEFT_ARM_LEAN_MASS_KG).toDoubleLoose(),
                leftArmLeanPercentage = row.cell(LEFT_ARM_LEAN_PERCENTAGE).toDoubleLoose(),
                leftArmFatMassKg = row.cell(LEFT_ARM_FAT_MASS_KG).toDoubleLoose(),
                leftArmFatPercentage = row.cell(LEFT_ARM_FAT_PERCENTAGE).toDoubleLoose(),
                rightArmLeanMassKg = row.cell(RIGHT_ARM_LEAN_MASS_KG).toDoubleLoose(),
                rightArmLeanPercentage = row.cell(RIGHT_ARM_LEAN_PERCENTAGE).toDoubleLoose(),
                rightArmFatMassKg = row.cell(RIGHT_ARM_FAT_MASS_KG).toDoubleLoose(),
                rightArmFatPercentage = row.cell(RIGHT_ARM_FAT_PERCENTAGE).toDoubleLoose(),
                trunkLeanMassKg = row.cell(TRUNK_LEAN_MASS_KG).toDoubleLoose(),
                trunkLeanPercentage = row.cell(TRUNK_LEAN_PERCENTAGE).toDoubleLoose(),
                trunkFatMassKg = row.cell(TRUNK_FAT_MASS_KG).toDoubleLoose(),
                trunkFatPercentage = row.cell(TRUNK_FAT_PERCENTAGE).toDoubleLoose(),
                leftLegLeanMassKg = row.cell(LEFT_LEG_LEAN_MASS_KG).toDoubleLoose(),
                leftLegLeanPercentage = row.cell(LEFT_LEG_LEAN_PERCENTAGE).toDoubleLoose(),
                leftLegFatMassKg = row.cell(LEFT_LEG_FAT_MASS_KG).toDoubleLoose(),
                leftLegFatPercentage = row.cell(LEFT_LEG_FAT_PERCENTAGE).toDoubleLoose(),
                rightLegLeanMassKg = row.cell(RIGHT_LEG_LEAN_MASS_KG).toDoubleLoose(),
                rightLegLeanPercentage = row.cell(RIGHT_LEG_LEAN_PERCENTAGE).toDoubleLoose(),
                rightLegFatMassKg = row.cell(RIGHT_LEG_FAT_MASS_KG).toDoubleLoose(),
                rightLegFatPercentage = row.cell(RIGHT_LEG_FAT_PERCENTAGE).toDoubleLoose(),
                uploadStatus = UploadStatus.UPLOADED,
                uploadError = null,
            )
        }
        return ParsedMeasurements(parsedById.values.toList(), skippedRows)
    }

    private fun List<String>.cell(index: Int): String = getOrNull(index)?.trim().orEmpty()

    private fun String.toDoubleLoose(): Double? =
        takeIf(String::isNotEmpty)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf(Double::isFinite)

    private fun String.toIntLoose(): Int? {
        val value = toDoubleLoose() ?: return null
        val asInt = value.toInt()
        return asInt.takeIf { value == it.toDouble() }
    }

    private fun parseMillis(date: String, time: String, zone: ZoneId): Long? = try {
        val localDate = LocalDate.parse(date, DATE_FORMATTER)
        val localTime = LocalTime.parse(time)
        LocalDateTime.of(localDate, localTime).atZone(zone).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }

    private const val MEASUREMENT_ID = 0
    private const val DATE = 1
    private const val TIME = 2
    private const val WEIGHT_KG = 3
    private const val SKELETAL_MUSCLE_MASS_KG = 4
    private const val BODY_FAT_PERCENTAGE = 5
    private const val BODY_FAT_MASS_KG = 6
    private const val VISCERAL_FAT_LEVEL = 7
    private const val WHR = 8
    private const val WAIST_CM = 9
    private const val CHEST_CM = 10
    private const val HIPS_CM = 11
    private const val RIGHT_RELAXED_ARM_CM = 12
    private const val RIGHT_THIGH_CM = 13
    private const val INBODY_SCORE = 14
    private const val TOTAL_BODY_WATER_L = 15
    private const val PROTEIN_KG = 16
    private const val MINERALS_KG = 17
    private const val BODY_MASS_INDEX = 18
    private const val FAT_FREE_MASS_KG = 19
    private const val BASAL_METABOLIC_RATE_KCAL = 20
    private const val RECOMMENDED_CALORIE_INTAKE_KCAL = 21
    private const val LEFT_ARM_LEAN_MASS_KG = 22
    private const val LEFT_ARM_LEAN_PERCENTAGE = 23
    private const val LEFT_ARM_FAT_MASS_KG = 24
    private const val LEFT_ARM_FAT_PERCENTAGE = 25
    private const val RIGHT_ARM_LEAN_MASS_KG = 26
    private const val RIGHT_ARM_LEAN_PERCENTAGE = 27
    private const val RIGHT_ARM_FAT_MASS_KG = 28
    private const val RIGHT_ARM_FAT_PERCENTAGE = 29
    private const val TRUNK_LEAN_MASS_KG = 30
    private const val TRUNK_LEAN_PERCENTAGE = 31
    private const val TRUNK_FAT_MASS_KG = 32
    private const val TRUNK_FAT_PERCENTAGE = 33
    private const val LEFT_LEG_LEAN_MASS_KG = 34
    private const val LEFT_LEG_LEAN_PERCENTAGE = 35
    private const val LEFT_LEG_FAT_MASS_KG = 36
    private const val LEFT_LEG_FAT_PERCENTAGE = 37
    private const val RIGHT_LEG_LEAN_MASS_KG = 38
    private const val RIGHT_LEG_LEAN_PERCENTAGE = 39
    private const val RIGHT_LEG_FAT_MASS_KG = 40
    private const val RIGHT_LEG_FAT_PERCENTAGE = 41
}
