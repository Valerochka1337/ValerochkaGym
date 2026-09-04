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

    @Test
    fun `focus profile strengthens the item at the viewport centre`() {
        val centered = MuscleSelectorState.focusProfile(
            item = MuscleSelectorState.VisibleItem(index = 11, offset = 100, size = 100),
            viewportStart = 0,
            viewportEnd = 300,
        )
        val neighbor = MuscleSelectorState.focusProfile(
            item = MuscleSelectorState.VisibleItem(index = 10, offset = 0, size = 100),
            viewportStart = 0,
            viewportEnd = 300,
        )

        assertEquals(1f, centered.amount)
        assertEquals(1f, centered.scale)
        assertEquals(1f, centered.alpha)
        assertTrue(centered.scale > neighbor.scale)
        assertTrue(centered.alpha > neighbor.alpha)
    }

    @Test
    fun `focus profile changes continuously and clamps beyond the viewport`() {
        val nearer = MuscleSelectorState.focusProfile(
            item = MuscleSelectorState.VisibleItem(index = 11, offset = 125, size = 100),
            viewportStart = 0,
            viewportEnd = 300,
        )
        val farther = MuscleSelectorState.focusProfile(
            item = MuscleSelectorState.VisibleItem(index = 11, offset = 150, size = 100),
            viewportStart = 0,
            viewportEnd = 300,
        )
        val outside = MuscleSelectorState.focusProfile(
            item = MuscleSelectorState.VisibleItem(index = 13, offset = 400, size = 100),
            viewportStart = 0,
            viewportEnd = 300,
        )
        val fartherOutside = MuscleSelectorState.focusProfile(
            item = MuscleSelectorState.VisibleItem(index = 14, offset = 700, size = 100),
            viewportStart = 0,
            viewportEnd = 300,
        )

        assertTrue(nearer.amount > farther.amount)
        assertTrue(nearer.scale > farther.scale)
        assertTrue(nearer.alpha > farther.alpha)
        assertEquals(0f, outside.amount)
        assertEquals(0.96f, outside.scale)
        assertEquals(0.72f, outside.alpha)
        assertEquals(outside.scale, fartherOutside.scale)
        assertEquals(outside.alpha, fartherOutside.alpha)
    }

    @Test
    fun `focus profile resolves a matching visible item`() {
        val profile = MuscleSelectorState.focusProfileFor(
            virtualIndex = 11,
            visibleItems = listOf(MuscleSelectorState.VisibleItem(index = 11, offset = 100, size = 100)),
            viewportStart = 0,
            viewportEnd = 300,
        )

        assertEquals(1f, profile.amount)
        assertEquals(1f, profile.scale)
        assertEquals(1f, profile.alpha)
    }

    @Test
    fun `focus profile uses the requested virtual index among visible items`() {
        val profile = MuscleSelectorState.focusProfileFor(
            virtualIndex = 10,
            visibleItems = listOf(
                MuscleSelectorState.VisibleItem(index = 10, offset = 0, size = 100),
                MuscleSelectorState.VisibleItem(index = 11, offset = 100, size = 100),
                MuscleSelectorState.VisibleItem(index = 12, offset = 200, size = 100),
            ),
            viewportStart = 0,
            viewportEnd = 300,
        )

        assertTrue(profile.amount < 1f)
        assertTrue(profile.scale < 1f)
        assertTrue(profile.alpha < 1f)
    }

    @Test
    fun `focus profile returns neutral for a non-visible virtual index`() {
        val profile = MuscleSelectorState.focusProfileFor(
            virtualIndex = 11,
            visibleItems = listOf(MuscleSelectorState.VisibleItem(index = 10, offset = 0, size = 100)),
            viewportStart = 0,
            viewportEnd = 300,
        )

        assertEquals(MuscleSelectorState.FocusProfile.Neutral, profile)
    }

    @Test
    fun `focus profile stays neutral until lazy geometry is available`() {
        val profile = MuscleSelectorState.focusProfile(
            item = MuscleSelectorState.VisibleItem(index = 0, offset = 0, size = 0),
            viewportStart = 0,
            viewportEnd = 0,
        )

        assertEquals(MuscleSelectorState.FocusProfile.Neutral, profile)
    }
}
