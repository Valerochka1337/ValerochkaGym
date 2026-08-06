package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Один замер тела. [measuredAt] хранит выбранную пользователем дату вместе с текущим временем:
 * дата нужна для тренда, а время позволяет не терять контекст при экспорте в Google Sheets.
 *
 * InBody и ручные обхваты намеренно nullable. Пустое поле — это отсутствующее измерение, а не
 * ноль: при построении тренда такие точки пропускаются, чтобы линия не сообщала несуществующее
 * изменение. Масса жира является производной (`вес × процент жира`) и в БД не дублируется.
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
    val visceralFatLevel: Int? = null,
    val waistHipRatio: Double? = null,
    val waistCm: Double? = null,
    val chestCm: Double? = null,
    val hipsCm: Double? = null,
    val rightRelaxedArmCm: Double? = null,
    val rightThighCm: Double? = null,
    val uploadStatus: UploadStatus = UploadStatus.PENDING,
    val uploadError: String? = null,
)
