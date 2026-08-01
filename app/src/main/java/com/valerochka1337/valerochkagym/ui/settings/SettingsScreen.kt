package com.valerochka1337.valerochkagym.ui.settings

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.settings.GymSettings
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton

/** Шаг степпера отдыха по умолчанию (секунды) — совпадает с шагом внутри [SettingsViewModel]. */
private const val REST_STEP_SECONDS = 15

/**
 * Экран «Настройки»: аккаунт Google, целевая таблица Google Sheets и параметры таймера отдыха.
 * Вход и запрос доступа требуют Activity (берём из [LocalActivity]); согласие на OAuth-доступ
 * запускается через launcher, а результат возвращается во ViewModel для повторной авторизации.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        // Повторяем авторизацию только если пользователь дал согласие; отмена — без повтора,
        // иначе получился бы бесконечный цикл запросов согласия.
        if (result.resultCode == Activity.RESULT_OK) {
            activity?.let(viewModel::consentResolved)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.consentRequests.collect { intentSender ->
            consentLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        }
    }

    GlowBackground(modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Настройки",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                val settings = state.settings
                if (settings != null) {
                    GoogleAccountCard(
                        email = settings.googleEmail,
                        authBusy = state.authBusy,
                        authError = state.authError,
                        onSignIn = { activity?.let(viewModel::signIn) },
                        onSignOut = viewModel::signOut,
                    )
                    SpreadsheetCard(
                        currentId = settings.spreadsheetId,
                        error = state.spreadsheetError,
                        onSave = viewModel::setSpreadsheetInput,
                        onExportAll = viewModel::exportAll,
                    )
                    RestTimerCard(
                        settings = settings,
                        onChangeRest = viewModel::changeDefaultRest,
                        onToggleSound = viewModel::toggleSound,
                        onToggleVibration = viewModel::toggleVibration,
                    )
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun GoogleAccountCard(
    email: String?,
    authBusy: Boolean,
    authError: String?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    SectionCard(title = "Google-аккаунт") {
        if (email == null) {
            Text(
                text = "Войдите, чтобы выгружать тренировки в Google Sheets и Calendar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            PillButton(
                text = "Войти через Google",
                onClick = onSignIn,
                enabled = !authBusy,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = email,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onSignOut, enabled = !authBusy) {
                Text("Выйти")
            }
        }
        if (authError != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = authError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SpreadsheetCard(
    currentId: String?,
    error: Boolean,
    onSave: (String) -> Unit,
    onExportAll: () -> Unit,
) {
    SectionCard(title = "Google Sheets") {
        var input by rememberSaveable(currentId) { mutableStateOf(currentId.orEmpty()) }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Ссылка или ID таблицы") },
            isError = error,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(input) }),
            supportingText = {
                Text(
                    if (error) {
                        "Не похоже на ссылку или ID"
                    } else {
                        "Вставьте ссылку на таблицу или её ID из адресной строки"
                    },
                )
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { onSave(input) }) {
                Text("Сохранить")
            }
            OutlinedButton(onClick = onExportAll, enabled = currentId != null) {
                Text("Выгрузить всё")
            }
        }
    }
}

@Composable
private fun RestTimerCard(
    settings: GymSettings,
    onChangeRest: (Int) -> Unit,
    onToggleSound: (Boolean) -> Unit,
    onToggleVibration: (Boolean) -> Unit,
) {
    SectionCard(title = "Таймер отдыха") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Отдых по умолчанию",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepperButton(symbol = "−", description = "убавить отдых") {
                    onChangeRest(-REST_STEP_SECONDS)
                }
                Text(
                    text = formatRest(settings.defaultRestSeconds),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(104.dp),
                )
                StepperButton(symbol = "+", description = "прибавить отдых") {
                    onChangeRest(REST_STEP_SECONDS)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            label = "Звук",
            checked = settings.soundEnabled,
            onCheckedChange = onToggleSound,
        )
        ToggleRow(
            label = "Вибрация",
            checked = settings.vibrationEnabled,
            onCheckedChange = onToggleVibration,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StepperButton(
    symbol: String,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .semantics { contentDescription = description },
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    GymCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** Отдых в формате «2 мин 00 сек». */
private fun formatRest(seconds: Int): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%d мин %02d сек".format(minutes, secs)
}
