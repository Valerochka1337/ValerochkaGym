package com.valerochka1337.valerochkagym.data.update

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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
    @Suppress("DEPRECATION") // Robolectric 4.16 exposes only deprecated resolve-info test hooks.
    fun `installer grants the cached apk only to the resolved system installer`() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val directory = java.io.File(baseContext.cacheDir, "app_updates").apply { mkdirs() }
        val file = java.io.File(directory, "ValerochkaGym-v9.9.9.apk").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val uri = FileProvider.getUriForFile(
            baseContext,
            "${baseContext.packageName}.updates",
            file,
        )
        val installerIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
        }
        shadowOf(baseContext.packageManager).setResolveInfosForIntent(
            installerIntent,
            listOf(
                ResolveInfo().apply {
                    activityInfo = ActivityInfo().apply {
                        packageName = TEST_INSTALLER_PACKAGE
                        name = "$TEST_INSTALLER_PACKAGE.InstallStart"
                    }
                },
            ),
        )
        val context = RecordingGrantContext(baseContext)

        val intent = AndroidAppUpdateInstaller(context).installIntent(file)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(TEST_INSTALLER_PACKAGE, intent.`package`)
        assertEquals("content", intent.data?.scheme)
        assertEquals("${baseContext.packageName}.updates", intent.data?.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(TEST_INSTALLER_PACKAGE, context.grantedPackage)
        assertEquals(intent.data, context.grantedUri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, context.grantedFlags)
        file.delete()
    }

    private class RecordingGrantContext(base: Context) : ContextWrapper(base) {
        var grantedPackage: String? = null
            private set
        var grantedUri: Uri? = null
            private set
        var grantedFlags: Int = 0
            private set

        override fun grantUriPermission(toPackage: String?, uri: Uri?, modeFlags: Int) {
            grantedPackage = toPackage
            grantedUri = uri
            grantedFlags = modeFlags
        }
    }

    private companion object {
        const val TEST_INSTALLER_PACKAGE = "com.android.testinstaller"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
