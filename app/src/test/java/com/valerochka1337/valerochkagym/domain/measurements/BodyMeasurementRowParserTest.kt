package com.valerochka1337.valerochkagym.domain.measurements

import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.measurements.MeasurementSnapshotCodec
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyMeasurementRowParserTest {

    @Test
    fun `parsing a full InBody row restores every exported measurement`() = runTest {
        val zone = ZoneId.systemDefault()
        val measurement = BodyMeasurementEntity(
            id = "measurement-1",
            measuredAt = LocalDateTime.of(2026, 8, 20, 9, 30).atZone(zone).toInstant().toEpochMilli(),
            weightKg = 70.2,
            skeletalMuscleMassKg = 29.4,
            bodyFatPercentage = 21.7,
            bodyFatMassKg = 15.2,
            visceralFatLevel = 6,
            waistHipRatio = 0.81,
            inBodyScore = 78,
            totalBodyWaterLiters = 38.6,
            proteinKg = 10.5,
            mineralsKg = 3.75,
            bodyMassIndex = 22.9,
            fatFreeMassKg = 55.0,
            basalMetabolicRateKcal = 1_530,
            recommendedCalorieIntakeKcal = 2_140,
            leftArmLeanMassKg = 3.12,
            leftArmLeanPercentage = 105.4,
            rightArmLeanMassKg = 3.15,
            rightArmLeanPercentage = 106.1,
            trunkLeanMassKg = 25.1,
            trunkLeanPercentage = 101.2,
            leftLegLeanMassKg = 8.42,
            leftLegLeanPercentage = 98.4,
            rightLegLeanMassKg = 8.47,
            rightLegLeanPercentage = 99.0,
            leftArmFatMassKg = 0.81,
            leftArmFatPercentage = 111.0,
            rightArmFatMassKg = 0.79,
            rightArmFatPercentage = 108.0,
            trunkFatMassKg = 7.2,
            trunkFatPercentage = 116.0,
            leftLegFatMassKg = 2.75,
            leftLegFatPercentage = 109.0,
            rightLegFatMassKg = 2.77,
            rightLegFatPercentage = 110.0,
            waistCm = 72.0,
            chestCm = 95.0,
            hipsCm = 96.0,
            rightRelaxedArmCm = 31.0,
            rightThighCm = 54.0,
        )

        val result = BodyMeasurementRowParser.parse(
            listOf(
                BodyMeasurementRowMapper.HEADER_ROW,
                BodyMeasurementRowMapper.row(measurement, zone).map { it?.toString().orEmpty() },
            ),
        )

        assertEquals(0, result.skippedRows)
        assertEquals(
            measurement.copy(uploadStatus = UploadStatus.UPLOADED, uploadError = null),
            result.measurements.single(),
        )
    }

    @Test
    fun `parsing a legacy fourteen column row keeps newer InBody fields empty`() = runTest {
        val zone = ZoneId.systemDefault()
        val measurement = BodyMeasurementEntity(
            id = "legacy-measurement",
            measuredAt = LocalDateTime.of(2026, 1, 2, 10, 15).atZone(zone).toInstant().toEpochMilli(),
            weightKg = 71.0,
            bodyFatPercentage = 20.0,
            visceralFatLevel = 5,
            waistCm = 73.0,
        )
        val legacyHeader = BodyMeasurementRowMapper.HEADER_ROW.take(14)
        val legacyRow = BodyMeasurementRowMapper.row(measurement, zone)
            .take(14)
            .map { it?.toString().orEmpty() }

        val restored = BodyMeasurementRowParser.parse(listOf(legacyHeader, legacyRow)).measurements.single()

        assertEquals("legacy-measurement", restored.id)
        assertEquals(71.0, restored.weightKg ?: 0.0, 0.0)
        assertEquals(UploadStatus.UPLOADED, restored.uploadStatus)
        assertNull(restored.inBodyScore)
        assertNull(restored.leftArmLeanMassKg)
        assertNull(restored.rightLegFatPercentage)
    }

    @Test
    fun `parsing rows skips a measurement with an invalid date or time`() = runTest {
        val result = BodyMeasurementRowParser.parse(
            listOf(
                BodyMeasurementRowMapper.HEADER_ROW,
                listOf("bad", "2026-02-30", "10:00"),
                listOf("also-bad", "2026-02-20", "not-a-time"),
            ),
        )

        assertEquals(emptyList<BodyMeasurementEntity>(), result.measurements)
        assertEquals(2, result.skippedRows)
    }

    @Test
    fun `parsing AU snapshot metadata keeps version and hides a tombstone from live measurements`() = runTest {
        val measurement = BodyMeasurementEntity("m", 1_700_000_000_000, weightKg = 70.0)
        val row = BodyMeasurementRowMapper.versionedRow(
            measurement, version = 2, updatedAt = 2_000, isDeleted = true,
            payloadHash = "hash", idempotencyKey = "m:2",
        ).map { it?.toString().orEmpty() }

        val parsed = BodyMeasurementRowParser.parse(listOf(BodyMeasurementRowMapper.HEADER_ROW, row))

        assertEquals(emptyList<BodyMeasurementEntity>(), parsed.measurements)
        assertEquals(2L, parsed.snapshots.single().version)
        assertEquals(true, parsed.snapshots.single().isDeleted)
        assertEquals("m:2", parsed.snapshots.single().idempotencyKey)
    }

    @Test
    fun `versioned primary row preserves nullable raw values without exporting presentation derivations`() {
        val measurement = BodyMeasurementEntity(
            "raw-null", 1_700_000_000_000,
            weightKg = 70.0,
            bodyFatPercentage = 20.0,
            bodyFatMassKg = null,
            waistHipRatio = null,
            waistCm = 75.0,
            hipsCm = 100.0,
        )
        val row = BodyMeasurementRowMapper.versionedRow(measurement, 1, 10, false, "hash", "raw-null:1")

        assertNull(row[6])
        assertNull(row[8])
        val parsed = BodyMeasurementRowParser.parse(
            listOf(BodyMeasurementRowMapper.HEADER_ROW, row.map { it?.toString().orEmpty() }),
        ).snapshots.single()
        assertNull(parsed.measurement.bodyFatMassKg)
        assertNull(parsed.measurement.waistHipRatio)
        assertEquals(75.0, parsed.measurement.waistCm ?: 0.0, 0.0)
        assertEquals(100.0, parsed.measurement.hipsCm ?: 0.0, 0.0)
    }

    @Test
    fun `full AY row round trips conditions while interim AU user cells stay local to Sheets`() {
        val measurement = BodyMeasurementEntity(
            "conditions", 1_700_000_000_000, weightKg = 70.0,
            afterMeal = true, afterWorkout = true, unusualHydration = true, conditionNote = "сауна",
        )
        val full = BodyMeasurementRowMapper.versionedRow(measurement, 2, 20, false, "hash", "conditions:2")
            .map { it?.toString().orEmpty() }
        val restored = BodyMeasurementRowParser.parse(listOf(BodyMeasurementRowMapper.HEADER_ROW, full)).snapshots.single()
        assertEquals(measurement.copy(uploadStatus = UploadStatus.UPLOADED, uploadError = null), restored.measurement)
        assertEquals("v2", restored.canonicalPayload.substringBefore('|'))

        val interimRow = full.take(47) + listOf("true", "true", "true", "user-owned")
        val interim = BodyMeasurementRowParser.parse(
            listOf(BodyMeasurementRowMapper.INTERIM_HEADER_ROW, interimRow),
        ).snapshots.single().measurement
        assertEquals(false, interim.afterMeal)
        assertNull(interim.conditionNote)
    }

    @Test
    fun `v2 default condition booleans survive Sheets trimming an empty trailing note`() {
        val measurement = BodyMeasurementEntity("trimmed", 1_700_000_000_000, weightKg = 70.0)
        // Values API can omit the trailing empty AY cell, but AV:AX remain explicit v2 booleans.
        val trimmed = BodyMeasurementRowMapper.versionedRow(measurement, 2, 20, false, "hash", "trimmed:2")
            .take(50)
            .map { it?.toString().orEmpty() }

        val parsed = BodyMeasurementRowParser.parse(listOf(BodyMeasurementRowMapper.HEADER_ROW, trimmed)).snapshots.single()
        assertEquals(MeasurementSnapshotCodec.PayloadFormat.V2, parsed.payloadFormat)
        assertEquals(false, parsed.measurement.afterMeal)
        assertEquals(false, parsed.measurement.afterWorkout)
        assertEquals(false, parsed.measurement.unusualHydration)
        assertNull(parsed.measurement.conditionNote)
    }

    @Test
    fun `legacy v1 row below an upgraded header keeps its exact v1 snapshot contract`() {
        val measurement = BodyMeasurementEntity("legacy", 1_700_000_000_000, weightKg = 70.0)
        val historical = BodyMeasurementRowMapper.versionedRow(
            measurement, 1, 10, false, null, "legacy:1", includeConditions = false,
        )
            .map { it?.toString().orEmpty() } + listOf("user AZ", "user BA")

        val parsed = BodyMeasurementRowParser.parse(
            listOf(BodyMeasurementRowMapper.HEADER_ROW, historical),
        ).snapshots.single()

        assertEquals(MeasurementSnapshotCodec.encodeV1(measurement), parsed.canonicalPayload)
        assertEquals(false, parsed.measurement.afterMeal)
        assertNull(parsed.measurement.conditionNote)
    }

    @Test
    fun `mixed or malformed managed condition cells are rejected instead of inferred from user tail`() {
        val measurement = BodyMeasurementEntity("bad", 1_700_000_000_000, weightKg = 70.0)
        val mixed = BodyMeasurementRowMapper.versionedRow(measurement, 1, 10, false, null, "bad:1")
            .map { it?.toString().orEmpty() }.toMutableList().also {
                it[47] = "true"
                it[48] = ""
                it[49] = "false"
                it += "user-owned nonempty tail"
            }
        val malformed = mixed.toMutableList().also {
            it[47] = "sometimes"
            it[48] = "false"
            it[49] = "false"
        }

        val result = BodyMeasurementRowParser.parse(
            listOf(BodyMeasurementRowMapper.HEADER_ROW, mixed, malformed),
        )
        assertEquals(2, result.skippedRows)
        assertTrue(result.snapshots.isEmpty())
    }

    @Test
    fun `v1 and v2 tombstones keep their distinct canonical payload formats`() {
        val measurement = BodyMeasurementEntity("gone", 1_700_000_000_000, weightKg = 70.0)
        val v1 = BodyMeasurementRowMapper.versionedRow(
            measurement, 1, 10, true, null, "gone:1", includeConditions = false,
        ).map { it?.toString().orEmpty() }
        val v2 = BodyMeasurementRowMapper.versionedRow(measurement, 2, 20, true, "hash", "gone:2")
            .map { it?.toString().orEmpty() }

        val parsed = BodyMeasurementRowParser.parse(listOf(BodyMeasurementRowMapper.HEADER_ROW, v1, v2))
        assertEquals(listOf(MeasurementSnapshotCodec.PayloadFormat.V1, MeasurementSnapshotCodec.PayloadFormat.V2),
            parsed.snapshots.map { it.payloadFormat })
        assertTrue(parsed.snapshots.all { it.isDeleted })
    }

    @Test
    fun `null hash v1 and v2 default rows of the same version conflict rather than merge`() {
        val measurement = BodyMeasurementEntity("format", 1_700_000_000_000, weightKg = 70.0)
        val v1 = BodyMeasurementRowMapper.versionedRow(
            measurement, 1, 10, false, null, "format:1", includeConditions = false,
        ).map { it?.toString().orEmpty() }
        val v2 = BodyMeasurementRowMapper.versionedRow(measurement, 1, 10, false, null, "format:1")
            .map { it?.toString().orEmpty() }

        val parsed = BodyMeasurementRowParser.parse(listOf(BodyMeasurementRowMapper.HEADER_ROW, v1, v2))
        assertTrue(parsed.snapshots.isEmpty())
        assertEquals(1, parsed.conflicts.size)
    }

    @Test
    fun `version ordering is independent from Sheets row order and keeps immutable history`() {
        val v1 = BodyMeasurementEntity("m", 1_700_000_000_000, weightKg = 70.0)
        val v2 = v1.copy(weightKg = 69.0)
        val rows = listOf(
            BodyMeasurementRowMapper.HEADER_ROW,
            row(v2, version = 2, updatedAt = 20),
            row(v1, version = 1, updatedAt = 10),
        )

        val parsed = BodyMeasurementRowParser.parse(rows)
        val reversed = BodyMeasurementRowParser.parse(rows.take(1) + rows.drop(1).reversed())

        assertEquals(listOf(1L, 2L), parsed.snapshots.map { it.version })
        assertEquals(parsed.snapshots, reversed.snapshots)
        assertEquals(69.0, parsed.measurements.single().weightKg ?: 0.0, 0.0)
    }

    @Test
    fun `equal version divergence is reported instead of choosing the last row`() {
        val first = BodyMeasurementEntity("m", 1_700_000_000_000, weightKg = 70.0)
        val second = first.copy(weightKg = 68.0)

        val parsed = BodyMeasurementRowParser.parse(
            listOf(BodyMeasurementRowMapper.HEADER_ROW, row(first, 2, 20), row(second, 2, 10)),
        )

        assertEquals(emptyList<BodyMeasurementEntity>(), parsed.measurements)
        assertEquals(emptyList<Long>(), parsed.snapshots.map { it.version })
        assertEquals(1, parsed.conflicts.size)
    }

    @Test
    fun `exact duplicate and legacy rows stay idempotent at version one`() {
        val measurement = BodyMeasurementEntity("m", 1_700_000_000_000, weightKg = 70.0)
        val versioned = row(measurement, 1, 10)
        val legacy = BodyMeasurementRowMapper.row(measurement).map { it?.toString().orEmpty() }

        val duplicates = BodyMeasurementRowParser.parse(
            listOf(BodyMeasurementRowMapper.HEADER_ROW, versioned, versioned),
        )
        val parsedLegacy = BodyMeasurementRowParser.parse(listOf(BodyMeasurementRowMapper.HEADER_ROW, legacy))

        assertEquals(1, duplicates.snapshots.size)
        assertEquals(1L, parsedLegacy.snapshots.single().version)
    }

    @Test
    fun `maximal tombstone hides a lower live version`() {
        val measurement = BodyMeasurementEntity("m", 1_700_000_000_000, weightKg = 70.0)

        val parsed = BodyMeasurementRowParser.parse(
            listOf(
                BodyMeasurementRowMapper.HEADER_ROW,
                row(measurement, 1, 10),
                row(measurement, 2, 20, isDeleted = true),
            ),
        )

        assertEquals(emptyList<BodyMeasurementEntity>(), parsed.measurements)
        assertEquals(listOf(1L, 2L), parsed.snapshots.map { it.version })
    }

    @Test fun `nonempty malformed managed numeric metadata and condition cells reject the whole row`() {
        val measurement = BodyMeasurementEntity("m", 1_700_000_000_000, weightKg = 70.0)
        val base = row(measurement, 1, 10)
        val invalidNumeric = base.toMutableList().also { it[3] = "seventy" }
        val invalidVersion = base.toMutableList().also { it[42] = "v1" }
        val invalidDeleted = base.toMutableList().also { it[44] = "maybe" }
        val invalidCondition = base.toMutableList().also { it[47] = "yes" }

        val parsed = BodyMeasurementRowParser.parse(
            listOf(BodyMeasurementRowMapper.HEADER_ROW, invalidNumeric, invalidVersion, invalidDeleted, invalidCondition),
        )

        assertEquals(4, parsed.skippedRows)
        assertTrue(parsed.snapshots.isEmpty())
    }

    private fun row(
        measurement: BodyMeasurementEntity,
        version: Long,
        updatedAt: Long,
        isDeleted: Boolean = false,
    ): List<String> = BodyMeasurementRowMapper.versionedRow(
        measurement, version, updatedAt, isDeleted, "hash-$version", "${measurement.id}:$version",
    ).map { it?.toString().orEmpty() }
}
