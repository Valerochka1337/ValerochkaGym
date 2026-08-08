package com.valerochka1337.valerochkagym.ui.settings

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.settings.GymSettings
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.theme.AccentColor
import com.valerochka1337.valerochkagym.ui.theme.LauncherIconBackground

/** Шаг степпера отдыха по умолчанию (секунды) — совпадает с шагом внутри [SettingsViewModel]. */
private const val REST_STEP_SECONDS = 15

/**
 * Экран «Настройки»: аккаунт Google, целевая таблица Google Sheets и параметры таймера отдыха.
 * Вход и запрос доступа требуют Activity (берём из [LocalActivity]); согласие на OAuth-доступ
 * запускается через launcher, а результат возвращается во ViewModel для повторной авторизации.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
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
            Column(modifier = Modifier.fillMaxSize()) {
                SettingsHeader(onBack = onBack)
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
                        OpenRouterCard(
                            keyConfigured = state.openRouterKeyConfigured,
                            onSave = viewModel::setOpenRouterKey,
                            onClear = viewModel::clearOpenRouterKey,
                        )
                        RestTimerCard(
                            settings = settings,
                            onChangeRest = viewModel::changeDefaultRest,
                            onToggleAutostart = viewModel::toggleRestAutostart,
                            onToggleSound = viewModel::toggleSound,
                            onToggleVibration = viewModel::toggleVibration,
                        )
                        InterfaceCard(
                            hapticsEnabled = settings.hapticsEnabled,
                            onToggleHaptics = viewModel::toggleHaptics,
                        )
                        AccentCard(
                            selected = settings.accent,
                            onSelect = viewModel::setAccent,
                        )
                        DataCard(
                            onExport = viewModel::exportDatabase,
                            onClear = viewModel::clearAllData,
                        )
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
private fun SettingsHeader(onBack: () -> Unit) {
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
            text = "Настройки",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
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

/** Настройка AI-генерации: поле всегда пустое, чтобы ключ нельзя было прочитать из интерфейса. */
@Composable
private fun OpenRouterCard(
    keyConfigured: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    SectionCard(title = "OpenRouter", icon = Icons.Rounded.AutoAwesome) {
        var input by rememberSaveable { mutableStateOf("") }
        Text(
            text = if (keyConfigured) {
                "Ключ сохранён на этом устройстве"
            } else {
                "Добавьте API key, чтобы создавать упражнения по описанию"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("OpenRouter API key") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSave(input) }),
            supportingText = {
                Text("Ключ шифруется и не переносится в резервных копиях")
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { onSave(input) },
                enabled = input.trim().isNotEmpty(),
            ) {
                Text(if (keyConfigured) "Заменить ключ" else "Сохранить ключ")
            }
            if (keyConfigured) {
                TextButton(
                    onClick = {
                        input = ""
                        onClear()
                    },
                ) {
                    Text("Удалить")
                }
            }
        }
    }
}

@Composable
private fun RestTimerCard(
    settings: GymSettings,
    onChangeRest: (Int) -> Unit,
    onToggleAutostart: (Boolean) -> Unit,
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
private fun InterfaceCard(
    hapticsEnabled: Boolean,
    onToggleHaptics: (Boolean) -> Unit,
) {
    SectionCard(title = "Интерфейс", icon = Icons.Rounded.TouchApp) {
        ToggleRow(
            label = "Виброотклик",
            icon = Icons.Rounded.Vibration,
            checked = hapticsEnabled,
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
        Spacer(Modifier.height(12.dp))
        AboutRow()
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

/** «О приложении»: имя и версия из PackageManager — без включения buildConfig. */
@Composable
private fun AboutRow() {
    val context = LocalContext.current
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "ValerochkaGym",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "v$versionName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Выбор акцента. Варианты показаны так, как их увидит лаунчер — тем же силуэтом иконки на её
 * тёмной подложке: пользователь выбирает не абстрактный цвет, а конкретную иконку приложения.
 */
@Composable
private fun AccentCard(
    selected: AccentColor,
    onSelect: (AccentColor) -> Unit,
) {
    SectionCard(title = "Акцент", icon = Icons.Rounded.Palette) {
        Text(
            text = "Цвет интерфейса и иконки. Иконка на рабочем столе сменится, когда свернёте приложение.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccentColor.entries.forEach { accent ->
                AccentSwatch(
                    accent = accent,
                    selected = accent == selected,
                    onClick = { onSelect(accent) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Уголок иконки в долях стороны — повторяет скругление адаптивной иконки в лаунчере. */
private const val ICON_CORNER_PERCENT = 26

@Composable
private fun AccentSwatch(
    accent: AccentColor,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = ICON_CORNER_PERCENT)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(LauncherIconBackground)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = accent.label },
        contentAlignment = Alignment.Center,
    ) {
        LauncherGlyph(color = accent.primary, modifier = Modifier.fillMaxSize())
    }
}

/** Размер вьюпорта и геометрия силуэта — те же числа, что в `drawable/ic_launcher_foreground.xml`. */
private const val GLYPH_VIEWPORT = 1024f
private const val GLYPH_CENTER = 512f
private const val GLYPH_RING_RADIUS = 248f
private const val GLYPH_RING_STROKE = 88f
private const val GLYPH_V_STROKE = 84f

/**
 * Масштаб силуэта: 0.7 — группа внутри самой иконки, 1.5 — переход от вьюпорта 108dp к безопасной
 * зоне 72dp, которую и показывает лаунчер после обрезки маской.
 */
private const val GLYPH_SCALE = 0.7f * 1.5f

/**
 * Силуэт иконки приложения. Рисуется на `Canvas`, а не `painterResource`: четыре превью — это
 * четыре инфляции векторного drawable, и карточка акцента заметно «доезжала» после остальных.
 */
@Composable
private fun LauncherGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val k = GLYPH_SCALE * size.minDimension / GLYPH_VIEWPORT
        fun point(x: Float, y: Float) =
            Offset(center.x + (x - GLYPH_CENTER) * k, center.y + (y - GLYPH_CENTER) * k)

        drawCircle(
            color = color,
            radius = GLYPH_RING_RADIUS * k,
            center = center,
            style = Stroke(width = GLYPH_RING_STROKE * k),
        )
        drawPath(
            path = Path().apply {
                val start = point(418f, 414f)
                val bottom = point(512f, 602f)
                val end = point(606f, 414f)
                moveTo(start.x, start.y)
                lineTo(bottom.x, bottom.y)
                lineTo(end.x, end.y)
            },
            color = color,
            style = Stroke(
                width = GLYPH_V_STROKE * k,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
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
