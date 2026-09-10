package com.valerochka1337.valerochkagym.ui.permissions

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

interface PermissionRecoveryPlatform {
  fun state(permission: String): LivePermissionState

  fun appSettingsIntentOrNull(): Intent?
}

/** Android-only adapter; callers receive values/intents and own their ActivityResult launchers. */
class AndroidPermissionPlatform(private val context: Context) : PermissionRecoveryPlatform {

  override fun state(permission: String): LivePermissionState =
      LivePermissionState(
          permission = permission,
          granted =
              ContextCompat.checkSelfPermission(context, permission) ==
                  PackageManager.PERMISSION_GRANTED,
          shouldShowRationale =
              findActivity(context)?.shouldShowRequestPermissionRationale(permission) ?: false,
      )

  override fun appSettingsIntentOrNull(): Intent? {
    if (findActivity(context) == null) return null
    val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
    return intent.takeIf { it.resolveActivity(context.packageManager) != null }
  }

  private tailrec fun findActivity(value: Context): Activity? =
      when (value) {
        is Activity -> value
        is ContextWrapper -> findActivity(value.baseContext)
        else -> null
      }
}
