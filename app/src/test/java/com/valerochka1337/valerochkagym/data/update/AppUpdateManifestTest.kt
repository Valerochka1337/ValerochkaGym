package com.valerochka1337.valerochkagym.data.update

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AppUpdateManifestTest {

  @Test
  fun `manifest allows sessions and keeps their callback private`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val packageInfo =
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_PERMISSIONS or
                        PackageManager.GET_PROVIDERS or
                        PackageManager.GET_RECEIVERS)
                    .toLong(),
            ),
        )

    assertTrue(
        packageInfo.requestedPermissions
            .orEmpty()
            .contains(Manifest.permission.REQUEST_INSTALL_PACKAGES),
    )
    assertFalse(
        packageInfo.providers.orEmpty().any { it.authority == "${context.packageName}.updates" },
    )
    val callbackReceiver =
        packageInfo.receivers.orEmpty().single {
          it.name == AppUpdateInstallStatusReceiver::class.java.name
        }
    assertFalse(callbackReceiver.exported)
    val replacementReceiver =
        packageInfo.receivers.orEmpty().single {
          it.name == AppUpdateReplacementReceiver::class.java.name
        }
    assertFalse(replacementReceiver.exported)
  }

  @Test
  fun `manifest maps package replacement only to the private replacement receiver`() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    val receivers =
        context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(context.packageName),
            PackageManager.ResolveInfoFlags.of(0L),
        )

    assertEquals(
        listOf(AppUpdateReplacementReceiver::class.java.name),
        receivers.map { it.activityInfo.name },
    )
  }

  @Test
  fun `session callback forwards the system confirmation intent`() {
    val confirmation = Intent("test.confirm.install")

    val event =
        Intent(AppUpdateInstallStatusReceiver.ACTION_INSTALL_STATUS)
            .apply {
              putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
              putExtra(Intent.EXTRA_INTENT, confirmation)
            }
            .toAppUpdateInstallEvent() as AppUpdateInstallEvent.UserActionRequired

    assertEquals(confirmation.action, event.intent.action)
  }

  @Test
  fun `blocked installation callback becomes a user friendly error`() {
    val event =
        Intent(AppUpdateInstallStatusReceiver.ACTION_INSTALL_STATUS)
            .apply {
              putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_BLOCKED)
              putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "INSTALL_FAILED_VERIFICATION_FAILURE")
            }
            .toAppUpdateInstallEvent() as AppUpdateInstallEvent.Failed

    assertEquals("Защита устройства заблокировала установку обновления", event.message)
  }

  @Test
  fun `installer stages the apk in a package installer session`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val packageInstaller = context.packageManager.packageInstaller
    var createdSessionId: Int? = null
    var finishedSuccessfully = false
    packageInstaller.registerSessionCallback(
        object : PackageInstaller.SessionCallback() {
          override fun onCreated(sessionId: Int) {
            createdSessionId = sessionId
          }

          override fun onBadgingChanged(sessionId: Int) = Unit

          override fun onActiveChanged(sessionId: Int, active: Boolean) = Unit

          override fun onProgressChanged(sessionId: Int, progress: Float) = Unit

          override fun onFinished(sessionId: Int, success: Boolean) {
            if (sessionId == createdSessionId) finishedSuccessfully = success
          }
        },
        Handler(Looper.getMainLooper()),
    )
    val file =
        File(context.cacheDir, "session-update.apk").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
    val release = testRelease(sizeBytes = file.length())

    AndroidAppUpdateInstaller(context, AppUpdateInstallEventBus()).startInstallation(file, release)
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue(createdSessionId != null)
    assertTrue(finishedSuccessfully)
    file.delete()
  }

  private fun testRelease(sizeBytes: Long): AppRelease =
      AppRelease(
          tagName = "v9.9.9",
          versionName = "9.9.9",
          title = "ValerochkaGym 9.9.9",
          notes = null,
          pageUrl = "https://github.com/Valerochka1337/ValerochkaGym/releases/tag/v9.9.9",
          apk =
              AppReleaseAsset(
                  name = "ValerochkaGym-v9.9.9.apk",
                  downloadUrl =
                      "https://github.com/Valerochka1337/ValerochkaGym/releases/download/" +
                          "v9.9.9/ValerochkaGym-v9.9.9.apk",
                  sizeBytes = sizeBytes,
                  sha256 = "a".repeat(64),
              ),
      )
}
