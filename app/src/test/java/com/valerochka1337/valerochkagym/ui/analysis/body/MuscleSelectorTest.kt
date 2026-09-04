package com.valerochka1337.valerochkagym.ui.analysis.body

import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuscleSelectorTest {

    @Test
    fun `virtual indices map cyclically to logical muscles`() {
        assertEquals(Muscle.NECK, MuscleSelectorState.muscleAt(-1))
        assertEquals(Muscle.UPPER_CHEST, MuscleSelectorState.muscleAt(Muscle.entries.size))
        assertEquals(
            listOf(Muscle.TRAPS, Muscle.NECK, Muscle.UPPER_CHEST),
            MuscleSelectorState.visible(Muscle.NECK),
        )
    }

    @Test
    fun `external muscle resolves to the nearest equivalent virtual index`() {
        val from = MuscleSelectorState.anchoredIndex(Muscle.UPPER_CHEST) + Muscle.entries.size * 3
        val target = MuscleSelectorState.nearestEquivalentIndex(from, Muscle.NECK)

        assertEquals(Muscle.NECK, MuscleSelectorState.muscleAt(target))
        assertEquals(1, kotlin.math.abs(target - from))
    }

    @Test
    fun `centered item is selected from the viewport midpoint`() {
        val centered = MuscleSelectorState.centeredIndex(
            items = listOf(
                MuscleSelectorState.VisibleItem(index = 10, offset = 0, size = 100),
                MuscleSelectorState.VisibleItem(index = 11, offset = 100, size = 100),
                MuscleSelectorState.VisibleItem(index = 12, offset = 200, size = 100),
            ),
            viewportStart = 0,
            viewportEnd = 300,
        )

        assertEquals(11, centered)
    }

    @Test
    fun `only edge-near virtual indices require silent recentering`() {
        assertTrue(MuscleSelectorState.shouldRecenter(0))
        assertFalse(MuscleSelectorState.shouldRecenter(MuscleSelectorState.middleAnchor))
    }

    @Test
    fun `external selection waits for a user drag or fling to settle`() {
        assertFalse(
            MuscleSelectorState.canReconcileExternal(
                pendingExternal = Muscle.LATS,
                settledMuscle = Muscle.UPPER_CHEST,
                userInteractionInProgress = true,
            ),
        )
        assertTrue(
            MuscleSelectorState.canReconcileExternal(
                pendingExternal = Muscle.LATS,
                settledMuscle = Muscle.UPPER_CHEST,
                userInteractionInProgress = false,
            ),
        )
    }
}
