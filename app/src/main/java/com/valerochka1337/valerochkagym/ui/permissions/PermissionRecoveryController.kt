package com.valerochka1337.valerochkagym.ui.permissions

import com.valerochka1337.valerochkagym.data.settings.PermissionRequestHistory
import javax.inject.Inject
import javax.inject.Singleton

/** DI seam for contextual hosts. It owns history but never keeps an Activity or pending action. */
@Singleton
class PermissionRecoveryController
@Inject
constructor(private val history: PermissionRequestHistory) {

  suspend fun decide(live: List<LivePermissionState>): PermissionRecoveryDecision =
      PermissionRecoveryPolicy.decide(
          live.map { state ->
            PermissionSnapshot(
                permission = state.permission,
                granted = state.granted,
                requestedBefore = history.wasRequested(state.permission),
                shouldShowRationale = state.shouldShowRationale,
            )
          },
      )

  suspend fun markRequestLaunched(permissions: Collection<String>) {
    history.markRequested(permissions)
  }
}

data class LivePermissionState(
    val permission: String,
    val granted: Boolean,
    val shouldShowRationale: Boolean,
)
