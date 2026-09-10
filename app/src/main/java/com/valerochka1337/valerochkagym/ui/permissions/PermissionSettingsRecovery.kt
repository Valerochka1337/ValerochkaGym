package com.valerochka1337.valerochkagym.ui.permissions

/** Primitive saveable settings-return phase. A normal configuration resume never consumes Armed. */
enum class PermissionSettingsPhase {
  None,
  Armed,
  Returned,
}

data class PermissionSettingsPending(
    val kind: String,
    val token: String,
    val workoutId: String,
    val phase: PermissionSettingsPhase,
)

fun PermissionSettingsPending?.returnedBy(token: String?): PermissionSettingsPending? =
    this?.let { pending ->
      if (pending.token == token && pending.phase == PermissionSettingsPhase.Armed) {
        pending.copy(phase = PermissionSettingsPhase.Returned)
      } else {
        pending
      }
    }

fun PermissionSettingsPending?.consumeReturned(token: String): PermissionSettingsPending? =
    if (this?.token == token && phase == PermissionSettingsPhase.Returned) null else this
