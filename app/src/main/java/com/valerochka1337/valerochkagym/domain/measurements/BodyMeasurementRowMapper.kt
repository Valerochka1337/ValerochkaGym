package com.valerochka1337.valerochkagym.domain.measurements

import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Одна строка `Measurements` в Google Sheets на один локальный замер.
 *
 * `measurement_id` — ключ идемпотентности: перед append репозиторий читает колонку A и не добавляет
 * повторную строку, если воркер перезапустился после успешного запроса. Правки и удаление локальной
 * записи намеренно не меняют уже добавленную строку (append-only экспорт).
 */
object BodyMeasurementRowMapper {

  private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
  private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

  val HEADER_ROW: List<String> =
      listOf(
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
}
