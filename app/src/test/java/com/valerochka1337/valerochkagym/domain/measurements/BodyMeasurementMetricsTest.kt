package com.valerochka1337.valerochkagym.domain.measurements

import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyMeasurementMetricsTest {

    @Test
    fun `fat mass calculates from weight and body fat percentage`() {
        assertEquals(17.5, calculateBodyFatMassKg(70.0, 25.0)!!, 1e-6)
        assertNull(calculateBodyFatMassKg(70.0, null))
    }

    @Test
    fun `whr calculates from waist and hips when it is not entered`() {
        val measurement = measurement(waistCm = 72.0, hipsCm = 96.0)

        assertEquals(0.75, calculateWaistHipRatio(72.0, 96.0)!!, 1e-6)
        assertEquals(0.75, measurement.effectiveWaistHipRatio()!!, 1e-6)
    }

    @Test
    fun `whr keeps the explicitly entered InBody value`() {
        val measurement = measurement(waistHipRatio = 0.82, waistCm = 72.0, hipsCm = 96.0)

        assertEquals(0.82, measurement.effectiveWaistHipRatio()!!, 1e-6)
    }

    @Test
    fun `period filter keeps only measurements inside the selected calendar window`() {
        val now = millis("2026-08-06")
        val recent = measurement(id = "recent", measuredAt = millis("2026-06-01"))
        val boundary = measurement(id = "boundary", measuredAt = millis("2026-05-06"))
        val old = measurement(id = "old", measuredAt = millis("2026-05-05"))

        val filtered = filterMeasurementsByPeriod(
            measurements = listOf(recent, boundary, old),
            period = MeasurementPeriod.MONTHS_3,
            nowMillis = now,
            zone = ZoneOffset.UTC,
        )

        assertEquals(listOf("recent", "boundary"), filtered.map { it.id })
    }

    @Test
    fun `latest comparison skips missing previous values instead of replacing them with zero`() {
        val latest = measurement(id = "latest", measuredAt = 3_000, weightKg = 70.0, chestCm = null)
        val missing = measurement(id = "missing", measuredAt = 2_000, weightKg = null, chestCm = 95.0)
        val previous = measurement(id = "previous", measuredAt = 1_000, weightKg = 72.5, chestCm = null)

        val comparisons = latestMetricComparisons(listOf(latest, missing, previous))
        val weight = comparisons.first { it.metric == BodyMeasurementMetric.WEIGHT }

        assertEquals(72.5, weight.previousValue!!, 1e-6)
        assertEquals(-2.5, weight.delta!!, 1e-6)
        assertFalse(comparisons.any { it.metric == BodyMeasurementMetric.CHEST })
    }

    @Test
    fun `a missing metric has no chart value`() = runTest {
        val measurement = measurement(weightKg = null, bodyFatPercentage = 25.0)

        assertNull(BodyMeasurementMetric.WEIGHT.value(measurement))
        assertNull(BodyMeasurementMetric.BODY_FAT_MASS.value(measurement))
        assertTrue(BodyMeasurementMetric.BODY_FAT_PERCENTAGE.value(measurement) != null)
    }

    private fun measurement(
        id: String = "m",
        measuredAt: Long = 1_000,
        weightKg: Double? = null,
        bodyFatPercentage: Double? = null,
        waistHipRatio: Double? = null,
        waistCm: Double? = null,
        chestCm: Double? = null,
        hipsCm: Double? = null,
    ): BodyMeasurementEntity = BodyMeasurementEntity(
        id = id,
        measuredAt = measuredAt,
        weightKg = weightKg,
        bodyFatPercentage = bodyFatPercentage,
        waistHipRatio = waistHipRatio,
        waistCm = waistCm,
        chestCm = chestCm,
        hipsCm = hipsCm,
    )

    private fun millis(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
