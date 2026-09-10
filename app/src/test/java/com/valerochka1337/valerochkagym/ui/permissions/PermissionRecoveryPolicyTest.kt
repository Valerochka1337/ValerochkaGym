package com.valerochka1337.valerochkagym.ui.permissions

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionRecoveryPolicyTest {

  @Test
  fun `first denial requests because false rationale is not permanent`() {
    assertEquals(
        PermissionRecoveryDecision.Request,
        PermissionRecoveryPolicy.decide(
            listOf(
                PermissionSnapshot(
                    "p",
                    granted = false,
                    requestedBefore = false,
                    shouldShowRationale = false,
                )
            )
        ),
    )
  }

  @Test
  fun `repeated denial without rationale offers settings`() {
    assertEquals(
        PermissionRecoveryDecision.OfferSettings,
        PermissionRecoveryPolicy.decide(
            listOf(
                PermissionSnapshot(
                    "p",
                    granted = false,
                    requestedBefore = true,
                    shouldShowRationale = false,
                )
            )
        ),
    )
  }

  @Test
  fun `partial bluetooth grant does not proceed`() {
    assertEquals(
        PermissionRecoveryDecision.Request,
        PermissionRecoveryPolicy.decide(
            listOf(
                PermissionSnapshot(
                    "scan",
                    granted = true,
                    requestedBefore = false,
                    shouldShowRationale = false,
                ),
                PermissionSnapshot(
                    "connect",
                    granted = false,
                    requestedBefore = false,
                    shouldShowRationale = true,
                ),
            )
        ),
    )
  }
}
