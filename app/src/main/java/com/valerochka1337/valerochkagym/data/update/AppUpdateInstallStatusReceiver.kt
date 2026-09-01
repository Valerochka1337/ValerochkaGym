package com.valerochka1337.valerochkagym.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Получает статусы PackageInstaller.Session.commit(), включая системное подтверждение. */
@AndroidEntryPoint
class AppUpdateInstallStatusReceiver : BroadcastReceiver() {

    @Inject
    lateinit var eventBus: AppUpdateInstallEventBus

    override fun onReceive(context: Context, intent: Intent) {
        eventBus.publish(intent.toAppUpdateInstallEvent() ?: return)
    }

    companion object {
        const val ACTION_INSTALL_STATUS =
            "com.valerochka1337.valerochkagym.action.APP_UPDATE_INSTALL_STATUS"
    }
}

internal fun Intent.toAppUpdateInstallEvent(): AppUpdateInstallEvent? {
    if (action != AppUpdateInstallStatusReceiver.ACTION_INSTALL_STATUS) return null

    val status = getIntExtra(
        PackageInstaller.EXTRA_STATUS,
        PackageInstaller.STATUS_FAILURE,
    )
    val systemIntent = getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)

    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
        return systemIntent?.let { AppUpdateInstallEvent.UserActionRequired(it) }
            ?: AppUpdateInstallEvent.Failed(
                "Система не смогла открыть подтверждение установки",
            )
    }

    // Android 16 QPR и новее может приложить системный экран с объяснением блокировки,
    // в частности при developer verification. Он понятнее любых внутренних status-кодов.
    if (systemIntent != null && status != PackageInstaller.STATUS_SUCCESS) {
        return AppUpdateInstallEvent.UserActionRequired(systemIntent)
    }

    return when (status) {
        PackageInstaller.STATUS_SUCCESS -> AppUpdateInstallEvent.Succeeded
        PackageInstaller.STATUS_FAILURE_ABORTED -> {
            if (hasExtra(DEVELOPER_VERIFICATION_FAILURE_REASON)) {
                AppUpdateInstallEvent.Failed(
                    "Защита устройства не разрешила установку обновления",
                )
            } else {
                AppUpdateInstallEvent.Cancelled
            }
        }
        PackageInstaller.STATUS_FAILURE_BLOCKED -> AppUpdateInstallEvent.Failed(
            "Защита устройства заблокировала установку обновления",
        )
        PackageInstaller.STATUS_FAILURE_CONFLICT -> AppUpdateInstallEvent.Failed(
            "Установленная версия приложения несовместима с этим обновлением",
        )
        PackageInstaller.STATUS_FAILURE_INVALID -> AppUpdateInstallEvent.Failed(
            "Система отклонила файл обновления. Скачайте его ещё раз",
        )
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> AppUpdateInstallEvent.Failed(
            "Эта версия приложения не поддерживается устройством",
        )
        PackageInstaller.STATUS_FAILURE_STORAGE -> AppUpdateInstallEvent.Failed(
            "Для установки обновления недостаточно свободного места",
        )
        PackageInstaller.STATUS_FAILURE_TIMEOUT -> AppUpdateInstallEvent.Failed(
            "Установка не завершилась вовремя. Попробуйте ещё раз",
        )
        else -> AppUpdateInstallEvent.Failed(
            "Не удалось установить обновление. Попробуйте ещё раз",
        )
    }
}

// Extension API 36.1: строка безопасна и на 36.0, где такого extra ещё нет.
private const val DEVELOPER_VERIFICATION_FAILURE_REASON =
    "android.content.pm.extra.DEVELOPER_VERIFICATION_FAILURE_REASON"
