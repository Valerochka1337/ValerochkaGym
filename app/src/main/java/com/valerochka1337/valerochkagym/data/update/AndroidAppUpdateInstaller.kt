package com.valerochka1337.valerochkagym.data.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidAppUpdateInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AppUpdateInstaller {

    override fun verify(file: File, release: AppRelease) {
        val packageManager = context.packageManager
        val archiveInfo = packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.PackageInfoFlags.of(0L),
        ) ?: throw AppUpdateException("Скачанный файл обновления повреждён или не поддерживается")
        if (archiveInfo.packageName != context.packageName) {
            throw AppUpdateException("Скачан файл для другого приложения")
        }
        if (archiveInfo.versionName != release.versionName) {
            throw AppUpdateException("Версия скачанного обновления не совпадает с ожидаемой")
        }

        val installedInfo = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
        if (archiveInfo.longVersionCode <= installedInfo.longVersionCode) {
            throw AppUpdateException("Скачанная версия не новее установленной")
        }

        val verifiedSigningInfo = try {
            PackageManager.getVerifiedSigningInfo(
                file.absolutePath,
                SigningInfo.VERSION_SIGNING_BLOCK_V2,
            )
        } catch (e: Exception) {
            throw AppUpdateException("Не удалось подтвердить подлинность обновления", e)
        }
        val installedSigningInfo = installedInfo.signingInfo
            ?: throw AppUpdateException("Не удалось прочитать подпись установленного приложения")
        if (!verifiedSigningInfo.signersMatchExactly(installedSigningInfo)) {
            throw AppUpdateException("Не удалось подтвердить подлинность обновления")
        }
    }

    override fun canRequestPackageInstalls(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    override fun unknownSourcesSettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    override fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updates",
            file,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            clipData = ClipData.newRawUri("APK обновления", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
