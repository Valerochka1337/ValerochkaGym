package com.valerochka1337.valerochkagym.ui.update

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics

/** Глобальные update-диалоги и мост к системным Activity unknown sources / Package Installer. */
@Composable
fun AppUpdateHost(
    state: AppUpdateUiState,
    externalActions: kotlinx.coroutines.flow.Flow<AppUpdateExternalAction>,
    onDismissPromptOnce: () -> Unit,
    onIgnorePromptVersion: () -> Unit,
    onDownload: () -> Unit,
    onHideDownloadDialog: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onUnknownSourcesReturned: () -> Unit,
    onExternalActionFailed: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val haptics = gymHaptics()
    var pendingUnknownSourcesIntent by remember { mutableStateOf<Intent?>(null) }

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        pendingUnknownSourcesIntent = null
        onUnknownSourcesReturned()
    }

    LaunchedEffect(externalActions) {
        externalActions.collect { action ->
            when (action) {
                is AppUpdateExternalAction.RequestUnknownSourcesPermission -> {
                    pendingUnknownSourcesIntent = action.intent
                }
                is AppUpdateExternalAction.OpenInstaller -> {
                    try {
                        (activity ?: context).startActivity(action.intent)
                    } catch (_: Exception) {
                        onExternalActionFailed()
                    }
                }
            }
        }
    }

    state.prompt?.let { release ->
        AlertDialog(
            onDismissRequest = onDismissPromptOnce,
            title = { Text("Доступно обновление") },
            text = {
                Column {
                    Text("Установлена v${state.installedVersionName}, доступна v${release.versionName}.")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Размер APK — ${formatUpdateBytes(release.apk.sizeBytes)}. " +
                            "«Не напоминать» скроет только эту версию.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.tap()
                    onDownload()
                }) {
                    Text("Обновить")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        haptics.tap()
                        onIgnorePromptVersion()
                    }) {
                        Text("Не напоминать")
                    }
                    TextButton(onClick = onDismissPromptOnce) {
                        Text("Позже")
                    }
                }
            },
        )
    }

    val downloading = state.status as? AppUpdateStatus.Downloading
    if (downloading != null && state.showDownloadDialog) {
        AlertDialog(
            onDismissRequest = onHideDownloadDialog,
            title = { Text("Скачиваем обновление") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val total = downloading.totalBytes
                    if (total > 0L) {
                        val progress = (downloading.downloadedBytes.toFloat() / total)
                            .coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${(progress * 100).toInt()}% · " +
                                formatUpdateBytes(downloading.downloadedBytes),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("После проверки откроется системный установщик Android.")
                }
            },
            confirmButton = {
                TextButton(onClick = onHideDownloadDialog) { Text("Скрыть") }
            },
        )
    }

    state.errorDialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("Обновление не установлено") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onRetry) { Text("Повторить") }
            },
            dismissButton = {
                TextButton(onClick = onDismissError) { Text("Позже") }
            },
        )
    }

    pendingUnknownSourcesIntent?.let { intent ->
        AlertDialog(
            onDismissRequest = { pendingUnknownSourcesIntent = null },
            title = { Text("Разрешите установку") },
            text = {
                Text(
                    "Android блокирует APK из новых источников. Разрешите ValerochkaGym " +
                        "устанавливать приложения — затем установка продолжится автоматически.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.tap()
                    try {
                        unknownSourcesLauncher.launch(intent)
                    } catch (_: Exception) {
                        pendingUnknownSourcesIntent = null
                        onExternalActionFailed()
                    }
                }) {
                    Text("Открыть настройки")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnknownSourcesIntent = null }) {
                    Text("Позже")
                }
            },
        )
    }
}

internal fun formatUpdateBytes(bytes: Long): String {
    val mebibytes = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0)
    return if (mebibytes >= 10.0) {
        "%.0f МБ".format(mebibytes)
    } else {
        "%.1f МБ".format(mebibytes)
    }
}
