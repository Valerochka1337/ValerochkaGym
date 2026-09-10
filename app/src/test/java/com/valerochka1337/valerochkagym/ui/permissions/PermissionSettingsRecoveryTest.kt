package com.valerochka1337.valerochkagym.ui.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionSettingsRecoveryTest {

  @Test
  fun `initial resume and configuration recreation keep an armed settings request`() {
    val armed = pending(phase = PermissionSettingsPhase.Armed)

    assertEquals(armed, armed.returnedBy(token = "8"))
    assertEquals(armed, armed.consumeReturned(token = "7"))
  }

  @Test
  fun `only the matching settings callback makes an armed request consumable once`() {
    val armed = pending(phase = PermissionSettingsPhase.Armed)

    val returned = armed.returnedBy(token = "7")

    assertEquals(PermissionSettingsPhase.Returned, returned?.phase)
    assertNull(returned.consumeReturned(token = "7"))
    assertNull(returned.consumeReturned(token = "7").consumeReturned(token = "7"))
  }

  @Test
  fun `a stale callback cannot change a newer settings request`() {
    val newer = pending(token = "9", workoutId = "new", phase = PermissionSettingsPhase.Armed)

    assertEquals(newer, newer.returnedBy(token = "7"))
    assertEquals(newer, newer.consumeReturned(token = "9"))
  }

  @Test
  fun `a returned pending request retains its exact workout identity until consumption`() {
    val returned =
        pending(workoutId = "workout-42", phase = PermissionSettingsPhase.Armed).returnedBy("7")

    assertEquals("workout-42", returned?.workoutId)
    assertEquals("notification", returned?.kind)
  }

  private fun pending(
      token: String = "7",
      workoutId: String = "workout-7",
      phase: PermissionSettingsPhase,
  ) =
      PermissionSettingsPending(
          kind = "notification",
          token = token,
          workoutId = workoutId,
          phase = phase,
      )
}
