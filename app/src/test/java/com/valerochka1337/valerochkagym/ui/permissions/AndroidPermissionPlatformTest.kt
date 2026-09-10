package com.valerochka1337.valerochkagym.ui.permissions

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config
class AndroidPermissionPlatformTest {

  @Test
  fun `platform exposes live state without an activity rationale`() {
    val state =
        AndroidPermissionPlatform(
                ApplicationProvider.getApplicationContext(),
            )
            .state("android.permission.POST_NOTIFICATIONS")

    assertEquals("android.permission.POST_NOTIFICATIONS", state.permission)
    assertEquals(false, state.shouldShowRationale)
  }

  @Test
  fun `settings intent falls back when no activity can own the result`() {
    val platform = AndroidPermissionPlatform(ApplicationProvider.getApplicationContext())
    assertNull(platform.appSettingsIntentOrNull())
  }

  @Test
  fun `settings intent uses application details action and package uri when resolvable`() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val resolver =
        ResolveInfo().apply {
          activityInfo =
              ActivityInfo().apply {
                packageName = activity.packageName
                name = "SettingsActivity"
                applicationInfo = ApplicationInfo().apply { packageName = activity.packageName }
              }
        }
    shadowOf(activity.packageManager)
        .addResolveInfoForIntent(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${activity.packageName}")),
            resolver,
        )
    val intent = AndroidPermissionPlatform(activity).appSettingsIntentOrNull()

    assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent?.action)
    assertEquals("package:${activity.packageName}", intent?.dataString)
  }
}
