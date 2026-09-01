package com.valerochka1337.valerochkagym.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.Process
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@Singleton
class AndroidAppUpdateInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val eventBus: AppUpdateInstallEventBus,
) : AppUpdateInstaller {

    override val installEvents: Flow<AppUpdateInstallEvent> = eventBus.events

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

    override suspend fun startInstallation(file: File, release: AppRelease) =
        withContext(Dispatchers.IO) {
            if (!file.isFile || file.length() <= 0L) {
                throw AppUpdateException("Файл обновления больше недоступен")
            }

            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
                setOriginatingUid(Process.myUid())
                setOriginatingUri(Uri.parse(release.apk.downloadUrl))
                setReferrerUri(Uri.parse(release.pageUrl))
            }

            val sessionId = try {
                packageInstaller.createSession(params)
            } catch (e: Exception) {
                throw AppUpdateException(
                    "Не удалось подготовить обновление к установке",
                    e,
                )
            }

            var committed = false
            try {
                packageInstaller.openSession(sessionId).use { session ->
                    file.inputStream().use { input ->
                        session.openWrite(BASE_APK_NAME, 0L, file.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }

                    val callbackIntent = Intent(
                        context,
                        AppUpdateInstallStatusReceiver::class.java,
                    ).apply {
                        action = AppUpdateInstallStatusReceiver.ACTION_INSTALL_STATUS
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    }
                    val callback = PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        callbackIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(callback.intentSender)
                    committed = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw AppUpdateException("Не удалось передать обновление системе", e)
            } finally {
                if (!committed) runCatching { packageInstaller.abandonSession(sessionId) }
            }
        }
}

private const val BASE_APK_NAME = "base.apk"
