package com.valerochka1337.valerochkagym.domain.analysis

import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ориентиры недельного объёма: зоны, полнота таблицы и ступенчатый вес подхода. */
class MuscleLandmarksTest {

    @Test
    fun `zero or negative weekly sets fall into the NONE zone`() {
        assertEquals(VolumeZone.NONE, Muscle.CHEST.zoneFor(0.0))
        assertEquals(VolumeZone.NONE, Muscle.CHEST.zoneFor(-1.0))
    }

    @Test
    fun `sets below MEV are BELOW_MEV and the MEV boundary itself is maintenance`() {
        // У груди MEV = 8: чуть ниже — недобор, ровно на границе — уже поддержка.
        assertEquals(VolumeZone.BELOW_MEV, Muscle.CHEST.zoneFor(0.5))
        assertEquals(VolumeZone.BELOW_MEV, Muscle.CHEST.zoneFor(7.99))
        assertEquals(VolumeZone.MAINTENANCE, Muscle.CHEST.zoneFor(8.0))
    }

    @Test
    fun `the adaptive corridor from MAV low to MRV inclusive is OPTIMAL`() {
        // У груди MAV начинается с 12, MRV = 22.
        assertEquals(VolumeZone.MAINTENANCE, Muscle.CHEST.zoneFor(11.99))
        assertEquals(VolumeZone.OPTIMAL, Muscle.CHEST.zoneFor(12.0))
        assertEquals(VolumeZone.OPTIMAL, Muscle.CHEST.zoneFor(22.0))
        assertEquals(VolumeZone.EXCESSIVE, Muscle.CHEST.zoneFor(22.01))
    }

    @Test
    fun `a muscle with zero MEV never reports BELOW_MEV`() {
        // Передняя дельта: MEV = 0 — любая ненулевая работа сразу хотя бы поддержка.
        assertEquals(VolumeZone.MAINTENANCE, Muscle.FRONT_DELTS.zoneFor(0.1))
        assertEquals(VolumeZone.NONE, Muscle.FRONT_DELTS.zoneFor(0.0))
    }

    @Test
    fun `every muscle has explicit landmarks in the table`() {
        Muscle.entries.forEach { muscle ->
            assertTrue("нет ориентиров для $muscle", muscleLandmarks.containsKey(muscle))
        }
    }

    @Test
    fun `landmarks of every muscle are ordered mev mavLow mavHigh mrv`() {
        Muscle.entries.forEach { muscle ->
            val landmarks = muscle.landmarks()
            assertTrue("$muscle: mev > mavLow", landmarks.mev <= landmarks.mavLow)
            assertTrue("$muscle: mavLow > mavHigh", landmarks.mavLow <= landmarks.mavHigh)
            assertTrue("$muscle: mavHigh > mrv", landmarks.mavHigh <= landmarks.mrv)
        }
    }

    @Test
    fun `meta-analytic range is the shared ten to twenty sets band`() {
        assertEquals(10.0, META_ANALYTIC_RANGE.start, 1e-9)
        assertEquals(20.0, META_ANALYTIC_RANGE.endInclusive, 1e-9)
    }

    @Test
    fun `set weight is stepped by contribution direct half indirect zero stabilizer`() {
        assertEquals(1.0, setWeightFor(100), 1e-9)
        assertEquals(1.0, setWeightFor(60), 1e-9)
        assertEquals(0.5, setWeightFor(59), 1e-9)
        assertEquals(0.5, setWeightFor(25), 1e-9)
        assertEquals(0.0, setWeightFor(24), 1e-9)
        assertEquals(0.0, setWeightFor(0), 1e-9)
    }
}
