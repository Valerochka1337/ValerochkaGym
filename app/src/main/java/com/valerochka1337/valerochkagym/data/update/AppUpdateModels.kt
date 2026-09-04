package com.valerochka1337.valerochkagym.data.update

import android.content.Intent
import java.io.File
import kotlinx.coroutines.flow.Flow

data class AppReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class AppRelease(
    val tagName: String,
    val versionName: String,
    val title: String,
    val notes: String?,
    val pageUrl: String,
    val apk: AppReleaseAsset,
)

/** Ошибка update-сценария с текстом, который безопасно показать прямо в UI. */
class AppUpdateException(
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause)

interface AppUpdateRepository {
  /** null означает, что опубликованной версии новее [installedVersionName] нет. */
  suspend fun findUpdate(installedVersionName: String): AppRelease?

  /** Скачивает APK в private cache и возвращает файл только после всех проверок. */
  suspend fun downloadAndVerify(
      release: AppRelease,
      onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
  ): File
}

/** Результат, который системный Package Installer возвращает после commit install-сессии. */
sealed interface AppUpdateInstallEvent {
  data class UserActionRequired(val intent: Intent) : AppUpdateInstallEvent

  data class Failed(val message: String) : AppUpdateInstallEvent

  data object Cancelled : AppUpdateInstallEvent

  data object Succeeded : AppUpdateInstallEvent
}

/** Проверка APK и взаимодействие с Package Installer вынесены за интерфейс для unit-тестов. */
interface AppUpdateInstaller {
  val installEvents: Flow<AppUpdateInstallEvent>

  fun verify(file: File, release: AppRelease)

  fun canRequestPackageInstalls(): Boolean

  fun unknownSourcesSettingsIntent(): Intent

  suspend fun startInstallation(file: File, release: AppRelease)
}
