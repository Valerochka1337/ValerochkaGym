package com.valerochka1337.valerochkagym.ui.analysis.body

import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuscleSectorProjectionTest {

  @Test
  fun `sectors cover every logical muscle and draw every SVG path once`() {
    val members =
        BodyView.entries.flatMap { view -> muscleSectors(view).flatMap { it.members } }.toSet()
    assertEquals(Muscle.entries.toSet(), members)
    assertTrue(offFigureMuscles.isEmpty())

    BodyView.entries.forEach { view ->
      val paths = muscleSectors(view).flatMap { sectorPaths(view, it) }
      assertEquals("$view contains a repeated SVG path", paths.size, paths.toSet().size)
    }
  }

  @Test
  fun `shared sector keeps selected member otherwise uses deterministic default`() {
    val chest = muscleSectors(BodyView.FRONT).first { it.slug == "chest" }

    assertEquals(Muscle.LOWER_CHEST, chest.memberForTap(Muscle.LOWER_CHEST))
    assertEquals(Muscle.UPPER_CHEST, chest.memberForTap(Muscle.TRICEPS))
    assertEquals(Muscle.UPPER_CHEST, chest.memberForTap(null))
  }

  @Test
  fun `frozen shared muscle mappings use their intended sectors`() {
    val front = muscleSectors(BodyView.FRONT).associateBy { it.slug }
    val back = muscleSectors(BodyView.BACK).associateBy { it.slug }

    assertTrue(Muscle.SERRATUS_ANTERIOR in front.getValue("obliques").members)
    assertTrue(Muscle.HIP_FLEXORS in front.getValue("quadriceps").members)
    assertTrue(Muscle.SIDE_DELTS in front.getValue("deltoids").members)
    assertTrue(Muscle.SIDE_DELTS in back.getValue("deltoids").members)
  }

  @Test
  fun `shared sectors resolve maximum load and strongest role without aggregation`() {
    val chest = muscleSectors(BodyView.FRONT).first { it.slug == "chest" }
    val weeklySets = mapOf(Muscle.UPPER_CHEST to 3.0, Muscle.LOWER_CHEST to 8.0)
    val roles = mapOf(Muscle.UPPER_CHEST to 50, Muscle.LOWER_CHEST to 100)

    assertEquals(8.0, chest.maxMember(weeklySets) { it }!!, 0.0)
    assertEquals(100, chest.strongestMember(roles) { it })
  }

  @Test
  fun `logical muscles keep a stable preferred side`() {
    assertEquals(BodyView.FRONT, preferredBodyView(Muscle.LOWER_CHEST))
    assertEquals(BodyView.BACK, preferredBodyView(Muscle.UPPER_BACK))
    assertEquals(BodyView.BACK, preferredBodyView(Muscle.HIP_ABDUCTORS))
  }
}
