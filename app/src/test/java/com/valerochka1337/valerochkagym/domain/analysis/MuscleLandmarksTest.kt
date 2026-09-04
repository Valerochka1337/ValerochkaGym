package com.valerochka1337.valerochkagym.domain.analysis

import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import org.junit.Assert.assertEquals
import org.junit.Test

/** Единая шкала объёма и дискретный вклад мышц. */
class MuscleLandmarksTest {

  @Test
  fun `zero through two weekly sets are in the low volume zone`() {
    assertEquals(VolumeZone.LOW, Muscle.UPPER_CHEST.zoneFor(-1.0))
    assertEquals(VolumeZone.LOW, Muscle.UPPER_CHEST.zoneFor(0.0))
    assertEquals(VolumeZone.LOW, Muscle.UPPER_CHEST.zoneFor(2.0))
  }

  @Test
  fun `more than two and less than five weekly sets are in the base zone`() {
    assertEquals(VolumeZone.BASE, Muscle.UPPER_CHEST.zoneFor(2.01))
    assertEquals(VolumeZone.BASE, Muscle.UPPER_CHEST.zoneFor(4.99))
  }

  @Test
  fun `five through less than ten weekly sets are in the working zone`() {
    assertEquals(VolumeZone.WORKING, Muscle.UPPER_CHEST.zoneFor(5.0))
    assertEquals(VolumeZone.WORKING, Muscle.UPPER_CHEST.zoneFor(9.99))
  }

  @Test
  fun `ten or more weekly sets are in the growth guide zone`() {
    assertEquals(VolumeZone.GROWTH_GUIDE, Muscle.UPPER_CHEST.zoneFor(10.0))
    assertEquals(VolumeZone.GROWTH_GUIDE, Muscle.UPPER_CHEST.zoneFor(100.0))
  }

  @Test
  fun `direct and indirect contributions use discrete effective set weights`() {
    assertEquals(1.0, setWeightFor(100), 1e-9)
    assertEquals(1.0, setWeightFor(60), 1e-9)
    assertEquals(0.5, setWeightFor(59), 1e-9)
    assertEquals(0.5, setWeightFor(25), 1e-9)
    assertEquals(0.0, setWeightFor(24), 1e-9)
    assertEquals(0.0, setWeightFor(0), 1e-9)
  }

  @Test
  fun `the volume guide exposes the two five and ten boundaries`() {
    assertEquals(2.0, HypertrophyVolumeGuide.LOW_MAX, 1e-9)
    assertEquals(5.0, HypertrophyVolumeGuide.BASE_MAX, 1e-9)
    assertEquals(10.0, HypertrophyVolumeGuide.WORKING_MAX, 1e-9)
  }
}
