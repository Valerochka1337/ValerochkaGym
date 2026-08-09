package com.valerochka1337.valerochkagym.domain.measurements

import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import java.time.Instant
import java.time.ZoneId

/** Единый период для всей вкладки замеров — карточки не должны показывать разные срезы. */
enum class MeasurementPeriod(val months: Long?) {
    MONTHS_3(3),
    MONTHS_6(6),
    YEAR(12),
    ALL(null),
}

/** Независимые графики на экране; в одном всегда только одна единица измерения. */
enum class MeasurementChartGroup {
    COMPOSITION,
    INBODY,
    CIRCUMFERENCES,
    RISK,
}

/**
 * Полный, но намеренно небольшой набор полезных метрик замера.
 *
 * [value] никогда не превращает отсутствующее значение в ноль. Это особенно важно для линий:
 * нулевая точка означала бы реальное изменение тела, которого пользователь не измерял.
 */
enum class BodyMeasurementMetric(
    val title: String,
    val unit: String,
    val group: MeasurementChartGroup,
) {
    WEIGHT("Вес", "кг", MeasurementChartGroup.COMPOSITION),
    SKELETAL_MUSCLE_MASS("Скелетные мышцы", "кг", MeasurementChartGroup.COMPOSITION),
    BODY_FAT_PERCENTAGE("Жир", "%", MeasurementChartGroup.COMPOSITION),
    BODY_FAT_MASS("Жировая масса", "кг", MeasurementChartGroup.COMPOSITION),
    TOTAL_BODY_WATER("Вода в организме", "л", MeasurementChartGroup.COMPOSITION),
    PROTEIN("Белок", "кг", MeasurementChartGroup.COMPOSITION),
    MINERALS("Минералы", "кг", MeasurementChartGroup.COMPOSITION),
    FAT_FREE_MASS("Безжировая масса", "кг", MeasurementChartGroup.COMPOSITION),
    INBODY_SCORE("Оценка InBody", "балл", MeasurementChartGroup.INBODY),
    BODY_MASS_INDEX("ИМТ", "", MeasurementChartGroup.INBODY),
    BASAL_METABOLIC_RATE("Основной обмен", "ккал", MeasurementChartGroup.INBODY),
    RECOMMENDED_CALORIE_INTAKE("Рекомендуемые калории", "ккал", MeasurementChartGroup.INBODY),
    WAIST("Талия", "см", MeasurementChartGroup.CIRCUMFERENCES),
    CHEST("Грудь", "см", MeasurementChartGroup.CIRCUMFERENCES),
    HIPS("Бёдра", "см", MeasurementChartGroup.CIRCUMFERENCES),
    RIGHT_RELAXED_ARM("Правое плечо", "см", MeasurementChartGroup.CIRCUMFERENCES),
    RIGHT_THIGH("Правое бедро", "см", MeasurementChartGroup.CIRCUMFERENCES),
    WAIST_HIP_RATIO("WHR", "", MeasurementChartGroup.RISK),
    VISCERAL_FAT("Висцеральный жир", "уровень", MeasurementChartGroup.RISK),
    ;

    fun value(measurement: BodyMeasurementEntity): Double? = when (this) {
        WEIGHT -> measurement.weightKg
        SKELETAL_MUSCLE_MASS -> measurement.skeletalMuscleMassKg
        BODY_FAT_PERCENTAGE -> measurement.bodyFatPercentage
        BODY_FAT_MASS -> measurement.bodyFatMassKg
            ?: calculateBodyFatMassKg(measurement.weightKg, measurement.bodyFatPercentage)
        TOTAL_BODY_WATER -> measurement.totalBodyWaterLiters
        PROTEIN -> measurement.proteinKg
        MINERALS -> measurement.mineralsKg
        FAT_FREE_MASS -> measurement.fatFreeMassKg
        INBODY_SCORE -> measurement.inBodyScore?.toDouble()
        BODY_MASS_INDEX -> measurement.bodyMassIndex
        BASAL_METABOLIC_RATE -> measurement.basalMetabolicRateKcal?.toDouble()
        RECOMMENDED_CALORIE_INTAKE -> measurement.recommendedCalorieIntakeKcal?.toDouble()
        WAIST -> measurement.waistCm
        CHEST -> measurement.chestCm
        HIPS -> measurement.hipsCm
        RIGHT_RELAXED_ARM -> measurement.rightRelaxedArmCm
        RIGHT_THIGH -> measurement.rightThighCm
        WAIST_HIP_RATIO -> measurement.effectiveWaistHipRatio()
        VISCERAL_FAT -> measurement.visceralFatLevel?.toDouble()
    }
}

/** Значение последнего замера и предыдущая заполненная точка именно этой метрики. */
data class MeasurementMetricComparison(
    val metric: BodyMeasurementMetric,
    val value: Double,
    val previousValue: Double?,
) {
    val delta: Double? get() = previousValue?.let { value - it }
}

/** Производная жировая масса: вес × доля жира. Некорректные числа не попадают в аналитику. */
fun calculateBodyFatMassKg(weightKg: Double?, bodyFatPercentage: Double?): Double? {
    if (weightKg == null || bodyFatPercentage == null) return null
    if (!weightKg.isFinite() || !bodyFatPercentage.isFinite() || weightKg < 0.0 || bodyFatPercentage < 0.0) {
        return null
    }
    return weightKg * bodyFatPercentage / 100.0
}

/** WHR из обхватов: талия / бёдра. Деление на ноль и нечисловые значения — отсутствие метрики. */
fun calculateWaistHipRatio(waistCm: Double?, hipsCm: Double?): Double? {
    if (waistCm == null || hipsCm == null) return null
    if (!waistCm.isFinite() || !hipsCm.isFinite() || waistCm < 0.0 || hipsCm <= 0.0) return null
    return waistCm / hipsCm
}

/** Явно введённый InBody WHR имеет приоритет; иначе используем расчёт из сохранённых обхватов. */
fun BodyMeasurementEntity.effectiveWaistHipRatio(): Double? =
    waistHipRatio?.takeIf { it.isFinite() && it >= 0.0 }
        ?: calculateWaistHipRatio(waistCm, hipsCm)

/** Отсекает историю по календарной дате локальной зоны, сохраняя входной порядок. */
fun filterMeasurementsByPeriod(
    measurements: List<BodyMeasurementEntity>,
    period: MeasurementPeriod,
    nowMillis: Long,
    zone: ZoneId,
): List<BodyMeasurementEntity> {
    val months = period.months ?: return measurements
    val cutoff = Instant.ofEpochMilli(nowMillis)
        .atZone(zone)
        .toLocalDate()
        .minusMonths(months)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
    return measurements.filter { it.measuredAt >= cutoff }
}

/**
 * Сравнения для последнего замера. Если следующая по времени запись не содержит метрики, ищем
 * дальше: «нет измерения» не является нулём и не должен обрывать полезное сравнение.
 */
fun latestMetricComparisons(
    measurements: List<BodyMeasurementEntity>,
): List<MeasurementMetricComparison> {
    val latest = measurements.maxByOrNull { it.measuredAt } ?: return emptyList()
    val older = measurements
        .asSequence()
        .filter { it.id != latest.id && it.measuredAt <= latest.measuredAt }
        .sortedByDescending { it.measuredAt }
        .toList()

    return BodyMeasurementMetric.entries.mapNotNull { metric ->
        val value = metric.value(latest) ?: return@mapNotNull null
        MeasurementMetricComparison(
            metric = metric,
            value = value,
            previousValue = older.firstNotNullOfOrNull(metric::value),
        )
    }
}
