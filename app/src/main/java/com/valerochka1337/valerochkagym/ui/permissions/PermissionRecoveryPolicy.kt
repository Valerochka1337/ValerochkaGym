package com.valerochka1337.valerochkagym.ui.permissions

/** Live Android permission state enriched with the durable request history. */
data class PermissionSnapshot(
    val permission: String,
    val granted: Boolean,
    val requestedBefore: Boolean,
    val shouldShowRationale: Boolean,
)

sealed interface PermissionRecoveryDecision {
  data object Proceed : PermissionRecoveryDecision

  data object Request : PermissionRecoveryDecision

  data object OfferSettings : PermissionRecoveryDecision
}

/**
 * Decides a permission recovery step without retaining a host or a continuation.
 *
 * Android returns `shouldShowRequestPermissionRationale == false` both before the first request and
 * after a permanent denial. [PermissionSnapshot.requestedBefore] resolves that ambiguity.
 */
object PermissionRecoveryPolicy {
  fun decide(snapshots: List<PermissionSnapshot>): PermissionRecoveryDecision {
    if (snapshots.all { it.granted }) return PermissionRecoveryDecision.Proceed
    return if (snapshots.any { !it.granted && it.requestedBefore && !it.shouldShowRationale }) {
      PermissionRecoveryDecision.OfferSettings
    } else {
      PermissionRecoveryDecision.Request
    }
  }
}
