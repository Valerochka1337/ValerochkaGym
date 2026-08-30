package com.valerochka1337.valerochkagym.data.update

import android.content.Intent
import java.io.File

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

/** Проверка APK и создание системных Intent-ов вынесены за интерфейс для unit-тестов ViewModel. */
interface AppUpdateInstaller {
    fun verify(file: File, release: AppRelease)
    fun canRequestPackageInstalls(): Boolean
    fun unknownSourcesSettingsIntent(): Intent
    fun installIntent(file: File): Intent
}

