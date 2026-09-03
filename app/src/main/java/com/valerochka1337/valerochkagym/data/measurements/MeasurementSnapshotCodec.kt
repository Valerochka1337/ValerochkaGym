package com.valerochka1337.valerochkagym.data.measurements

import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity

/**
 * Stable measurement payload shared by the v9→v10 SQL backfill and Kotlin-created snapshots.
 *
 * The SQL migration uses SQLite's [quote] form, so this codec intentionally keeps that compact
 * representation instead of introducing a second JSON wire format. Decoding is strict: a corrupt
 * outbox row must never be replaced with the current live projection during a retry.
 */
object MeasurementSnapshotCodec {

    enum class PayloadFormat { V1, V2 }

    data class Decoded(
        val measurement: BodyMeasurementEntity,
        val isTombstone: Boolean,
        val payloadFormat: PayloadFormat,
    )

    /** New local snapshots use v2; v1 stays byte-for-byte compatible with the SQL backfill. */
    fun encode(measurement: BodyMeasurementEntity, isTombstone: Boolean = false): String =
        encode("v2", V2_FIELDS, measurement, isTombstone)

    fun encodeV1(measurement: BodyMeasurementEntity, isTombstone: Boolean = false): String =
        encode("v1", V1_FIELDS, measurement, isTombstone)

    private fun encode(
        version: String,
        fields: List<Field>,
        measurement: BodyMeasurementEntity,
        isTombstone: Boolean,
    ): String = buildString {
        append(version)
        fields.forEach { field -> append('|').append(field.name).append('=').append(sqlValue(field.value(measurement))) }
        if (isTombstone) append("|isDeleted=1")
    }

    fun decode(payload: String): Decoded? {
        val payloadFormat = when {
            payload.startsWith("v1") -> PayloadFormat.V1
            payload.startsWith("v2") -> PayloadFormat.V2
            else -> return null
        }
        val fields = if (payloadFormat == PayloadFormat.V1) V1_FIELDS else V2_FIELDS
        var position = 2
        val tombstoneSuffix = "|isDeleted=1"
        val payloadEnd = if (payload.endsWith(tombstoneSuffix)) payload.length - tombstoneSuffix.length else payload.length
        val values = LinkedHashMap<String, String>(fields.size)
        fields.forEachIndexed { index, field ->
            val prefix = "|${field.name}="
            if (!payload.startsWith(prefix, position)) return null
            val valueStart = position + prefix.length
            val next = if (index == fields.lastIndex) {
                payloadEnd
            } else {
                payload.indexOf("|${fields[index + 1].name}=", valueStart).takeIf { it >= 0 } ?: return null
            }
            values[field.name] = payload.substring(valueStart, next)
            position = next
        }
        if (position != payloadEnd) return null
        val tombstone = payloadEnd != payload.length
        return parseMeasurement(values, payloadFormat == PayloadFormat.V2)
            ?.let { Decoded(it, tombstone, payloadFormat) }
    }

    private fun parseMeasurement(values: Map<String, String>, hasConditions: Boolean): BodyMeasurementEntity? {
        fun string(name: String): String? = parseString(values.getValue(name))
        fun long(name: String): Long? = values.getValue(name).takeUnless { it == "NULL" }?.toLongOrNull()
        fun int(name: String): Int? = values.getValue(name).takeUnless { it == "NULL" }?.toIntOrNull()
        fun double(name: String): Double? = values.getValue(name).takeUnless { it == "NULL" }
            ?.toDoubleOrNull()
            ?.takeIf(Double::isFinite)
        fun boolean(name: String): Boolean? = when (values[name]) {
            "true" -> true
            "false" -> false
            else -> null
        }

        val id = string("id") ?: return null
        val measuredAt = long("measuredAt") ?: return null
        val conditionNote = if (hasConditions) {
            val raw = values.getValue("conditionNote")
            parseString(raw).also { if (raw != "NULL" && it == null) return null }
        } else {
            null
        }
        return BodyMeasurementEntity(
            id = id,
            measuredAt = measuredAt,
            weightKg = double("weightKg"), skeletalMuscleMassKg = double("skeletalMuscleMassKg"),
            bodyFatPercentage = double("bodyFatPercentage"), bodyFatMassKg = double("bodyFatMassKg"),
            visceralFatLevel = int("visceralFatLevel"), waistHipRatio = double("waistHipRatio"),
            waistCm = double("waistCm"), chestCm = double("chestCm"), hipsCm = double("hipsCm"),
            rightRelaxedArmCm = double("rightRelaxedArmCm"), rightThighCm = double("rightThighCm"),
            inBodyScore = int("inBodyScore"), totalBodyWaterLiters = double("totalBodyWaterLiters"),
            proteinKg = double("proteinKg"), mineralsKg = double("mineralsKg"),
            bodyMassIndex = double("bodyMassIndex"), fatFreeMassKg = double("fatFreeMassKg"),
            basalMetabolicRateKcal = int("basalMetabolicRateKcal"),
            recommendedCalorieIntakeKcal = int("recommendedCalorieIntakeKcal"),
            leftArmLeanMassKg = double("leftArmLeanMassKg"), leftArmLeanPercentage = double("leftArmLeanPercentage"),
            leftArmFatMassKg = double("leftArmFatMassKg"), leftArmFatPercentage = double("leftArmFatPercentage"),
            rightArmLeanMassKg = double("rightArmLeanMassKg"), rightArmLeanPercentage = double("rightArmLeanPercentage"),
            rightArmFatMassKg = double("rightArmFatMassKg"), rightArmFatPercentage = double("rightArmFatPercentage"),
            trunkLeanMassKg = double("trunkLeanMassKg"), trunkLeanPercentage = double("trunkLeanPercentage"),
            trunkFatMassKg = double("trunkFatMassKg"), trunkFatPercentage = double("trunkFatPercentage"),
            leftLegLeanMassKg = double("leftLegLeanMassKg"), leftLegLeanPercentage = double("leftLegLeanPercentage"),
            leftLegFatMassKg = double("leftLegFatMassKg"), leftLegFatPercentage = double("leftLegFatPercentage"),
            rightLegLeanMassKg = double("rightLegLeanMassKg"), rightLegLeanPercentage = double("rightLegLeanPercentage"),
            rightLegFatMassKg = double("rightLegFatMassKg"), rightLegFatPercentage = double("rightLegFatPercentage"),
            afterMeal = if (hasConditions) boolean("afterMeal") ?: return null else false,
            afterWorkout = if (hasConditions) boolean("afterWorkout") ?: return null else false,
            unusualHydration = if (hasConditions) boolean("unusualHydration") ?: return null else false,
            conditionNote = conditionNote,
        )
    }

    private fun parseString(value: String): String? {
        if (value == "NULL") return null
        if (value.length < 2 || value.first() != '\'' || value.last() != '\'') return null
        return buildString {
            var index = 1
            while (index < value.lastIndex) {
                if (value[index] == '\'') {
                    if (index + 1 >= value.lastIndex || value[index + 1] != '\'') return null
                    append('\'')
                    index += 2
                } else {
                    append(value[index++])
                }
            }
        }
    }

    private fun sqlValue(value: Any?): String = when (value) {
        null -> "NULL"
        is String -> "'${value.replace("'", "''")}'"
        else -> value.toString()
    }

    private data class Field(val name: String, val value: (BodyMeasurementEntity) -> Any?)

    private val V1_FIELDS = listOf(
        Field("id") { it.id }, Field("measuredAt") { it.measuredAt }, Field("weightKg") { it.weightKg },
        Field("skeletalMuscleMassKg") { it.skeletalMuscleMassKg }, Field("bodyFatPercentage") { it.bodyFatPercentage },
        Field("bodyFatMassKg") { it.bodyFatMassKg }, Field("visceralFatLevel") { it.visceralFatLevel },
        Field("waistHipRatio") { it.waistHipRatio }, Field("waistCm") { it.waistCm }, Field("chestCm") { it.chestCm },
        Field("hipsCm") { it.hipsCm }, Field("rightRelaxedArmCm") { it.rightRelaxedArmCm }, Field("rightThighCm") { it.rightThighCm },
        Field("inBodyScore") { it.inBodyScore }, Field("totalBodyWaterLiters") { it.totalBodyWaterLiters },
        Field("proteinKg") { it.proteinKg }, Field("mineralsKg") { it.mineralsKg }, Field("bodyMassIndex") { it.bodyMassIndex },
        Field("fatFreeMassKg") { it.fatFreeMassKg }, Field("basalMetabolicRateKcal") { it.basalMetabolicRateKcal },
        Field("recommendedCalorieIntakeKcal") { it.recommendedCalorieIntakeKcal }, Field("leftArmLeanMassKg") { it.leftArmLeanMassKg },
        Field("leftArmLeanPercentage") { it.leftArmLeanPercentage }, Field("leftArmFatMassKg") { it.leftArmFatMassKg },
        Field("leftArmFatPercentage") { it.leftArmFatPercentage }, Field("rightArmLeanMassKg") { it.rightArmLeanMassKg },
        Field("rightArmLeanPercentage") { it.rightArmLeanPercentage }, Field("rightArmFatMassKg") { it.rightArmFatMassKg },
        Field("rightArmFatPercentage") { it.rightArmFatPercentage }, Field("trunkLeanMassKg") { it.trunkLeanMassKg },
        Field("trunkLeanPercentage") { it.trunkLeanPercentage }, Field("trunkFatMassKg") { it.trunkFatMassKg },
        Field("trunkFatPercentage") { it.trunkFatPercentage }, Field("leftLegLeanMassKg") { it.leftLegLeanMassKg },
        Field("leftLegLeanPercentage") { it.leftLegLeanPercentage }, Field("leftLegFatMassKg") { it.leftLegFatMassKg },
        Field("leftLegFatPercentage") { it.leftLegFatPercentage }, Field("rightLegLeanMassKg") { it.rightLegLeanMassKg },
        Field("rightLegLeanPercentage") { it.rightLegLeanPercentage }, Field("rightLegFatMassKg") { it.rightLegFatMassKg },
        Field("rightLegFatPercentage") { it.rightLegFatPercentage },
    )

    private val V2_FIELDS = V1_FIELDS + listOf(
        Field("afterMeal") { it.afterMeal },
        Field("afterWorkout") { it.afterWorkout },
        Field("unusualHydration") { it.unusualHydration },
        Field("conditionNote") { it.conditionNote },
    )
}
