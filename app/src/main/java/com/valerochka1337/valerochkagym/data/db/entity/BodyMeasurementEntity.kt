package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Один замер тела. [measuredAt] хранит выбранную пользователем дату вместе с текущим временем: дата
 * нужна для тренда, а время позволяет не терять контекст при экспорте в Google Sheets.
 *
 * InBody и ручные обхваты намеренно nullable. Пустое поле — это отсутствующее измерение, а не ноль:
 * при построении тренда такие точки пропускаются, чтобы линия не сообщала несуществующее изменение.
 * Для ручной записи масса жира остаётся производной (`вес × процент жира`), а InBody может
 * сохранить напечатанное аппаратом округлённое значение в [bodyFatMassKg].
 */
@Entity(
    tableName = "body_measurements",
    indices = [Index("measuredAt"), Index("uploadStatus")],
)
data class BodyMeasurementEntity(
    @PrimaryKey val id: String,
    val measuredAt: Long,
    val weightKg: Double? = null,
    val skeletalMuscleMassKg: Double? = null,
    val bodyFatPercentage: Double? = null,
    val bodyFatMassKg: Double? = null,
    val visceralFatLevel: Int? = null,
    val waistHipRatio: Double? = null,
    val inBodyScore: Int? = null,
    val totalBodyWaterLiters: Double? = null,
    val proteinKg: Double? = null,
    val mineralsKg: Double? = null,
    val bodyMassIndex: Double? = null,
    val fatFreeMassKg: Double? = null,
    val basalMetabolicRateKcal: Int? = null,
    val recommendedCalorieIntakeKcal: Int? = null,
    val leftArmLeanMassKg: Double? = null,
    val leftArmLeanPercentage: Double? = null,
    val rightArmLeanMassKg: Double? = null,
    val rightArmLeanPercentage: Double? = null,
    val trunkLeanMassKg: Double? = null,
    val trunkLeanPercentage: Double? = null,
    val leftLegLeanMassKg: Double? = null,
    val leftLegLeanPercentage: Double? = null,
    val rightLegLeanMassKg: Double? = null,
    val rightLegLeanPercentage: Double? = null,
    val leftArmFatMassKg: Double? = null,
    val leftArmFatPercentage: Double? = null,
    val rightArmFatMassKg: Double? = null,
    val rightArmFatPercentage: Double? = null,
    val trunkFatMassKg: Double? = null,
    val trunkFatPercentage: Double? = null,
    val leftLegFatMassKg: Double? = null,
    val leftLegFatPercentage: Double? = null,
    val rightLegFatMassKg: Double? = null,
    val rightLegFatPercentage: Double? = null,
    val waistCm: Double? = null,
    val chestCm: Double? = null,
    val hipsCm: Double? = null,
    val rightRelaxedArmCm: Double? = null,
    val rightThighCm: Double? = null,
    val uploadStatus: UploadStatus = UploadStatus.PENDING,
    val uploadError: String? = null,
)
