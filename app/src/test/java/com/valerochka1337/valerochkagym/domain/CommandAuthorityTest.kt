package com.valerochka1337.valerochkagym.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandAuthorityTest {
  @Test
  fun `authority accepts only the exact packet and immutable anchors`() {
    val packet =
        WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.EditSet("set-a", reps = 6)))
    val anchors = CommandAuthority.Anchors("set-a", "set-before", "set-next")
    val authority = CommandAuthority.local(packet, anchors)
    assertTrue(authority.authorizes(packet, anchors))
    assertFalse(
        authority.authorizes(
            WorkoutChangeSet.Packet(
                listOf(
                    WorkoutChangeSet.Operation.EditSet("set-a", reps = 6),
                    WorkoutChangeSet.Operation.AddSet("section-a"),
                )
            ),
            anchors,
        )
    )
    assertFalse(authority.authorizes(packet, anchors.copy(currentSetId = "other")))
  }
}
