package com.valerochka1337.valerochkagym.ui

import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.data.update.AppRelease
import com.valerochka1337.valerochkagym.data.update.AppReleaseAsset
import com.valerochka1337.valerochkagym.data.update.AppUpdateException
import com.valerochka1337.valerochkagym.data.update.AppUpdateInstallEvent
import com.valerochka1337.valerochkagym.data.update.AppUpdateInstaller
import com.valerochka1337.valerochkagym.data.update.AppUpdateRepository
import com.valerochka1337.valerochkagym.ui.update.AppUpdateExternalAction
import com.valerochka1337.valerochkagym.ui.update.AppUpdateRetry
import com.valerochka1337.valerochkagym.ui.update.AppUpdateStatus
import com.valerochka1337.valerochkagym.ui.update.AppUpdateViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `checking a newer release shows the startup prompt`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = viewModel(release = release("1.3.0"))

            viewModel.checkForUpdate()

            val status = viewModel.uiState.value.status as AppUpdateStatus.Available
            assertEquals("1.3.0", status.release.versionName)
            assertEquals("v1.3.0", viewModel.uiState.value.prompt?.tagName)
        }

    @Test
    fun `dismissing once keeps the same release hidden for this view model`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = viewModel(release = release("1.3.0"))
            viewModel.checkForUpdate()

            viewModel.dismissPromptOnce()
            viewModel.checkForUpdate()

            assertNull(viewModel.uiState.value.prompt)
            assertTrue(viewModel.uiState.value.status is AppUpdateStatus.Available)
        }

    @Test
    fun `ignoring a version persists its tag and a newer version prompts again`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val settings = settingsRepository()
            val repository = FakeUpdateRepository(release("1.3.0"))
            val first = AppUpdateViewModel(repository, FakeInstaller(), settings)
            first.checkForUpdate()
            first.ignorePromptVersion()

            assertEquals("v1.3.0", settings.settings.first().ignoredUpdateTag)

            repository.release = release("1.4.0")
            val second = AppUpdateViewModel(repository, FakeInstaller(), settings)
            second.checkForUpdate()

            assertEquals("v1.4.0", second.uiState.value.prompt?.tagName)
        }

    @Test
    fun `verified download requests unknown sources permission when it is disabled`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val installer = FakeInstaller(canInstall = false)
            val viewModel = viewModel(release = release("1.3.0"), installer = installer)
            viewModel.checkForUpdate()

            viewModel.downloadAvailableUpdate()

            assertTrue(viewModel.uiState.value.status is AppUpdateStatus.ReadyToInstall)
            assertTrue(
                viewModel.externalActions.first() is
                    AppUpdateExternalAction.RequestUnknownSourcesPermission,
            )
        }

    @Test
    fun `verified download starts a session and opens its system confirmation`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val installer = FakeInstaller(canInstall = true)
            val viewModel = viewModel(release = release("1.3.0"), installer = installer)
            viewModel.checkForUpdate()

            viewModel.downloadAvailableUpdate()

            assertTrue(viewModel.externalActions.first() is AppUpdateExternalAction.OpenInstaller)
            assertEquals(1, installer.startInstallationCalls)
        }

    @Test
    fun `system installation failure remains retryable with a friendly message`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val installer = FakeInstaller(canInstall = true)
            val viewModel = viewModel(release = release("1.3.0"), installer = installer)
            viewModel.checkForUpdate()
            viewModel.downloadAvailableUpdate()
            viewModel.externalActions.first()

            installer.emit(AppUpdateInstallEvent.Failed("Защита устройства остановила установку"))

            val status = viewModel.uiState.value.status as AppUpdateStatus.Failed
            assertEquals(AppUpdateRetry.INSTALL, status.retry)
            assertEquals("Защита устройства остановила установку", status.message)
            assertEquals(status.message, viewModel.uiState.value.errorDialogMessage)
        }

    @Test
    fun `download failure remains retryable and closes progress`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val repository = FakeUpdateRepository(
                release = release("1.3.0"),
                downloadError = AppUpdateException("Контрольная сумма не совпала"),
            )
            val viewModel = AppUpdateViewModel(repository, FakeInstaller(), settingsRepository())
            viewModel.checkForUpdate()

            viewModel.downloadAvailableUpdate()

            val status = viewModel.uiState.value.status as AppUpdateStatus.Failed
            assertEquals(AppUpdateRetry.DOWNLOAD, status.retry)
            assertEquals("Контрольная сумма не совпала", status.message)
            assertFalse(viewModel.uiState.value.showDownloadDialog)
        }

    private fun viewModel(
        release: AppRelease?,
        installer: FakeInstaller = FakeInstaller(),
    ): AppUpdateViewModel = AppUpdateViewModel(
        repository = FakeUpdateRepository(release),
        installer = installer,
        settingsRepository = settingsRepository(),
    )

    private fun settingsRepository(): SettingsRepository = SettingsRepository(FakeDataStore())

    private fun release(version: String): AppRelease = AppRelease(
        tagName = "v$version",
        versionName = version,
        title = "ValerochkaGym $version",
        notes = null,
        pageUrl = "https://github.com/Valerochka1337/ValerochkaGym/releases/tag/v$version",
        apk = AppReleaseAsset(
            name = "ValerochkaGym-v$version.apk",
            downloadUrl = "https://github.com/Valerochka1337/ValerochkaGym/releases/download/v$version/ValerochkaGym-v$version.apk",
            sizeBytes = 5_000_000L,
            sha256 = "a".repeat(64),
        ),
    )

    private class FakeUpdateRepository(
        var release: AppRelease?,
        private val downloadError: AppUpdateException? = null,
    ) : AppUpdateRepository {
        override suspend fun findUpdate(installedVersionName: String): AppRelease? = release

        override suspend fun downloadAndVerify(
            release: AppRelease,
            onProgress: (Long, Long) -> Unit,
        ): File {
            downloadError?.let { throw it }
            onProgress(release.apk.sizeBytes, release.apk.sizeBytes)
            return File("verified-update.apk")
        }
    }

    private class FakeInstaller(
        var canInstall: Boolean = true,
    ) : AppUpdateInstaller {
        private val eventChannel = Channel<AppUpdateInstallEvent>(Channel.BUFFERED)
        override val installEvents: Flow<AppUpdateInstallEvent> = eventChannel.receiveAsFlow()

        var startInstallationCalls = 0
            private set

        override fun verify(file: File, release: AppRelease) = Unit

        override fun canRequestPackageInstalls(): Boolean = canInstall

        override fun unknownSourcesSettingsIntent(): Intent = Intent("test.unknown.sources")

        override suspend fun startInstallation(file: File, release: AppRelease) {
            startInstallationCalls++
            eventChannel.send(
                AppUpdateInstallEvent.UserActionRequired(Intent("test.install")),
            )
        }

        fun emit(event: AppUpdateInstallEvent) {
            eventChannel.trySend(event)
        }
    }

    private class FakeDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            state.value = transform(state.value)
            return state.value
        }
    }
}
