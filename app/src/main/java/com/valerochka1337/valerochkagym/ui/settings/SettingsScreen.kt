package com.valerochka1337.valerochkagym.ui.settings

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalFocusManager
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.ai.AiModel
import com.valerochka1337.valerochkagym.data.settings.GymSettings
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.theme.AccentColor
import com.valerochka1337.valerochkagym.ui.theme.PaletteMode
import com.valerochka1337.valerochkagym.ui.theme.ThemeMode
import com.valerochka1337.valerochkagym.ui.update.AppUpdateRetry
import com.valerochka1337.valerochkagym.ui.update.AppUpdateStatus
import com.valerochka1337.valerochkagym.ui.update.AppUpdateUiState
import com.valerochka1337.valerochkagym.ui.update.formatUpdateBytes

private enum class SettingsCategory(
    val label: String,
    val supportingText: String,
) {
    WORKOUT("Тренировка", "Отдых, пульс, звук и уведомления"),
    CONNECTIONS("Подключения", "Google, Sheets и распознавание InBody"),
    APPEARANCE("Вид и отклик", "Тема, палитра и виброотклик"),
    DATA_APP("Данные и приложение", "Экспорт, обновления, версия и очистка"),
}

/** Шаг степпера отдыха по умолчанию (секунды) — совпадает с шагом внутри [SettingsViewModel]. */
private const val REST_STEP_SECONDS = 15
private const val HEART_RATE_REST_THRESHOLD_STEP_BPM = 5
private const val HEART_RATE_REST_HOLD_STEP_SECONDS = 5

/**
 * Экран «Настройки»: аккаунт Google, целевая таблица Google Sheets и параметры таймера отдыха.
 * Вход и запрос доступа требуют Activity (берём из [LocalActivity]); согласие на OAuth-доступ
 * запускается через launcher, а результат возвращается во ViewModel для повторной авторизации.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenGyms: () -> Unit,
    appUpdateState: AppUpdateUiState,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onRetryUpdate: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedCategory by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }

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
            Column(modifier = Modifier.fillMaxSize()) {
                SettingsHeader(
                    title = selectedCategory?.label ?: "Настройки",
                    onBack = {
                        if (selectedCategory == null) onBack() else selectedCategory = null
                    },
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val settings = state.settings
                    if (settings != null) {
                        when (selectedCategory) {
                            null -> {
                                SettingsCategoryList(
                                    onSelect = { selectedCategory = it },
                                )
                                GymsSettingsCard(onOpen = onOpenGyms)
                            }

                            SettingsCategory.WORKOUT -> RestTimerCard(
                                settings = settings,
                                onChangeRest = viewModel::changeDefaultRest,
                                onToggleAutostart = viewModel::toggleRestAutostart,
                                onToggleHeartRateRest = viewModel::toggleHeartRateRest,
                                onChangeHeartRateRestThreshold = viewModel::changeHeartRateRestThreshold,
                                onChangeHeartRateRestHoldSeconds = viewModel::changeHeartRateRestHoldSeconds,
                                onToggleSound = viewModel::toggleSound,
                                onToggleVibration = viewModel::toggleVibration,
                            )

                            SettingsCategory.CONNECTIONS -> {
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
                                AiSettingsCard(
                                    baseUrl = settings.aiBaseUrl,
                                    baseUrlError = state.aiBaseUrlError,
                                    keyConfigured = state.aiApiKeyConfigured,
                                    keyPreview = state.aiApiKeyPreview,
                                    selectedModelId = settings.aiModelId,
                                    models = state.aiModels,
                                    modelsLoading = state.aiModelsLoading,
                                    modelsLoadError = state.aiModelsLoadError,
                                    onSaveBaseUrl = viewModel::setAiBaseUrl,
                                    onSaveKey = viewModel::setAiApiKey,
                                    onClear = viewModel::clearAiApiKey,
                                    onSelectModel = viewModel::setAiModel,
                                    onRefreshModels = viewModel::refreshAiModels,
                                )
                            }

                            SettingsCategory.APPEARANCE -> AppearanceCard(
                                settings = settings,
                                onThemeModeChange = viewModel::setThemeMode,
                                onPaletteModeChange = viewModel::setPaletteMode,
                                onToggleHaptics = viewModel::toggleHaptics,
                            )

                            SettingsCategory.DATA_APP -> {
                                AppUpdateCard(
                                    state = appUpdateState,
                                    onCheck = onCheckUpdate,
                                    onDownload = onDownloadUpdate,
                                    onInstall = onInstallUpdate,
                                    onRetry = onRetryUpdate,
                                )
                                DataCard(
                                    onExport = viewModel::exportDatabase,
                                    onClear = viewModel::clearAllData,
                                )
                            }
                        }
                    }
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
private fun GymsSettingsCard(onOpen: () -> Unit) {
    SettingsNavigationCard(
        label = "Тренажёрные залы",
        supportingText = "Упражнения, доступные в каждом зале",
        icon = Icons.Rounded.FitnessCenter,
        onClick = onOpen,
    )
}

@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Назад",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun SettingsCategoryList(onSelect: (SettingsCategory) -> Unit) {
    SettingsCategory.entries.forEach { category ->
        SettingsNavigationCard(
            label = category.label,
            supportingText = category.supportingText,
            icon = when (category) {
                SettingsCategory.WORKOUT -> Icons.Rounded.Timer
                SettingsCategory.CONNECTIONS -> Icons.Rounded.Link
                SettingsCategory.APPEARANCE -> Icons.Rounded.Palette
                SettingsCategory.DATA_APP -> Icons.Rounded.Storage
            },
            onClick = { onSelect(category) },
        )
    }
}

@Composable
private fun SettingsNavigationCard(
    label: String,
    supportingText: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val haptics = gymHaptics()
    GymCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            haptics.tap()
            onClick()
        },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
    SectionCard(title = "Google-аккаунт", icon = Icons.Rounded.AccountCircle) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = email,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
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
    SectionCard(title = "Google Sheets", icon = Icons.Rounded.TableChart) {
        var input by rememberSaveable(currentId) { mutableStateOf(currentId.orEmpty()) }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Ссылка или ID таблицы") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
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
                Icon(
                    imageVector = Icons.Rounded.CloudUpload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Выгрузить всё")
            }
        }
    }
}

/** Настройка AI-генерации: после отправки key удаляется из состояния поля и не возвращается в UI. */
@Composable
private fun AiSettingsCard(
    baseUrl: String?,
    baseUrlError: Boolean,
    keyConfigured: Boolean,
    keyPreview: String?,
    selectedModelId: String?,
    models: List<AiModel>,
    modelsLoading: Boolean,
    modelsLoadError: Boolean,
    onSaveBaseUrl: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onClear: () -> Unit,
    onSelectModel: (AiModel) -> Unit,
    onRefreshModels: () -> Unit,
) {
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    SectionCard(title = "Нейросеть", icon = Icons.Rounded.AutoAwesome) {
        var baseUrlInput by rememberSaveable(baseUrl) { mutableStateOf(baseUrl.orEmpty()) }
        var keyInput by rememberSaveable { mutableStateOf("") }
        var keyFieldFocused by remember { mutableStateOf(false) }
        val showSavedKeyPreview = keyConfigured && keyInput.isEmpty() && !keyFieldFocused
        val saveKey = {
            val key = keyInput
            if (key.isNotBlank()) {
                keyInput = ""
                focusManager.clearFocus()
                onSaveKey(key)
            }
        }
        OutlinedTextField(
            value = baseUrlInput,
            onValueChange = { baseUrlInput = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Base URL") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                IconButton(
                    onClick = { onSaveBaseUrl(baseUrlInput) },
                    enabled = baseUrlInput.trim().isNotEmpty() && baseUrlInput.trim() != baseUrl,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Сохранить Base URL",
                    )
                }
            },
            isError = baseUrlError,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSaveBaseUrl(baseUrlInput) }),
            supportingText = if (baseUrlError) {
                { Text("Некорректный HTTP(S)-адрес") }
            } else {
                null
            },
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = if (showSavedKeyPreview) keyPreview ?: FALLBACK_API_KEY_PREVIEW else keyInput,
            onValueChange = { keyInput = it },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { keyFieldFocused = it.isFocused },
            singleLine = true,
            label = { Text("API key") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                IconButton(
                    onClick = saveKey,
                    enabled = keyInput.trim().isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Сохранить API key",
                    )
                }
            },
            visualTransformation = if (showSavedKeyPreview) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation(mask = '*')
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { saveKey() }),
        )
        if (keyConfigured) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        keyInput = ""
                        onClear()
                    },
                ) {
                    Text("Удалить ключ")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val connectionConfigured = baseUrl != null && keyConfigured
        val modelStatus = when {
            !connectionConfigured -> "Сначала сохраните адрес и ключ"
            modelsLoading -> "Загрузка…"
            selectedModelId != null && models.none { it.id == selectedModelId } -> "Нет в каталоге"
            selectedModelId == null -> "Выберите из каталога"
            else -> null
        }
        OutlinedButton(
            onClick = { showModelPicker = true },
            enabled = connectionConfigured,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selectedModelId ?: "Модель не выбрана",
                    maxLines = 1,
                    softWrap = false,
                )
                modelStatus?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        if (modelsLoadError && connectionConfigured) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Не удалось загрузить модели",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRefreshModels) { Text("Повторить") }
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            selectedModelId = selectedModelId,
            models = models,
            modelsLoading = modelsLoading,
            onSelect = { model ->
                showModelPicker = false
                onSelectModel(model)
            },
            onDismiss = { showModelPicker = false },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    selectedModelId: String?,
    models: List<AiModel>,
    modelsLoading: Boolean,
    onSelect: (AiModel) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Модель") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                models.forEach { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = model.id == selectedModelId,
                                role = Role.RadioButton,
                                onClick = { onSelect(model) },
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = model.id == selectedModelId,
                            onClick = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = model.id,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (modelsLoading || models.isEmpty()) {
                    Text(
                        text = if (modelsLoading) {
                            "Загружаю модели…"
                        } else {
                            "Список моделей пуст"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Готово") }
        },
    )
}

private const val FALLBACK_API_KEY_PREVIEW = "sk-************"

@Composable
private fun RestTimerCard(
    settings: GymSettings,
    onChangeRest: (Int) -> Unit,
    onToggleAutostart: (Boolean) -> Unit,
    onToggleHeartRateRest: (Boolean) -> Unit,
    onChangeHeartRateRestThreshold: (Int) -> Unit,
    onChangeHeartRateRestHoldSeconds: (Int) -> Unit,
    onToggleSound: (Boolean) -> Unit,
    onToggleVibration: (Boolean) -> Unit,
) {
    SectionCard(title = "Таймер отдыха", icon = Icons.Rounded.Timer) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Отдых по умолчанию",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
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
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.width(116.dp),
                )
                StepperButton(symbol = "+", description = "прибавить отдых") {
                    onChangeRest(REST_STEP_SECONDS)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            label = "Автостарт после подхода",
            icon = Icons.Rounded.PlayCircle,
            checked = settings.restAutostart,
            onCheckedChange = onToggleAutostart,
        )
        ToggleRow(
            label = "Отдых по пульсу",
            icon = Icons.Rounded.Favorite,
            checked = settings.heartRateRestEnabled,
            onCheckedChange = onToggleHeartRateRest,
        )
        if (settings.heartRateRestEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Завершать при пульсе",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepperButton(symbol = "−", description = "уменьшить порог пульса") {
                        onChangeHeartRateRestThreshold(-HEART_RATE_REST_THRESHOLD_STEP_BPM)
                    }
                    Text(
                        text = "≤ ${settings.heartRateRestThresholdBpm} BPM",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.width(116.dp),
                    )
                    StepperButton(symbol = "+", description = "увеличить порог пульса") {
                        onChangeHeartRateRestThreshold(HEART_RATE_REST_THRESHOLD_STEP_BPM)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Удерживать ниже",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepperButton(symbol = "−", description = "уменьшить время удержания") {
                        onChangeHeartRateRestHoldSeconds(-HEART_RATE_REST_HOLD_STEP_SECONDS)
                    }
                    Text(
                        text = "${settings.heartRateRestHoldSeconds} с",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.width(116.dp),
                    )
                    StepperButton(symbol = "+", description = "увеличить время удержания") {
                        onChangeHeartRateRestHoldSeconds(HEART_RATE_REST_HOLD_STEP_SECONDS)
                    }
                }
            }
        }
        // Подписи уточняют, что звук и вибрация — про уведомление окончания отдыха,
        // а не про весь интерфейс (общий виброотклик живёт в карточке «Интерфейс»).
        ToggleRow(
            label = "Звук по окончании",
            icon = Icons.AutoMirrored.Rounded.VolumeUp,
            checked = settings.soundEnabled,
            onCheckedChange = onToggleSound,
        )
        ToggleRow(
            label = "Вибрация уведомления",
            icon = Icons.Rounded.Vibration,
            checked = settings.vibrationEnabled,
            onCheckedChange = onToggleVibration,
        )
    }
}

@Composable
private fun AppearanceCard(
    settings: GymSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onPaletteModeChange: (PaletteMode) -> Unit,
    onToggleHaptics: (Boolean) -> Unit,
) {
    SectionCard(title = "Вид и отклик", icon = Icons.Rounded.Palette) {
        Text(
            text = "Тема",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.themeMode == mode,
                    onClick = { onThemeModeChange(mode) },
                    label = { Text(mode.label) },
                    leadingIcon = if (settings.themeMode == mode) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Палитра",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaletteMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.paletteMode == mode,
                    onClick = { onPaletteModeChange(mode) },
                    label = { Text(mode.label) },
                    leadingIcon = if (settings.paletteMode == mode) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
        Text(
            text = if (settings.paletteMode == PaletteMode.SYSTEM) {
                "Системная палитра следует цветам обоев Material You."
            } else {
                "Фирменная палитра работает в светлом и тёмном режиме."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        ToggleRow(
            label = "Виброотклик",
            icon = Icons.Rounded.Vibration,
            checked = settings.hapticsEnabled,
            onCheckedChange = onToggleHaptics,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Лёгкая отдача на нажатия: выполнение подхода, шаги веса, выбор вкладок.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DataCard(
    onExport: (android.net.Uri) -> Unit,
    onClear: () -> Unit,
) {
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    val haptics = gymHaptics()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(onExport) }

    SectionCard(title = "Данные", icon = Icons.Rounded.Storage) {
        Text(
            text = "Экспорт — копия локальной базы (SQLite): история, программы и упражнения.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = {
                val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                exportLauncher.launch(DatabaseExporter.suggestedFileName(today))
            }) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Экспорт базы")
            }
            TextButton(onClick = { showClearDialog = true }) {
                Text("Очистить данные", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Очистить данные?") },
            text = {
                Text(
                    "История тренировок, программы и свои упражнения будут удалены без " +
                        "возможности восстановления. Встроенный каталог упражнений и настройки останутся.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.reject()
                    showClearDialog = false
                    onClear()
                }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Отмена")
                }
            },
        )
    }
}

@Composable
private fun AppUpdateCard(
    state: AppUpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
) {
    val haptics = gymHaptics()
    val status = state.status

    SectionCard(title = "Приложение", icon = Icons.Rounded.SystemUpdate) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ValerochkaGym",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "v${state.installedVersionName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
        Spacer(Modifier.height(8.dp))

        val statusText = when (status) {
            AppUpdateStatus.Idle -> "Проверка обновлений доступна вручную"
            AppUpdateStatus.Checking -> "Проверяем наличие обновлений…"
            AppUpdateStatus.UpToDate -> "Установлена последняя версия"
            is AppUpdateStatus.Available ->
                "Доступна v${status.release.versionName} · ${formatUpdateBytes(status.release.apk.sizeBytes)}"
            is AppUpdateStatus.Downloading -> {
                val percent = if (status.totalBytes > 0L) {
                    (status.downloadedBytes * 100 / status.totalBytes).coerceIn(0, 100)
                } else {
                    0
                }
                "Скачиваем v${status.release.versionName} · $percent%"
            }
            is AppUpdateStatus.ReadyToInstall ->
                "v${status.release.versionName} скачана и проверена"
            is AppUpdateStatus.Failed -> status.message
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (status is AppUpdateStatus.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        if (status is AppUpdateStatus.Downloading) {
            Spacer(Modifier.height(12.dp))
            if (status.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = {
                        (status.downloadedBytes.toFloat() / status.totalBytes).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(12.dp))
        when (status) {
            AppUpdateStatus.Idle,
            AppUpdateStatus.UpToDate -> OutlinedButton(onClick = {
                haptics.tap()
                onCheck()
            }) {
                Text("Проверить обновление")
            }
            AppUpdateStatus.Checking -> OutlinedButton(onClick = {}, enabled = false) {
                Text("Проверяем…")
            }
            is AppUpdateStatus.Available -> PillButton(
                text = "Обновить до v${status.release.versionName}",
                onClick = {
                    haptics.tap()
                    onDownload()
                },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Rounded.Download,
            )
            is AppUpdateStatus.Downloading -> Unit
            is AppUpdateStatus.ReadyToInstall -> PillButton(
                text = "Установить v${status.release.versionName}",
                onClick = {
                    haptics.tap()
                    onInstall()
                },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Rounded.SystemUpdate,
            )
            is AppUpdateStatus.Failed -> {
                val label = when (status.retry) {
                    AppUpdateRetry.CHECK -> "Проверить ещё раз"
                    AppUpdateRetry.DOWNLOAD -> "Повторить скачивание"
                    AppUpdateRetry.INSTALL -> "Повторить установку"
                }
                if (status.retry == AppUpdateRetry.CHECK) {
                    OutlinedButton(onClick = {
                        haptics.tap()
                        onRetry()
                    }) {
                        Text(label)
                    }
                } else {
                    PillButton(
                        text = label,
                        onClick = {
                            haptics.tap()
                            onRetry()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = if (status.retry == AppUpdateRetry.DOWNLOAD) {
                            Icons.Rounded.Download
                        } else {
                            Icons.Rounded.SystemUpdate
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StepperButton(
    symbol: String,
    description: String,
    onClick: () -> Unit,
) {
    val haptics = gymHaptics()
    IconButton(
        onClick = {
            haptics.step()
            onClick()
        },
        modifier = Modifier
            .size(48.dp)
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
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    GymCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
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
