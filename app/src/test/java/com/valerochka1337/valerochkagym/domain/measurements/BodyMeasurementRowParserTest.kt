package com.valerochka1337.valerochkagym.domain.measurements

import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
