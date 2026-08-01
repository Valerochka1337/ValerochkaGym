package com.valerochka1337.valerochkagym.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Собирает момент начала из даты (UTC-полночь, как отдаёт M3 DatePicker) и времени из TimePicker.
 * Берём [java.time.LocalDate] из UTC-полуночи, добавляем локальное время и переводим в зону устройства.
 */
internal fun combineToMillis(utcMidnightMillis: Long, hour: Int, minute: Int): Long {
    val localDate = Instant.ofEpochMilli(utcMidnightMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return localDate.atTime(hour, minute)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

/**
 * Диалог выбора времени начала (24 часа). По умолчанию — текущее время. Общий пикер для планирования
 * ad-hoc тренировки в календаре и редактора недельного расписания.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleTimePickerDialog(
    initialHour: Int? = null,
    initialMinute: Int? = null,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val now = remember { LocalTime.now() }
    val state = rememberTimePickerState(
        initialHour = initialHour ?: now.hour,
        initialMinute = initialMinute ?: now.minute,
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
