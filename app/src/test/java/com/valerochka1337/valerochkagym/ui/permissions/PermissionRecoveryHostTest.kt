package com.valerochka1337.valerochkagym.ui.permissions

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PermissionRecoveryHostTest {

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun `settings callback waits for resumed lifecycle then consumes cancelled return once`() {
    val registry = RecordingRegistry()
    val lifecycle = TestLifecycleOwner(Lifecycle.State.CREATED)
    val actions = mutableListOf<Triple<String, String, Boolean>>()
    var host: PermissionRecoveryHost? = null
    render(registry, lifecycle) {
      host =
          rememberPermissionRecoveryHost(
              kind = "notification",
              permissions = listOf("notification"),
              platform = FakePlatform(settingsIntent = Intent("settings"), granted = false),
              decide = { PermissionRecoveryDecision.OfferSettings },
              markRequestLaunched = {},
              onAction = { token, workoutId, granted ->
                actions += Triple(token, workoutId, granted)
              },
          )
    }

    compose.runOnIdle { host!!.begin("w1", "token-1") }
    compose.waitForIdle()
    compose.runOnIdle { host!!.confirm() }
    compose.waitForIdle()
    registry.dispatchActivityResult(Activity.RESULT_CANCELED)
    compose.waitForIdle()
    assertTrue(actions.isEmpty())

    compose.runOnIdle { lifecycle.resume() }
    compose.waitForIdle()
    compose.runOnIdle { lifecycle.resume() }
    registry.dispatchActivityResult(Activity.RESULT_CANCELED)

    assertEquals(listOf(Triple("token-1", "w1", false)), actions)
  }

  @Test
  fun `armed settings request survives restoration and initial resume does not consume it`() {
    val registry = RecordingRegistry()
    val lifecycle = TestLifecycleOwner(Lifecycle.State.CREATED)
    val actions = mutableListOf<Triple<String, String, Boolean>>()
    var host: PermissionRecoveryHost? = null
    val restoration = StateRestorationTester(compose)
    restoration.setContent {
      CompositionLocalProvider(
          LocalActivityResultRegistryOwner provides RegistryOwner(registry),
          LocalLifecycleOwner provides lifecycle,
      ) {
        host =
            rememberPermissionRecoveryHost(
                kind = "ble",
                permissions = listOf("scan", "connect"),
                platform = FakePlatform(settingsIntent = Intent("settings"), granted = false),
                decide = { PermissionRecoveryDecision.OfferSettings },
                markRequestLaunched = {},
                onAction = { token, workoutId, granted ->
                  actions += Triple(token, workoutId, granted)
                },
            )
      }
    }

    compose.runOnIdle { host!!.begin("w2", "token-2") }
    compose.waitForIdle()
    compose.runOnIdle { host!!.confirm() }
    compose.waitForIdle()
    restoration.emulateSavedInstanceStateRestore()

    compose.runOnIdle { lifecycle.resume() }
    compose.waitForIdle()

    assertTrue(actions.isEmpty())
  }

  @Test
  fun `rapid request confirmation marks and launches once for both permission kinds`() {
    val registry = RecordingRegistry()
    val lifecycle = TestLifecycleOwner(Lifecycle.State.RESUMED)
    var marks = 0
    var notification: PermissionRecoveryHost? = null
    var ble: PermissionRecoveryHost? = null
    render(registry, lifecycle) {
      notification = requestHost("notification", listOf("notification")) { marks++ }
      ble = requestHost("ble", listOf("scan", "connect")) { marks++ }
    }

    compose.runOnIdle {
      notification!!.begin("notification-workout")
      notification!!.begin("notification-workout")
      ble!!.begin("ble-workout")
      ble!!.begin("ble-workout")
    }
    compose.waitForIdle()
    compose.runOnIdle {
      notification!!.confirm()
      notification!!.confirm()
      ble!!.confirm()
      ble!!.confirm()
    }
    compose.waitForIdle()

    assertEquals(2, marks)
    assertEquals(2, registry.permissionLaunches)
  }

  @Test
  fun `rapid already granted starts run one tokenized action for each host`() {
    val registry = RecordingRegistry()
    val lifecycle = TestLifecycleOwner(Lifecycle.State.RESUMED)
    val actions = mutableListOf<Pair<String, String>>()
    var notification: PermissionRecoveryHost? = null
    var ble: PermissionRecoveryHost? = null
    render(registry, lifecycle) {
      notification =
          grantedHost("notification", listOf("notification")) { _, workoutId, _ ->
            actions += "notification" to workoutId
          }
      ble =
          grantedHost("ble", listOf("scan", "connect")) { _, workoutId, _ ->
            actions += "ble" to workoutId
          }
    }

    compose.runOnIdle {
      notification!!.begin("notification-workout")
      notification!!.begin("notification-workout")
      ble!!.begin("ble-workout")
      ble!!.begin("ble-workout")
    }
    compose.waitForIdle()

    assertEquals(listOf("notification" to "notification-workout", "ble" to "ble-workout"), actions)
    assertEquals(0, registry.permissionLaunches)
  }

  @Test
  fun `rapid settings confirmation launches once for both permission kinds`() {
    val registry = RecordingRegistry()
    val lifecycle = TestLifecycleOwner(Lifecycle.State.RESUMED)
    var notification: PermissionRecoveryHost? = null
    var ble: PermissionRecoveryHost? = null
    render(registry, lifecycle) {
      notification = settingsHost("notification", listOf("notification"))
      ble = settingsHost("ble", listOf("scan", "connect"))
    }

    compose.runOnIdle {
      notification!!.begin("notification-workout", "notification-token")
      ble!!.begin("ble-workout", "ble-token")
    }
    compose.waitForIdle()
    compose.runOnIdle {
      notification!!.confirm()
      notification!!.confirm()
      ble!!.confirm()
      ble!!.confirm()
    }
    compose.waitForIdle()

    assertEquals(2, registry.settingsLaunches)
  }

  @Test
  fun `a recreated host gives the same ViewModel sink a new opaque action identity`() {
    val registry = RecordingRegistry()
    val lifecycle = TestLifecycleOwner(Lifecycle.State.RESUMED)
    val consumedByViewModel = mutableSetOf<String>()
    var attached by mutableStateOf(true)
    var host: PermissionRecoveryHost? = null
    render(registry, lifecycle) {
      if (attached) {
        host =
            grantedHost("notification", listOf("notification")) { token, _, _ ->
              consumedByViewModel += token
            }
      }
    }

    compose.runOnIdle { host!!.begin("w1") }
    compose.waitForIdle()
    compose.runOnIdle { attached = false }
    compose.waitForIdle()
    compose.runOnIdle { attached = true }
    compose.waitForIdle()
    compose.runOnIdle { host!!.begin("w1") }
    compose.waitForIdle()

    assertEquals(2, consumedByViewModel.size)
  }

  @Test
  fun `settings launch failure takes the fallback action once`() {
    val registry = RecordingRegistry(throwOnSettingsLaunch = true)
    val lifecycle = TestLifecycleOwner(Lifecycle.State.RESUMED)
    val actions = mutableListOf<Triple<String, String, Boolean>>()
    var host: PermissionRecoveryHost? = null
    render(registry, lifecycle) {
      host =
          rememberPermissionRecoveryHost(
              kind = "notification",
              permissions = listOf("notification"),
              platform = FakePlatform(settingsIntent = Intent("settings"), granted = false),
              decide = { PermissionRecoveryDecision.OfferSettings },
              markRequestLaunched = {},
              onAction = { token, workoutId, granted ->
                actions += Triple(token, workoutId, granted)
              },
          )
    }

    compose.runOnIdle { host!!.begin("w3", "token-3") }
    compose.waitForIdle()
    compose.runOnIdle { host!!.confirm() }
    compose.waitForIdle()

    assertEquals(listOf(Triple("token-3", "w3", false)), actions)
  }

  @androidx.compose.runtime.Composable
  private fun requestHost(
      kind: String,
      permissions: List<String>,
      mark: () -> Unit,
  ) =
      rememberPermissionRecoveryHost(
          kind = kind,
          permissions = permissions,
          platform = FakePlatform(settingsIntent = null, granted = false),
          decide = { PermissionRecoveryDecision.Request },
          markRequestLaunched = { mark() },
          onAction = { _, _, _ -> },
      )

  @androidx.compose.runtime.Composable
  private fun grantedHost(
      kind: String,
      permissions: List<String>,
      action: (String, String, Boolean) -> Unit,
  ) =
      rememberPermissionRecoveryHost(
          kind = kind,
          permissions = permissions,
          platform = FakePlatform(settingsIntent = null, granted = true),
          decide = { PermissionRecoveryDecision.Proceed },
          markRequestLaunched = {},
          onAction = action,
      )

  @androidx.compose.runtime.Composable
  private fun settingsHost(kind: String, permissions: List<String>) =
      rememberPermissionRecoveryHost(
          kind = kind,
          permissions = permissions,
          platform = FakePlatform(settingsIntent = Intent("settings"), granted = false),
          decide = { PermissionRecoveryDecision.OfferSettings },
          markRequestLaunched = {},
          onAction = { _, _, _ -> },
      )

  private fun render(
      registry: RecordingRegistry,
      lifecycle: TestLifecycleOwner,
      content: @androidx.compose.runtime.Composable () -> Unit,
  ) {
    compose.setContent {
      CompositionLocalProvider(
          LocalActivityResultRegistryOwner provides RegistryOwner(registry),
          LocalLifecycleOwner provides lifecycle,
      ) {
        content()
      }
    }
  }

  private class FakePlatform(val settingsIntent: Intent?, val granted: Boolean) :
      PermissionRecoveryPlatform {
    override fun state(permission: String) = LivePermissionState(permission, granted, false)

    override fun appSettingsIntentOrNull() = settingsIntent
  }

  private class RegistryOwner(override val activityResultRegistry: ActivityResultRegistry) :
      ActivityResultRegistryOwner

  private class RecordingRegistry(private val throwOnSettingsLaunch: Boolean = false) :
      ActivityResultRegistry() {
    private var requestCode = -1
    var permissionLaunches = 0
      private set

    var settingsLaunches = 0
      private set

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
      this.requestCode = requestCode
      if (
          contract
              is
              androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
      ) {
        permissionLaunches++
      }
      if (
          contract
              is androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
      ) {
        settingsLaunches++
      }
      if (
          throwOnSettingsLaunch &&
              contract is
                  androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
      ) {
        throw IllegalStateException("settings launcher unavailable")
      }
    }

    fun dispatchActivityResult(resultCode: Int) {
      dispatchResult(requestCode, resultCode, null)
    }
  }

  private class TestLifecycleOwner(initial: Lifecycle.State) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle = registry

    init {
      registry.currentState = initial
    }

    fun resume() {
      registry.currentState = Lifecycle.State.RESUMED
    }
  }
}
