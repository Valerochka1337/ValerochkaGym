package com.valerochka1337.valerochkagym.data.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubAppUpdateRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: GitHubReleaseApi,
    private val installer: AppUpdateInstaller,
) : AppUpdateRepository {

    override suspend fun findUpdate(installedVersionName: String): AppRelease? = try {
        val response = api.latestRelease()
        when {
            response.code() == 404 -> null // Репозиторий доступен, но stable release ещё не создан.
            response.code() == 403 || response.code() == 429 -> {
                throw AppUpdateException(
                    "Сервис обновлений временно недоступен. Попробуйте позже.",
                )
            }
            !response.isSuccessful -> {
                throw AppUpdateException("Не удалось проверить обновления. Попробуйте позже.")
            }
            else -> {
                // toAppRelease закономерно возвращает null, когда установленная версия уже
                // актуальна. Elvis после всей цепочки ошибочно превращал этот случай в ошибку.
                val latestRelease = response.body()
                    ?: throw AppUpdateException(
                        "Не удалось проверить обновления. Попробуйте позже.",
                    )
                latestRelease.toAppRelease(installedVersionName)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AppUpdateException) {
        throw e
    } catch (e: IOException) {
        throw AppUpdateException("Нет сети для проверки обновлений", e)
    } catch (e: Exception) {
        throw AppUpdateException("Не удалось прочитать описание обновления", e)
    }

    override suspend fun downloadAndVerify(
        release: AppRelease,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, UPDATE_CACHE_DIRECTORY).apply { mkdirs() }
        val target = File(directory, release.apk.name)
        val temporary = File(directory, "${release.apk.name}.part")

        if (target.isFile && target.length() == release.apk.sizeBytes &&
            target.sha256() == release.apk.sha256
        ) {
            try {
                installer.verify(target, release)
                onProgress(target.length(), target.length())
                return@withContext target
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }

        target.delete()
        temporary.delete()
        directory.listFiles()
            ?.filter { it != temporary && it != target }
            ?.forEach(File::delete)

        try {
            val response = api.downloadAsset(release.apk.downloadUrl)
            if (!response.isSuccessful) {
                throw AppUpdateException("Не удалось скачать обновление. Попробуйте позже.")
            }
            val body = response.body()
                ?: throw AppUpdateException("Не удалось скачать обновление. Попробуйте позже.")
            val responseLength = body.contentLength()
            if (responseLength > MAX_UPDATE_APK_BYTES) {
                throw AppUpdateException("Файл обновления слишком большой")
            }

            val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
            var downloaded = 0L
            body.byteStream().buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > MAX_UPDATE_APK_BYTES) {
                            throw AppUpdateException("Файл обновления слишком большой")
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        onProgress(downloaded, release.apk.sizeBytes)
                    }
                }
            }

            if (downloaded != release.apk.sizeBytes) {
                throw AppUpdateException("Обновление скачано не полностью")
            }
            val actualDigest = digest.digest().toHexString()
            if (actualDigest != release.apk.sha256) {
                throw AppUpdateException("Проверка целостности скачанного обновления не пройдена")
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            installer.verify(target, release)
            target
        } catch (e: CancellationException) {
            temporary.delete()
            throw e
        } catch (e: AppUpdateException) {
            temporary.delete()
            target.delete()
            throw e
        } catch (e: IOException) {
            temporary.delete()
            target.delete()
            throw AppUpdateException(
                "Не удалось скачать обновление. Проверьте подключение и попробуйте снова.",
                e,
            )
        } catch (e: Exception) {
            temporary.delete()
            target.delete()
            throw AppUpdateException("Не удалось проверить скачанное обновление", e)
        }
    }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHexString()
}

private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

private const val UPDATE_CACHE_DIRECTORY = "app_updates"
private const val SHA256_ALGORITHM = "SHA-256"
private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
