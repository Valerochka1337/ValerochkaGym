package com.valerochka1337.valerochkagym.ui.measurements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.ui.components.CircleIconButton
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.NumberField
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import java.time.Instant
import java.time.ZoneOffset

/** Полноэкранная форма создания/правки локального замера. */
@Composable
fun MeasurementEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MeasurementEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = gymHaptics()
    var showDatePicker by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.finished.collect { onBack() }
    }

    GlowBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            MeasurementEditorHeader(
                isNew = state.isNew,
                onBack = onBack,
                onDelete = if (state.isNew || state.isLoading) {
                    null
                } else {
                    { showDeleteDialog = true }
                },
            )
            if (state.isLoading) return@Column

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DateCard(
                    measuredAt = state.measuredAt,
                    onPickDate = {
                        haptics.tap()
                        showDatePicker = true
                    },
                )
                InBodyCard(state, viewModel)
                CircumferencesCard(state, viewModel)
                if (!state.isNew) {
                    GymCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Google Sheets",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Экспорт append-only: правки и удаление этого локального замера не меняют уже добавленную строку в таблице.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                PillButton(
                    text = if (state.isNew) "Сохранить замер" else "Сохранить изменения",
                    onClick = {
                        haptics.confirm()
                        viewModel.save()
                    },
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!state.canSave) {
                    Text(
                        text = "Укажите хотя бы один показатель.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showDatePicker) {
        MeasurementDatePicker(
            measuredAt = state.measuredAt,
            onConfirm = { utcMillis ->
                viewModel.setDateFromUtcMillis(utcMillis)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удалить замер?") },
            text = {
                Text(
                    "Локальный замер будет удалён. Уже выгруженная строка в Google Sheets останется без изменений.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.reject()
                    showDeleteDialog = false
                    viewModel.delete()
                }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun MeasurementEditorHeader(
    isNew: Boolean,
    onBack: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Назад",
            onClick = onBack,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (isNew) "Новый замер" else "Замер",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        onDelete?.let {
            CircleIconButton(
                icon = Icons.Rounded.Delete,
                contentDescription = "Удалить замер",
                onClick = it,
            )
        }
    }
}

@Composable
private fun DateCard(measuredAt: Long, onPickDate: () -> Unit) {
    GymCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.CalendarMonth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Дата замера",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = formatMeasurementDate(measuredAt, java.time.ZoneId.systemDefault()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onPickDate) { Text("Изменить") }
        }
    }
}

@Composable
private fun InBodyCard(state: MeasurementEditorUiState, viewModel: MeasurementEditorViewModel) {
    GymCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.MonitorWeight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Состав тела · InBody",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Для сравнения делайте замеры на одном аппарате и в похожих условиях.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        NumberField(
            value = state.weightKg,
            onValueChange = viewModel::setWeightKg,
            modifier = Modifier.fillMaxWidth(),
            label = "Вес, кг",
            decimal = true,
        )
        Spacer(Modifier.height(8.dp))
        NumberField(
            value = state.skeletalMuscleMassKg,
            onValueChange = viewModel::setSkeletalMuscleMassKg,
            modifier = Modifier.fillMaxWidth(),
            label = "Масса скелетных мышц, кг",
            decimal = true,
        )
        Spacer(Modifier.height(8.dp))
        NumberField(
            value = state.bodyFatPercentage,
            onValueChange = viewModel::setBodyFatPercentage,
            modifier = Modifier.fillMaxWidth(),
            label = "Процент жира, %",
            decimal = true,
        )
        Spacer(Modifier.height(8.dp))
        NumberField(
            value = state.visceralFatLevel,
            onValueChange = viewModel::setVisceralFatLevel,
            modifier = Modifier.fillMaxWidth(),
            label = "Уровень висцерального жира",
        )
        Spacer(Modifier.height(8.dp))
        NumberField(
            value = state.waistHipRatio,
            onValueChange = viewModel::setWaistHipRatio,
            modifier = Modifier.fillMaxWidth(),
            label = "WHR из InBody",
            decimal = true,
        )
    }
}

@Composable
private fun CircumferencesCard(state: MeasurementEditorUiState, viewModel: MeasurementEditorViewModel) {
    val calculatedWhr = state.effectiveWaistHipRatio
    GymCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Straighten,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Обхваты, см",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(12.dp))
        NumberField(
            value = state.waistCm,
            onValueChange = viewModel::setWaistCm,
            modifier = Modifier.fillMaxWidth(),
            label = "Талия",
            decimal = true,
        )
        Spacer(Modifier.height(8.dp))
        NumberField(
            value = state.chestCm,
            onValueChange = viewModel::setChestCm,
            modifier = Modifier.fillMaxWidth(),
            label = "Грудь",
            decimal = true,
        )
        Spacer(Modifier.height(8.dp))
        NumberField(
            value = state.hipsCm,
            onValueChange = viewModel::setHipsCm,
            modifier = Modifier.fillMaxWidth(),
            label = "Бёдра",
            decimal = true,
        )
        Spacer(Modifier.height(8.dp))
        NumberField(
            value = state.rightRelaxedArmCm,
            onValueChange = viewModel::setRightRelaxedArmCm,
            modifier = Modifier.fillMaxWidth(),
            label = "Правое расслабленное плечо",
            decimal = true,
        )
        Spacer(Modifier.height(8.dp))
        NumberField(
            value = state.rightThighCm,
            onValueChange = viewModel::setRightThighCm,
            modifier = Modifier.fillMaxWidth(),
            label = "Правое бедро",
            decimal = true,
        )
        if (state.waistHipRatio.isBlank() && calculatedWhr != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "WHR рассчитан из талии и бёдер: ${formatMeasurementValue(com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementMetric.WAIST_HIP_RATIO, calculatedWhr)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeasurementDatePicker(
    measuredAt: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // M3 DatePicker работает в UTC-полуночи; из локального measuredAt берём именно дату.
    val initialDate = Instant.ofEpochMilli(measuredAt)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialDate)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { pickerState.selectedDateMillis?.let(onConfirm) }) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    ) {
        DatePicker(state = pickerState)
    }
}
