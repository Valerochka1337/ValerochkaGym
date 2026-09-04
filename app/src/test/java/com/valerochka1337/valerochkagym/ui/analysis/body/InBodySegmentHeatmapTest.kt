package com.valerochka1337.valerochkagym.ui.analysis.body

import android.app.Application
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegmentValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class InBodySegmentHeatmapTest {

    @Test
    fun `lean percentages map deficit through sufficient heat zones`() {
        val cases = listOf(
            null to InBodyHeatZone.NO_DATA,
            89.9 to InBodyHeatZone.RED,
            90.0 to InBodyHeatZone.AMBER,
            99.9 to InBodyHeatZone.AMBER,
            100.0 to InBodyHeatZone.GREEN,
        )

        cases.forEach { (percentage, expected) ->
            val values = InBodySegmentValues(leanPercentage = percentage)
            assertEquals("lean $percentage", expected, values.heatZoneFor(InBodySegmentMapMode.LEAN))
        }
    }

    @Test
    fun `fat percentages map reference through excess heat zones`() {
        val cases = listOf(
            null to InBodyHeatZone.NO_DATA,
            100.0 to InBodyHeatZone.GREEN,
            100.1 to InBodyHeatZone.AMBER,
            160.0 to InBodyHeatZone.AMBER,
            160.1 to InBodyHeatZone.RED,
            180.0 to InBodyHeatZone.RED,
        )

        cases.forEach { (percentage, expected) ->
            val values = InBodySegmentValues(fatPercentage = percentage)
            assertEquals("fat $percentage", expected, values.heatZoneFor(InBodySegmentMapMode.FAT))
        }
    }

    @Test
    fun `mass without percentage maps to no data`() {
        val values = InBodySegmentValues(
            leanMassKg = 20.0,
            fatMassKg = 10.0,
        )

        assertEquals(InBodyHeatZone.NO_DATA, values.heatZoneFor(InBodySegmentMapMode.LEAN))
        assertEquals(InBodyHeatZone.NO_DATA, values.heatZoneFor(InBodySegmentMapMode.FAT))
    }

    @Test
    fun `tibialis SVG paths stay classified as legs`() {
        val body = ParsedBody.of(BodyView.FRONT)
        val tibialis = body.sectors.first { it.sector.slug == "tibialis" }

        tibialis.paths.forEach { path ->
            val segment = inBodySegmentFor(Muscle.TIBIALIS_ANTERIOR, path, BodyView.FRONT, body.viewportW)
            assertTrue(segment == com.valerochka1337.valerochkagym.domain.measurements.InBodySegment.LEFT_LEG ||
                segment == com.valerochka1337.valerochkagym.domain.measurements.InBodySegment.RIGHT_LEG)
        }
    }
}
