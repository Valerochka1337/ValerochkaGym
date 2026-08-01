package com.valerochka1337.valerochkagym.ui.workouts

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.GymCardShape
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * UI планирования тренировок вкладки «Тренировки», вынесенный из `WorkoutsScreen.kt`: блок
 * «Ближайшие», двухфазные пикеры даты и времени и диалог отмены. Поведение не менялось —
 * это чистое перемещение composable-функций (см. [WorkoutsScreen]).
 */

/**
 * Собирает момент начала из выбранной в DatePicker даты (UTC-полночь!) и времени из TimePicker.
 * DatePicker отдаёт полночь по UTC, поэтому берём из неё [LocalDate] в зоне UTC, добавляем
 * локальное время и переводим в зону устройства.
 */
internal fun combineToMillis(utcMidnightMillis: Long, hour: Int, minute: Int): Long {
    val localDate = Instant.ofEpochMilli(utcMidnightMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return localDate.atTime(hour, minute)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

/**
 * Блок «Ближайшие» над списком программ. Наступившие тренировки ([UpcomingUi.isDue]) подсвечены
 * рамкой primary и кликабельны — тап запускает тренировку. Иконка-крестик открывает подтверждение
 * отмены.
 */
@Composable
internal fun UpcomingSection(
    items: List<UpcomingUi>,
    onStartDue: (UpcomingUi) -> Unit,
    onCancel: (UpcomingUi) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Ближайшие",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        items.forEach { item ->
            UpcomingCard(
                item = item,
                onStart = { onStartDue(item) },
                onCancel = { onCancel(item) },
            )
        }
    }
}

@Composable
private fun UpcomingCard(
    item: UpcomingUi,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val borderModifier = if (item.isDue) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, GymCardShape)
    } else {
        Modifier
    }
    GymCard(
        modifier = Modifier.fillMaxWidth().then(borderModifier),
        // Тап запускает тренировку только у наступивших — у будущих карточка не кликабельна.
        onClick = if (item.isDue) onStart else null,
        contentPadding = PaddingValues(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.whenLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (item.isDue) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.routineName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (item.isDue) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Пора — нажмите, чтобы начать",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Отменить тренировку",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleDatePickerDialog(
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    // DatePicker оперирует UTC-полночью; сегодняшняя UTC-полночь — нижняя граница выбора.
    val todayUtcMidnight = remember {
        today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    val selectableDates = remember {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcTimeMillis >= todayUtcMidnight

            override fun isSelectableYear(year: Int): Boolean = year >= today.year
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = todayUtcMidnight,
        selectableDates = selectableDates,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let(onConfirm) },
                enabled = state.selectedDateMillis != null,
            ) {
                Text("Далее")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleTimePickerDialog(
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val now = remember { LocalTime.now() }
    val state = rememberTimePickerState(
        initialHour = now.hour,
        initialMinute = now.minute,
        is24Hour = true,
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Время начала",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                )
                TimePicker(state = state)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Отмена")
                    }
                    TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                        Text("Готово")
                    }
                }
            }
        }
    }
}

@Composable
internal fun CancelScheduledDialog(
    routineName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Отменить тренировку?") },
        text = { Text("Запланированная тренировка «$routineName» и событие в календаре будут удалены.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Отменить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Назад")
            }
        },
    )
}
