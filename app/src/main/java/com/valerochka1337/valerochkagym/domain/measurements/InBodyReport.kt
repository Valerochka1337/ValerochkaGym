package com.valerochka1337.valerochkagym.domain.measurements

import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity

/** Five fixed anatomical sections printed by an InBody 270 report. */
enum class InBodySegment {
    LEFT_ARM,
    RIGHT_ARM,
    TRUNK,
    LEFT_LEG,
    RIGHT_LEG,
}

/**
 * Two independent segmental analyses from InBody. Percentages are the values printed by the
 * device relative to its reference, not an application-generated health verdict.
 */
data class InBodySegmentValues(
    val leanMassKg: Double? = null,
    val leanPercentage: Double? = null,
    val fatMassKg: Double? = null,
    val fatPercentage: Double? = null,
) {
    val hasAnyValue: Boolean
        get() = leanMassKg != null || leanPercentage != null || fatMassKg != null || fatPercentage != null
}

fun BodyMeasurementEntity.inBodySegmentValues(segment: InBodySegment): InBodySegmentValues = when (segment) {
    InBodySegment.LEFT_ARM -> InBodySegmentValues(
        leanMassKg = leftArmLeanMassKg,
        leanPercentage = leftArmLeanPercentage,
        fatMassKg = leftArmFatMassKg,
        fatPercentage = leftArmFatPercentage,
    )

    InBodySegment.RIGHT_ARM -> InBodySegmentValues(
        leanMassKg = rightArmLeanMassKg,
        leanPercentage = rightArmLeanPercentage,
        fatMassKg = rightArmFatMassKg,
        fatPercentage = rightArmFatPercentage,
    )

    InBodySegment.TRUNK -> InBodySegmentValues(
        leanMassKg = trunkLeanMassKg,
        leanPercentage = trunkLeanPercentage,
        fatMassKg = trunkFatMassKg,
        fatPercentage = trunkFatPercentage,
    )

    InBodySegment.LEFT_LEG -> InBodySegmentValues(
        leanMassKg = leftLegLeanMassKg,
        leanPercentage = leftLegLeanPercentage,
        fatMassKg = leftLegFatMassKg,
        fatPercentage = leftLegFatPercentage,
    )

    InBodySegment.RIGHT_LEG -> InBodySegmentValues(
        leanMassKg = rightLegLeanMassKg,
        leanPercentage = rightLegLeanPercentage,
        fatMassKg = rightLegFatMassKg,
        fatPercentage = rightLegFatPercentage,
    )
}

fun BodyMeasurementEntity.inBodySegmentValues(): Map<InBodySegment, InBodySegmentValues> =
    InBodySegment.entries.associateWith(::inBodySegmentValues)

