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
    /** Versioned rows keep epoch-millisecond identity instead of silently rounding to a minute. */
    private val SYNC_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    val LEGACY_HEADER_ROW: List<String> = listOf(
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
        "inbody_score",
        "total_body_water_l",
        "protein_kg",
        "minerals_kg",
        "body_mass_index",
        "fat_free_mass_kg",
        "basal_metabolic_rate_kcal",
        "recommended_calorie_intake_kcal",
        "left_arm_lean_mass_kg",
        "left_arm_lean_percentage",
        "left_arm_fat_mass_kg",
        "left_arm_fat_percentage",
        "right_arm_lean_mass_kg",
        "right_arm_lean_percentage",
        "right_arm_fat_mass_kg",
        "right_arm_fat_percentage",
        "trunk_lean_mass_kg",
        "trunk_lean_percentage",
        "trunk_fat_mass_kg",
        "trunk_fat_percentage",
        "left_leg_lean_mass_kg",
        "left_leg_lean_percentage",
        "left_leg_fat_mass_kg",
        "left_leg_fat_percentage",
        "right_leg_lean_mass_kg",
        "right_leg_lean_percentage",
        "right_leg_fat_mass_kg",
        "right_leg_fat_percentage",
    )

    /** The formerly published A:AU contract, retained for safe header upgrades. */
    val INTERIM_HEADER_ROW: List<String> = LEGACY_HEADER_ROW + listOf(
        "version", "updated_at", "is_deleted", "payload_hash", "idempotency_key",
    )

    /** A:AY is append-only primary data; user-owned columns begin only after AY. */
    val HEADER_ROW: List<String> = INTERIM_HEADER_ROW + listOf(
        "after_meal", "after_workout", "unusual_hydration", "condition_note",
    )

    /**
     * Historical pre-versioned presentation row. It keeps legacy calculated cells for imports
     * made before the immutable snapshot contract; new sync code must use [primaryRow].
     */
    fun row(measurement: BodyMeasurementEntity, zone: ZoneId = ZoneId.systemDefault()): List<Any?> {
        val zoned = Instant.ofEpochMilli(measurement.measuredAt).atZone(zone)
        return listOf(
            measurement.id,
            DATE_FORMATTER.format(zoned),
            TIME_FORMATTER.format(zoned),
            measurement.weightKg,
            measurement.skeletalMuscleMassKg,
            measurement.bodyFatPercentage,
            measurement.bodyFatMassKg
                ?: calculateBodyFatMassKg(measurement.weightKg, measurement.bodyFatPercentage),
            measurement.visceralFatLevel,
            measurement.effectiveWaistHipRatio(),
            measurement.waistCm,
            measurement.chestCm,
            measurement.hipsCm,
            measurement.rightRelaxedArmCm,
            measurement.rightThighCm,
            measurement.inBodyScore,
            measurement.totalBodyWaterLiters,
            measurement.proteinKg,
            measurement.mineralsKg,
            measurement.bodyMassIndex,
            measurement.fatFreeMassKg,
            measurement.basalMetabolicRateKcal,
            measurement.recommendedCalorieIntakeKcal,
            measurement.leftArmLeanMassKg,
            measurement.leftArmLeanPercentage,
            measurement.leftArmFatMassKg,
            measurement.leftArmFatPercentage,
            measurement.rightArmLeanMassKg,
            measurement.rightArmLeanPercentage,
            measurement.rightArmFatMassKg,
            measurement.rightArmFatPercentage,
            measurement.trunkLeanMassKg,
            measurement.trunkLeanPercentage,
            measurement.trunkFatMassKg,
            measurement.trunkFatPercentage,
            measurement.leftLegLeanMassKg,
            measurement.leftLegLeanPercentage,
            measurement.leftLegFatMassKg,
            measurement.leftLegFatPercentage,
            measurement.rightLegLeanMassKg,
            measurement.rightLegLeanPercentage,
            measurement.rightLegFatMassKg,
            measurement.rightLegFatPercentage,
        )
    }

    /** Exact nullable primary fields for A:AY outbox snapshots; no derived values cross sync. */
    fun primaryRow(measurement: BodyMeasurementEntity, zone: ZoneId = ZoneId.systemDefault()): List<Any?> {
        val zoned = Instant.ofEpochMilli(measurement.measuredAt).atZone(zone)
        return listOf(
            measurement.id,
            DATE_FORMATTER.format(zoned),
            SYNC_TIME_FORMATTER.format(zoned),
            measurement.weightKg,
            measurement.skeletalMuscleMassKg,
            measurement.bodyFatPercentage,
            measurement.bodyFatMassKg,
            measurement.visceralFatLevel,
            measurement.waistHipRatio,
            measurement.waistCm,
            measurement.chestCm,
            measurement.hipsCm,
            measurement.rightRelaxedArmCm,
            measurement.rightThighCm,
            measurement.inBodyScore,
            measurement.totalBodyWaterLiters,
            measurement.proteinKg,
            measurement.mineralsKg,
            measurement.bodyMassIndex,
            measurement.fatFreeMassKg,
            measurement.basalMetabolicRateKcal,
            measurement.recommendedCalorieIntakeKcal,
            measurement.leftArmLeanMassKg,
            measurement.leftArmLeanPercentage,
            measurement.leftArmFatMassKg,
            measurement.leftArmFatPercentage,
            measurement.rightArmLeanMassKg,
            measurement.rightArmLeanPercentage,
            measurement.rightArmFatMassKg,
            measurement.rightArmFatPercentage,
            measurement.trunkLeanMassKg,
            measurement.trunkLeanPercentage,
            measurement.trunkFatMassKg,
            measurement.trunkFatPercentage,
            measurement.leftLegLeanMassKg,
            measurement.leftLegLeanPercentage,
            measurement.leftLegFatMassKg,
            measurement.leftLegFatPercentage,
            measurement.rightLegLeanMassKg,
            measurement.rightLegLeanPercentage,
            measurement.rightLegFatMassKg,
            measurement.rightLegFatPercentage,
        )
    }

    fun versionedRow(
        measurement: BodyMeasurementEntity,
        version: Long,
        updatedAt: Long,
        isDeleted: Boolean,
        payloadHash: String?,
        idempotencyKey: String,
        includeConditions: Boolean = true,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Any?> = primaryRow(measurement, zone) + listOf(
        version,
        updatedAt,
        isDeleted,
        payloadHash,
        idempotencyKey,
        if (includeConditions) measurement.afterMeal else null,
        if (includeConditions) measurement.afterWorkout else null,
        if (includeConditions) measurement.unusualHydration else null,
        if (includeConditions) measurement.conditionNote else null,
    )
}
