package com.valerochka1337.valerochkagym.ui.health

import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.health.HealthSyncPayloadCodec
import com.valerochka1337.valerochkagym.data.measurements.MeasurementSnapshotCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class HealthConflictFormatterTest {
    @Test
    fun `formatter shows typed health values without exposing canonical JSON`() {
        val report = HealthReportEntity("report", 1, 1, false, "CONFIRMED", "LAB", 1_700_000_000_000, "Биохимия")
        val observation = HealthObservationEntity(
            "observation", "report", 1, 1, false, 1_700_000_000_000,
            "Глюкоза", "NUMBER", "5.2", "ммоль/л", "3.9–5.5", source = "Лаборатория",
        )
        val payload = HealthSyncPayloadCodec.report(report, listOf(observation))

        val shown = HealthConflictFormatter.format(
            HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS,
            payload,
            ZoneId.of("UTC"),
        )!!

        assertEquals("Биохимия", shown.title)
        assertTrue(shown.rows.any { it.contains("Глюкоза: 5.2 ммоль/л") })
        assertFalse(shown.rows.any { it.contains("{\"kind\"") })
    }

    @Test
    fun `formatter renders measurement and restriction tombstones as a human state`() {
        val measurement = MeasurementSnapshotCodec.encode(BodyMeasurementEntity("m", 1_700_000_000_000, weightKg = 70.0), true)
        val restriction = HealthSyncPayloadCodec.restriction(
            HealthRestrictionEntity("r", 1, 1, true, "LIFTED", "MANUAL", 1, description = "Бег"),
        )

        assertEquals("Замер удалён", HealthConflictFormatter.format(HealthSyncCategory.MEASUREMENTS, measurement, ZoneId.of("UTC"))!!.title)
        assertEquals("Ограничение удалено", HealthConflictFormatter.format(HealthSyncCategory.HEALTH_RESTRICTIONS, restriction, ZoneId.of("UTC"))!!.title)
    }

    @Test
    fun `malformed payload is not rendered`() {
        assertNull(HealthConflictFormatter.format(HealthSyncCategory.MEASUREMENTS, "not a snapshot"))
    }
}
