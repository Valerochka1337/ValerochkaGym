package com.valerochka1337.valerochkagym.ui.analysis

import androidx.compose.ui.graphics.Color
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.domain.analysis.MuscleLoadSummary
import com.valerochka1337.valerochkagym.domain.analysis.VolumeZone
import com.valerochka1337.valerochkagym.ui.analysis.body.BodyView
import com.valerochka1337.valerochkagym.ui.analysis.body.muscleSectors
import com.valerochka1337.valerochkagym.ui.analysis.body.selectedOutlineColorFor
import com.valerochka1337.valerochkagym.ui.theme.ChartPalette
import org.junit.Assert.assertEquals
import org.junit.Test

class MuscleHeatmapProjectionTest {

    @Test
    fun `shared chest sector follows maximum weekly sets rather than combined volume`() {
        val chest = muscleSectors(BodyView.FRONT).first { it.slug == "chest" }
        val fills = heatmapSectorFillFor(
            mapOf(
                Muscle.UPPER_CHEST to summary(Muscle.UPPER_CHEST, 3.0, VolumeZone.LOW),
                Muscle.LOWER_CHEST to summary(Muscle.LOWER_CHEST, 8.0, VolumeZone.WORKING),
            ),
        )

        assertEquals(ChartPalette.zoneColor(VolumeZone.WORKING), fills(chest))
    }

    @Test
    fun `selected member keeps sector heatmap fill and uses a scrim outline`() {
        val loads = mapOf(
            Muscle.UPPER_CHEST to summary(Muscle.UPPER_CHEST, 12.0, VolumeZone.GROWTH_GUIDE),
            Muscle.LOWER_CHEST to summary(Muscle.LOWER_CHEST, 3.0, VolumeZone.BASE),
        )
        val chest = muscleSectors(BodyView.FRONT).first { it.slug == "chest" }

        val sectorFill = heatmapSectorFillFor(loads)
        assertEquals(ChartPalette.zoneColor(VolumeZone.GROWTH_GUIDE), sectorFill(chest))
        assertEquals(
            Color.Black,
            selectedOutlineColorFor(Muscle.LOWER_CHEST, chest, Color.Black),
        )
    }

    private fun summary(muscle: Muscle, sets: Double, zone: VolumeZone) = MuscleLoadSummary(
        muscle = muscle,
        weeklySets = sets,
        totalSets = sets,
        tonnageKg = 0.0,
        zone = zone,
        sessionsPerWeek = 0.0,
        daysSinceLast = null,
        topExercises = emptyList(),
    )
}
