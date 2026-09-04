package com.valerochka1337.valerochkagym.data.update

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.valerochka1337.valerochkagym.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Receives Android's private package-replacement broadcast and never launches UI itself. */
@AndroidEntryPoint
class AppUpdateReplacementReceiver : BroadcastReceiver() {

  @Inject lateinit var coordinator: PostUpdateRelaunchCoordinator

  @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

  @SuppressLint("UnsafeProtectedBroadcastReceiver")
  override fun onReceive(context: Context, intent: Intent) {
    if (!intent.isMyPackageReplacement()) return

    val pendingResult = goAsync()
    applicationScope.launch {
      try {
        val versionCode =
            context.packageManager
                .getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0L),
                )
                .longVersionCode
        coordinator.recordReplacement(versionCode)
      } finally {
        pendingResult.finish()
      }
    }
  }
}

internal fun Intent.isMyPackageReplacement(): Boolean = action == Intent.ACTION_MY_PACKAGE_REPLACED
