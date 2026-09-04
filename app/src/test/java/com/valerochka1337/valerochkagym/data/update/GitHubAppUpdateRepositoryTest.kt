package com.valerochka1337.valerochkagym.data.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class GitHubAppUpdateRepositoryTest {

  @Test
  fun `missing github release means no update`() = runTest {
    val repository = repository(FakeGitHubReleaseApi(latestCode = 404))

    assertNull(repository.findUpdate("1.2.0"))
  }

  @Test
  fun `release matching the installed version means no update`() = runTest {
    val repository = repository(FakeGitHubReleaseApi())

    assertNull(repository.findUpdate("9.9.9"))
  }

  @Test
  fun `download writes exact bytes and verifies the finished apk`() = runTest {
    val bytes = "signed apk placeholder".encodeToByteArray()
    val release = release(bytes)
    val installer = FakeInstaller()
    val repository =
        repository(
            api = FakeGitHubReleaseApi(downloadBytes = bytes),
            installer = installer,
        )

    val progress = mutableListOf<Pair<Long, Long>>()
    val file =
        repository.downloadAndVerify(release) { downloaded, total ->
          progress += downloaded to total
        }

    assertArrayEquals(bytes, file.readBytes())
    assertEquals(1, installer.verifyCalls)
    assertEquals(bytes.size.toLong() to bytes.size.toLong(), progress.last())
    file.delete()
  }

  @Test
  fun `download rejects a sha256 mismatch and removes the apk`() = runTest {
    val bytes = "tampered apk".encodeToByteArray()
    val release =
        release(bytes)
            .copy(
                apk = release(bytes).apk.copy(sha256 = "0".repeat(64)),
            )
    val installer = FakeInstaller()
    val repository =
        repository(
            api = FakeGitHubReleaseApi(downloadBytes = bytes),
            installer = installer,
        )

    val error =
        try {
          repository.downloadAndVerify(release) { _, _ -> }
          null
        } catch (e: AppUpdateException) {
          e
        }

    assertEquals(
        "Проверка целостности скачанного обновления не пройдена",
        error?.userMessage,
    )
    assertEquals(0, installer.verifyCalls)
    val directory =
        File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "app_updates")
    assertFalse(File(directory, release.apk.name).exists())
    assertFalse(File(directory, "${release.apk.name}.part").exists())
  }

  private fun repository(
      api: GitHubReleaseApi,
      installer: FakeInstaller = FakeInstaller(),
  ): GitHubAppUpdateRepository =
      GitHubAppUpdateRepository(
          context = ApplicationProvider.getApplicationContext(),
          api = api,
          installer = installer,
      )

  private fun release(bytes: ByteArray): AppRelease =
      AppRelease(
          tagName = "v9.9.9",
          versionName = "9.9.9",
          title = "Test",
          notes = null,
          pageUrl = "https://github.com/Valerochka1337/ValerochkaGym/releases/tag/v9.9.9",
          apk =
              AppReleaseAsset(
                  name = "ValerochkaGym-v9.9.9.apk",
                  downloadUrl =
                      "https://github.com/Valerochka1337/ValerochkaGym/releases/download/v9.9.9/ValerochkaGym-v9.9.9.apk",
                  sizeBytes = bytes.size.toLong(),
                  sha256 =
                      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                        "%02x".format(it.toInt() and 0xff)
                      },
              ),
      )

  private class FakeGitHubReleaseApi(
      private val latestCode: Int = 200,
      private val downloadBytes: ByteArray = byteArrayOf(),
  ) : GitHubReleaseApi {
    override suspend fun latestRelease(): Response<GitHubReleaseDto> =
        if (latestCode == 404) {
          Response.error(404, "not found".toResponseBody("application/json".toMediaType()))
        } else {
          Response.success(
              GitHubReleaseDto(
                  tagName = "v9.9.9",
                  htmlUrl = "https://github.com/Valerochka1337/ValerochkaGym/releases/tag/v9.9.9",
              ),
          )
        }

    override suspend fun downloadAsset(url: String): Response<ResponseBody> =
        Response.success(
            downloadBytes.toResponseBody("application/vnd.android.package-archive".toMediaType()),
        )
  }

  private class FakeInstaller : AppUpdateInstaller {
    override val installEvents: Flow<AppUpdateInstallEvent> = emptyFlow()

    var verifyCalls: Int = 0
      private set

    override fun verify(file: File, release: AppRelease) {
      verifyCalls++
    }

    override fun canRequestPackageInstalls(): Boolean = true

    override fun unknownSourcesSettingsIntent() = android.content.Intent()

    override suspend fun startInstallation(file: File, release: AppRelease) = Unit
  }
}
