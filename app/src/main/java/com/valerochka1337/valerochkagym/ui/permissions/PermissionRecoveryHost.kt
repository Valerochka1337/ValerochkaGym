package com.valerochka1337.valerochkagym.ui.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.UUID

/** Shared contextual host for a permission request and its optional Settings round trip. */
@Stable
class PermissionRecoveryHost
internal constructor(
    val showDialog: Boolean,
    val offersSettings: Boolean,
    private val beginRecovery: (Pair<String, String>) -> Unit,
    private val confirmRecovery: () -> Unit,
    private val cancelRecovery: () -> Unit,
) {
  fun begin(workoutId: String, actionToken: String = UUID.randomUUID().toString()) =
      beginRecovery(workoutId to actionToken)

  fun confirm() = confirmRecovery()

  fun cancel() = cancelRecovery()
}

/**
 * Saves only primitive pending work. The ViewModel receives the opaque [token] synchronously before
 * this host clears composition state, so a recreation cannot lose a continuation after a result.
 */
@Composable
fun rememberPermissionRecoveryHost(
    kind: String,
    permissions: List<String>,
    platform: PermissionRecoveryPlatform,
    decide: suspend (List<LivePermissionState>) -> PermissionRecoveryDecision,
    markRequestLaunched: suspend (Collection<String>) -> Unit,
    onAction: (token: String, workoutId: String, allGranted: Boolean) -> Unit,
): PermissionRecoveryHost {
  val lifecycleOwner = LocalLifecycleOwner.current
  var showDialog by rememberSaveable(kind) { mutableStateOf(false) }
  var pendingKind by rememberSaveable(kind) { mutableStateOf<String?>(null) }
  var pendingWorkoutId by rememberSaveable(kind) { mutableStateOf<String?>(null) }
  var pendingToken by rememberSaveable(kind) { mutableStateOf<String?>(null) }
  var settingsLaunchToken by rememberSaveable(kind) { mutableStateOf<String?>(null) }
  var settingsPhase by rememberSaveable(kind) { mutableStateOf(PermissionSettingsPhase.None) }
  var decisionPending by rememberSaveable(kind) { mutableStateOf(false) }
  var requestLaunchToken by rememberSaveable(kind) { mutableStateOf<String?>(null) }
  var offersSettings by rememberSaveable(kind) { mutableStateOf(false) }

  fun pending(): PermissionSettingsPending? =
      pendingWorkoutId?.let { workoutId ->
        pendingKind?.let { pendingKind ->
          pendingToken?.let { token ->
            PermissionSettingsPending(pendingKind, token, workoutId, settingsPhase)
          }
        }
      }
  fun isCurrent(token: String) = pendingKind == kind && pendingToken == token
  fun clearPending() {
    showDialog = false
    pendingKind = null
    pendingWorkoutId = null
    settingsPhase = PermissionSettingsPhase.None
    decisionPending = false
    requestLaunchToken = null
    offersSettings = false
  }
  fun finishCurrent(token: String) {
    val workoutId = pendingWorkoutId ?: return
    if (!isCurrent(token)) return
    val allGranted = permissions.all { platform.state(it).granted }
    clearPending()
    onAction(token, workoutId, allGranted)
  }
  fun maybeConsumeReturned() {
    if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    val current = pending() ?: return
    if (current.consumeReturned(current.token) != null) return
    finishCurrent(current.token)
  }

  val permissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingToken?.let(::finishCurrent)
      }
  val settingsLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val returned = pending()?.returnedBy(settingsLaunchToken)
        if (returned != null) settingsPhase = returned.phase
        maybeConsumeReturned()
      }

  LaunchedEffect(decisionPending, pendingToken) {
    val token = pendingToken ?: return@LaunchedEffect
    if (!decisionPending || !isCurrent(token)) return@LaunchedEffect
    when (decide(permissions.map(platform::state))) {
      PermissionRecoveryDecision.Proceed -> if (isCurrent(token)) finishCurrent(token)
      PermissionRecoveryDecision.Request ->
          if (isCurrent(token)) {
            decisionPending = false
            offersSettings = false
            showDialog = true
          }
      PermissionRecoveryDecision.OfferSettings ->
          if (isCurrent(token)) {
            decisionPending = false
            offersSettings = true
            showDialog = true
          }
    }
  }
  LaunchedEffect(requestLaunchToken) {
    val token = requestLaunchToken
    if (token == null || !isCurrent(token)) return@LaunchedEffect
    markRequestLaunched(permissions)
    if (!isCurrent(token) || requestLaunchToken != token) return@LaunchedEffect
    requestLaunchToken = null
    permissionLauncher.launch(permissions.toTypedArray())
  }
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) maybeConsumeReturned()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  return PermissionRecoveryHost(
      showDialog = showDialog,
      offersSettings = offersSettings,
      beginRecovery = { (workoutId, actionToken) ->
        if (pendingKind != null) return@PermissionRecoveryHost
        pendingKind = kind
        pendingWorkoutId = workoutId
        pendingToken = actionToken
        settingsPhase = PermissionSettingsPhase.None
        decisionPending = true
      },
      confirmRecovery = {
        val current = pending() ?: return@PermissionRecoveryHost
        if (!showDialog || current.phase != PermissionSettingsPhase.None) {
          return@PermissionRecoveryHost
        }
        if (offersSettings) {
          val intent = platform.appSettingsIntentOrNull()
          if (intent == null) {
            finishCurrent(current.token)
          } else {
            showDialog = false
            settingsPhase = PermissionSettingsPhase.Armed
            settingsLaunchToken = current.token
            runCatching { settingsLauncher.launch(intent) }
                .onFailure { finishCurrent(current.token) }
          }
        } else {
          showDialog = false
          requestLaunchToken = current.token
        }
      },
      cancelRecovery = {
        val current = pending() ?: return@PermissionRecoveryHost
        finishCurrent(current.token)
      },
  )
}
