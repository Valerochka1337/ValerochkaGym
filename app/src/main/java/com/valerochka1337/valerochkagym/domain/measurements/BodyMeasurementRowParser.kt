package com.valerochka1337.valerochkagym.domain.measurements

import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.measurements.MeasurementSnapshotCodec
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Разобранные строки `Measurements` и число строк с UUID, которые нельзя восстановить. */
data class ParsedMeasurements(
    val measurements: List<BodyMeasurementEntity>,
    val skippedRows: Int,
    val snapshots: List<ParsedMeasurementSnapshot> = emptyList(),
    /** Equal stable ID/version rows with divergent app-managed values; never resolved by row order. */
    val conflicts: List<ParsedMeasurementConflict> = emptyList(),
)

/** A:AY sync metadata. Pre-AQ rows are compatible v1 records with an implicit false tombstone. */
data class ParsedMeasurementSnapshot(
    val measurement: BodyMeasurementEntity,
    val version: Long,
    val updatedAt: Long,
    val isDeleted: Boolean,
    val payloadHash: String?,
    val idempotencyKey: String,
    /** Exact payload form inferred from the managed header; needed for v1 hash compatibility. */
    val canonicalPayload: String = MeasurementSnapshotCodec.encode(measurement, isDeleted),
    val payloadFormat: MeasurementSnapshotCodec.PayloadFormat = MeasurementSnapshotCodec.PayloadFormat.V2,
)

data class ParsedMeasurementConflict(
    val first: ParsedMeasurementSnapshot,
    val second: ParsedMeasurementSnapshot,
)

/**
 * Обратный к [BodyMeasurementRowMapper]. Поддерживает как полный формат A:AP, так и ранний
 * A:N: отсутствующие после N показатели остаются null. UUID — ключ дедупликации, поэтому
 * импортированные записи сразу получают [UploadStatus.UPLOADED] и не отправляются обратно.
 */
object BodyMeasurementRowParser {

    /** The parser accepts a validated managed header, never user-owned trailing cells as schema. */
    enum class Schema { EARLY, LEGACY, INTERIM, FULL, UNKNOWN }

    /** Строгий ISO-форматтер не превращает 2026-02-30 молча в другую дату. */
    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun parse(rows: List<List<String>>, schema: Schema = schemaForHeader(rows.firstOrNull().orEmpty())): ParsedMeasurements {
        val zone = ZoneId.systemDefault()
        var skippedRows = 0
        val parsedByIdentity = LinkedHashMap<Pair<String, Long>, ParsedMeasurementSnapshot>()
        val conflictedIdentities = mutableSetOf<Pair<String, Long>>()
        val conflicts = mutableListOf<ParsedMeasurementConflict>()

        rows.forEach { row ->
            val id = row.cell(MEASUREMENT_ID)
            if (id.isEmpty() || id == "measurement_id") return@forEach
            val measuredAt = parseMillis(row.cell(DATE), row.cell(TIME), zone)
            if (measuredAt == null) {
                skippedRows++
                return@forEach
            }
            val payloadFormat = payloadFormatFor(row, schema)
            if (payloadFormat == null) {
                skippedRows++
                return@forEach
            }
            // A non-empty managed numeric cell is never silently converted to null.  That would
            // manufacture a different immutable revision from a malformed spreadsheet row.
            if (!row.hasOnlyValidManagedNumbers() || !row.hasValidMetadata(schema)) {
                skippedRows++
                return@forEach
            }
            val rowHasConditions = payloadFormat == MeasurementSnapshotCodec.PayloadFormat.V2
            val measurement = BodyMeasurementEntity(
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
                afterMeal = if (rowHasConditions) row.cell(AFTER_MEAL).toBooleanWire() else false,
                afterWorkout = if (rowHasConditions) row.cell(AFTER_WORKOUT).toBooleanWire() else false,
                unusualHydration = if (rowHasConditions) row.cell(UNUSUAL_HYDRATION).toBooleanWire() else false,
                conditionNote = if (rowHasConditions) row.cell(CONDITION_NOTE).ifBlank { null } else null,
            )
            val version = row.cell(VERSION).toLongOrNull()?.takeIf { it > 0 } ?: 1L
            val updatedAt = row.cell(UPDATED_AT).toLongOrNull() ?: measuredAt
            val tombstone = row.cell(IS_DELETED).equals("true", ignoreCase = true) || row.cell(IS_DELETED) == "1"
            val snapshot = ParsedMeasurementSnapshot(
                measurement = measurement,
                version = version,
                updatedAt = updatedAt,
                isDeleted = tombstone,
                payloadHash = row.cell(PAYLOAD_HASH).ifBlank { null },
                idempotencyKey = row.cell(IDEMPOTENCY_KEY).ifBlank { "$id:$version" },
                canonicalPayload = if (rowHasConditions) {
                    MeasurementSnapshotCodec.encode(measurement, tombstone)
                } else {
                    MeasurementSnapshotCodec.encodeV1(measurement, tombstone)
                },
                payloadFormat = payloadFormat,
            )
            val identity = id to version
            if (identity in conflictedIdentities) return@forEach
            val previous = parsedByIdentity[identity]
            when {
                previous == null -> parsedByIdentity[identity] = snapshot
                previous.sameManagedSnapshot(snapshot) -> Unit // exact lost-response duplicate
                else -> {
                    parsedByIdentity.remove(identity)
                    conflictedIdentities += identity
                    conflicts += ParsedMeasurementConflict(previous, snapshot)
                }
            }
        }
        val snapshots = parsedByIdentity.values.sortedWith(
            compareBy<ParsedMeasurementSnapshot> { it.measurement.id }.thenBy { it.version },
        )
        return ParsedMeasurements(
            measurements = snapshots.groupBy { it.measurement.id }.values.mapNotNull { versions ->
                versions.maxBy { it.version }.takeUnless { it.isDeleted }?.measurement
            },
            skippedRows = skippedRows,
            snapshots = snapshots,
            conflicts = conflicts,
        )
    }

    /** A null migration hash is compatible only with an otherwise identical v1 payload. */
    private fun ParsedMeasurementSnapshot.sameManagedSnapshot(other: ParsedMeasurementSnapshot): Boolean =
        canonicalPayload == other.canonicalPayload && measurement == other.measurement && isDeleted == other.isDeleted &&
            updatedAt == other.updatedAt && idempotencyKey == other.idempotencyKey &&
            (payloadHash == null || other.payloadHash == null || payloadHash == other.payloadHash)

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

    private fun String.toBooleanWire(): Boolean = equals("true", ignoreCase = true)

    private fun List<String>.hasOnlyValidManagedNumbers(): Boolean {
        val integerFields = setOf(VISCERAL_FAT_LEVEL, INBODY_SCORE, BASAL_METABOLIC_RATE_KCAL, RECOMMENDED_CALORIE_INTAKE_KCAL)
        return (WEIGHT_KG..RIGHT_LEG_FAT_PERCENTAGE).all { index ->
            val value = cell(index)
            value.isEmpty() || if (index in integerFields) value.toIntLoose() != null else value.toDoubleLoose() != null
        }
    }

    private fun List<String>.hasValidMetadata(schema: Schema): Boolean {
        if (schema == Schema.EARLY || schema == Schema.LEGACY || schema == Schema.UNKNOWN) return true
        // A historical A:AP presentation row can appear beneath an already-upgraded header.
        // It has no metadata cells at all (rather than blank modern metadata), and remains a
        // recognised v1 compatibility shape.
        if (size <= LEGACY_MEASUREMENT_COLUMN_COUNT + 28 &&
            cell(VERSION).isEmpty() && cell(UPDATED_AT).isEmpty() && cell(IS_DELETED).isEmpty()
        ) return true
        val version = cell(VERSION).toLongOrNull()?.takeIf { it > 0 } ?: return false
        if (version <= 0 || cell(UPDATED_AT).toLongOrNull() == null) return false
        return cell(IS_DELETED) in setOf("0", "1", "true", "false", "TRUE", "FALSE")
    }

    private fun payloadFormatFor(
        row: List<String>,
        schema: Schema,
    ): MeasurementSnapshotCodec.PayloadFormat? {
        if (schema != Schema.FULL) return when (schema) {
            Schema.UNKNOWN -> null
            else -> MeasurementSnapshotCodec.PayloadFormat.V1
        }
        val managed = listOf(
            row.cell(AFTER_MEAL), row.cell(AFTER_WORKOUT), row.cell(UNUSUAL_HYDRATION), row.cell(CONDITION_NOTE),
        )
        if (managed.all(String::isEmpty)) return MeasurementSnapshotCodec.PayloadFormat.V1
        val booleansValid = managed.take(3).all { it == "true" || it == "false" }
        return MeasurementSnapshotCodec.PayloadFormat.V2.takeIf { booleansValid }
    }

    fun schemaForHeader(header: List<String>): Schema = when {
        header.startsWith(BodyMeasurementRowMapper.HEADER_ROW) -> Schema.FULL
        header.startsWith(BodyMeasurementRowMapper.INTERIM_HEADER_ROW) -> Schema.INTERIM
        header.startsWith(BodyMeasurementRowMapper.LEGACY_HEADER_ROW) -> Schema.LEGACY
        header.startsWith(BodyMeasurementRowMapper.LEGACY_HEADER_ROW.take(LEGACY_MEASUREMENT_COLUMN_COUNT)) -> Schema.EARLY
        else -> Schema.UNKNOWN
    }

    private fun List<String>.startsWith(expected: List<String>): Boolean =
        size >= expected.size && take(expected.size) == expected

    private fun parseMillis(date: String, time: String, zone: ZoneId): Long? = try {
        val localDate = LocalDate.parse(date, DATE_FORMATTER)
        val localTime = LocalTime.parse(time)
        LocalDateTime.of(localDate, localTime).atZone(zone).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }

    private const val MEASUREMENT_ID = 0
    private const val LEGACY_MEASUREMENT_COLUMN_COUNT = 14
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
    private const val VERSION = 42
    private const val UPDATED_AT = 43
    private const val IS_DELETED = 44
    private const val PAYLOAD_HASH = 45
    private const val IDEMPOTENCY_KEY = 46
    private const val AFTER_MEAL = 47
    private const val AFTER_WORKOUT = 48
    private const val UNUSUAL_HYDRATION = 49
    private const val CONDITION_NOTE = 50
}
