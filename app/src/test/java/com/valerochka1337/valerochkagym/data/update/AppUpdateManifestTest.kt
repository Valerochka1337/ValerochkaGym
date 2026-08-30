package com.valerochka1337.valerochkagym.data.update

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppUpdateManifestTest {

    @Test
    fun `manifest allows update installs and exposes only the scoped provider`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS).toLong(),
            ),
        )

        assertTrue(packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.REQUEST_INSTALL_PACKAGES))
        val updateProvider = packageInfo.providers.orEmpty().single {
            it.authority == "${context.packageName}.updates"
        }
        assertTrue(!updateProvider.exported)
        assertTrue(updateProvider.grantUriPermissions)
    }

    @Test
    fun `installer shares only the cached apk through a content uri`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = java.io.File(context.cacheDir, "app_updates").apply { mkdirs() }
        val file = java.io.File(directory, "ValerochkaGym-v9.9.9.apk").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }

        val intent = AndroidAppUpdateInstaller(context).installIntent(file)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data?.scheme)
        assertEquals("${context.packageName}.updates", intent.data?.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        file.delete()
    }
}
