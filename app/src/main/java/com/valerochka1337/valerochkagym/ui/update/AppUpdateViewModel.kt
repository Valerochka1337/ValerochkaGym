package com.valerochka1337.valerochkagym.ui.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.BuildConfig
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.data.update.AppRelease
import com.valerochka1337.valerochkagym.data.update.AppUpdateException
import com.valerochka1337.valerochkagym.data.update.AppUpdateInstaller
import com.valerochka1337.valerochkagym.data.update.AppUpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed interface AppUpdateStatus {
    data object Idle : AppUpdateStatus
    data object Checking : AppUpdateStatus
    data object UpToDate : AppUpdateStatus
    data class Available(val release: AppRelease) : AppUpdateStatus
    data class Downloading(
        val release: AppRelease,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : AppUpdateStatus
    data class ReadyToInstall(val release: AppRelease) : AppUpdateStatus
    data class Failed(
        val message: String,
        val release: AppRelease?,
        val retry: AppUpdateRetry,
    ) : AppUpdateStatus
}

enum class AppUpdateRetry { CHECK, DOWNLOAD, INSTALL }

data class AppUpdateUiState(
    val installedVersionName: String = BuildConfig.VERSION_NAME,
    val status: AppUpdateStatus = AppUpdateStatus.Idle,
    val prompt: AppRelease? = null,
    val showDownloadDialog: Boolean = false,
    val errorDialogMessage: String? = null,
)

sealed interface AppUpdateExternalAction {
    data class RequestUnknownSourcesPermission(val intent: Intent) : AppUpdateExternalAction
    data class OpenInstaller(val intent: Intent) : AppUpdateExternalAction
}

/** Один activity-scoped процесс обновления, общий для стартового диалога и экрана настроек. */
@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val repository: AppUpdateRepository,
    private val installer: AppUpdateInstaller,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppUpdateUiState())
    val uiState = _uiState.asStateFlow()

    private val _externalActions = Channel<AppUpdateExternalAction>(Channel.BUFFERED)
    val externalActions: Flow<AppUpdateExternalAction> = _externalActions.receiveAsFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var downloadedFile: File? = null
    private var dismissedPromptTag: String? = null

    init {
        // Debug APK подписан другим ключом и не может обновиться release APK. Ручная проверка в
        // настройках остаётся доступной, а автоматический popup включён только в release-сборке.
        if (!BuildConfig.DEBUG) checkForUpdate()
    }

    fun checkForUpdate() {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return
        if (_uiState.value.status is AppUpdateStatus.ReadyToInstall) return
        checkJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    status = AppUpdateStatus.Checking,
                    prompt = null,
                    errorDialogMessage = null,
                )
            }
            try {
                val release = repository.findUpdate(BuildConfig.VERSION_NAME)
                if (release == null) {
                    downloadedFile = null
                    _uiState.update { it.copy(status = AppUpdateStatus.UpToDate) }
                } else {
                    val ignoredTag = settingsRepository.settings.first().ignoredUpdateTag
                    val shouldPrompt = release.tagName != ignoredTag &&
                        release.tagName != dismissedPromptTag
                    _uiState.update {
                        it.copy(
                            status = AppUpdateStatus.Available(release),
                            prompt = release.takeIf { shouldPrompt },
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppUpdateException) {
                _uiState.update {
                    it.copy(
                        status = AppUpdateStatus.Failed(
                            message = e.userMessage,
                            release = null,
                            retry = AppUpdateRetry.CHECK,
                        ),
                    )
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        status = AppUpdateStatus.Failed(
                            message = "Не удалось проверить обновления",
                            release = null,
                            retry = AppUpdateRetry.CHECK,
                        ),
                    )
                }
            }
        }
    }

    /** «Позже»: скрывает предложение только до следующего создания ViewModel/процесса. */
    fun dismissPromptOnce() {
        val release = _uiState.value.prompt ?: return
        dismissedPromptTag = release.tagName
        _uiState.update { it.copy(prompt = null) }
    }

    /** Не напоминает об этом теге, но более новый GitHub Release снова появится автоматически. */
    fun ignorePromptVersion() {
        val release = _uiState.value.prompt ?: return
        dismissedPromptTag = release.tagName
        _uiState.update { it.copy(prompt = null) }
        viewModelScope.launch { settingsRepository.setIgnoredUpdateTag(release.tagName) }
    }

    fun downloadAvailableUpdate() {
        if (downloadJob?.isActive == true) return
        val release = when (val status = _uiState.value.status) {
            is AppUpdateStatus.Available -> status.release
            is AppUpdateStatus.Failed -> status.release.takeIf { status.retry == AppUpdateRetry.DOWNLOAD }
            else -> null
        } ?: _uiState.value.prompt ?: return

        downloadedFile = null
        _uiState.update {
            it.copy(
                status = AppUpdateStatus.Downloading(release, 0L, release.apk.sizeBytes),
                prompt = null,
                showDownloadDialog = true,
                errorDialogMessage = null,
            )
        }
        downloadJob = viewModelScope.launch {
            try {
                val file = repository.downloadAndVerify(release) { downloaded, total ->
                    _uiState.update { current ->
                        if ((current.status as? AppUpdateStatus.Downloading)?.release?.tagName !=
                            release.tagName
                        ) {
                            current
                        } else {
                            current.copy(
                                status = AppUpdateStatus.Downloading(
                                    release = release,
                                    downloadedBytes = downloaded,
                                    totalBytes = total,
                                ),
                            )
                        }
                    }
                }
                downloadedFile = file
                _uiState.update {
                    it.copy(
                        status = AppUpdateStatus.ReadyToInstall(release),
                        showDownloadDialog = false,
                    )
                }
                requestInstallation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppUpdateException) {
                showDownloadFailure(release, e.userMessage)
            } catch (_: Exception) {
                showDownloadFailure(release, "Не удалось скачать обновление")
            }
        }
    }

    fun requestInstallation() {
        val file = downloadedFile ?: return
        try {
            val action = if (installer.canRequestPackageInstalls()) {
                AppUpdateExternalAction.OpenInstaller(installer.installIntent(file))
            } else {
                AppUpdateExternalAction.RequestUnknownSourcesPermission(
                    installer.unknownSourcesSettingsIntent(),
                )
            }
            _externalActions.trySend(action)
        } catch (_: Exception) {
            externalActionFailed()
        }
    }

    fun unknownSourcesPermissionReturned() {
        try {
            if (installer.canRequestPackageInstalls()) requestInstallation()
        } catch (_: Exception) {
            externalActionFailed()
        }
    }

    fun externalActionFailed() {
        val release = when (val status = _uiState.value.status) {
            is AppUpdateStatus.ReadyToInstall -> status.release
            is AppUpdateStatus.Failed -> status.release
            else -> null
        }
        _uiState.update {
            it.copy(
                status = AppUpdateStatus.Failed(
                    message = "Не удалось открыть системный установщик",
                    release = release,
                    retry = AppUpdateRetry.INSTALL,
                ),
                errorDialogMessage = "Не удалось открыть системный установщик",
            )
        }
    }

    fun retryFailedAction() {
        when ((uiState.value.status as? AppUpdateStatus.Failed)?.retry) {
            AppUpdateRetry.CHECK -> checkForUpdate()
            AppUpdateRetry.DOWNLOAD -> downloadAvailableUpdate()
            AppUpdateRetry.INSTALL -> requestInstallation()
            null -> Unit
        }
        _uiState.update { it.copy(errorDialogMessage = null) }
    }

    fun hideDownloadDialog() {
        _uiState.update { it.copy(showDownloadDialog = false) }
    }

    fun dismissErrorDialog() {
        _uiState.update { it.copy(errorDialogMessage = null) }
    }

    private fun showDownloadFailure(release: AppRelease, message: String) {
        _uiState.update {
            it.copy(
                status = AppUpdateStatus.Failed(
                    message = message,
                    release = release,
                    retry = AppUpdateRetry.DOWNLOAD,
                ),
                showDownloadDialog = false,
                errorDialogMessage = message,
            )
        }
    }
}
