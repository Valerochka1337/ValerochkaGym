package com.valerochka1337.valerochkagym.ui.workouts

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.service.WorkoutSessionService
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.GymCardShape
import com.valerochka1337.valerochkagym.ui.components.PillButton
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Вкладка «Тренировки»: список программ и быстрый старт. Тап по карточке выбирает
 * программу (обводка primary), меню карточки — редактирование/дублирование/удаление.
 *
 * Старт тренировки создаётся в [WorkoutsViewModel] (single-flight); по событию
 * [WorkoutsViewModel.startEvents] экран навигирует на активную тренировку через [onStartWorkout].
 */
@Composable
fun WorkoutsScreen(
    onCreateRoutine: () -> Unit,
    onEditRoutine: (Long) -> Unit,
    onStartWorkout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorkoutsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val upcoming by viewModel.upcoming.collectAsStateWithLifecycle()
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingCancelId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Флоу планирования: routineId выбранной программы держится обе фазы; scheduleDateUtc != null
    // означает, что дата выбрана и пора показывать выбор времени.
    var scheduleRoutineId by rememberSaveable { mutableStateOf<Long?>(null) }
    var scheduleDateUtc by rememberSaveable { mutableStateOf<Long?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.scheduleEvents.collect { snackbarHostState.showSnackbar(it) }
    }

    val context = LocalContext.current
    // Отказ в разрешении не блокирует тренировку — таймер работает на экране, просто без уведомлений.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* результат не важен */ }

    LaunchedEffect(Unit) {
        viewModel.startEvents.collect {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            WorkoutSessionService.start(context)
            onStartWorkout()
        }
    }

    GlowBackground(modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                WorkoutsHeader()

                upcoming?.takeIf { it.isNotEmpty() }?.let { items ->
                    UpcomingSection(
                        items = items,
                        onStartDue = viewModel::startScheduled,
                        onCancel = { pendingCancelId = it.id },
                    )
                }

                val routines = state.routines
                when {
                    // Ещё не загружено: ничего не показываем (Room отдаёт быстро).
                    routines == null -> Unit
                    routines.isEmpty() -> EmptyState(
                        onCreateRoutine = onCreateRoutine,
                        modifier = Modifier.weight(1f),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(
                            start = 24.dp,
                            end = 24.dp,
                            top = 4.dp,
                            bottom = 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(routines, key = { it.id }) { routine ->
                            RoutineCard(
                                routine = routine,
                                selected = routine.id == state.selectedRoutineId,
                                onClick = { viewModel.onRoutineSelected(routine.id) },
                                onEdit = { onEditRoutine(routine.id) },
                                onDuplicate = { viewModel.duplicate(routine.id) },
                                onSchedule = {
                                    scheduleRoutineId = routine.id
                                    scheduleDateUtc = null
                                },
                                onDelete = { pendingDeleteId = routine.id },
                            )
                        }
                    }
                }

                if (routines != null && routines.isNotEmpty()) {
                    StartBar(
                        startEnabled = state.selectedRoutineId != null,
                        onStart = { state.selectedRoutineId?.let(viewModel::startFromRoutine) },
                        onEmpty = viewModel::startEmpty,
                    )
                }
            }

            IconButton(
                onClick = onCreateRoutine,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 20.dp),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Новая программа",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp),
            )
        }
    }

    val deleteId = pendingDeleteId
    if (deleteId != null) {
        val name = state.routines?.firstOrNull { it.id == deleteId }?.name.orEmpty()
        DeleteRoutineDialog(
            routineName = name,
            onConfirm = {
                viewModel.delete(deleteId)
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null },
        )
    }

    val cancelId = pendingCancelId
    if (cancelId != null) {
        val name = upcoming?.firstOrNull { it.id == cancelId }?.routineName.orEmpty()
        CancelScheduledDialog(
            routineName = name,
            onConfirm = {
                viewModel.cancelScheduled(cancelId)
                pendingCancelId = null
            },
            onDismiss = { pendingCancelId = null },
        )
    }

    // Фаза 1 — выбор даты (только сегодня и дальше). Затем фаза 2 — выбор времени.
    val schedulingRoutineId = scheduleRoutineId
    val pickedDateUtc = scheduleDateUtc
    if (schedulingRoutineId != null && pickedDateUtc == null) {
        ScheduleDatePickerDialog(
            onConfirm = { scheduleDateUtc = it },
            onDismiss = { scheduleRoutineId = null },
        )
    } else if (schedulingRoutineId != null && pickedDateUtc != null) {
        ScheduleTimePickerDialog(
            onConfirm = { hour, minute ->
                viewModel.schedule(schedulingRoutineId, combineToMillis(pickedDateUtc, hour, minute))
                scheduleRoutineId = null
                scheduleDateUtc = null
            },
            onDismiss = {
                scheduleRoutineId = null
                scheduleDateUtc = null
            },
        )
    }
}

/**
 * Собирает момент начала из выбранной в DatePicker даты (UTC-полночь!) и времени из TimePicker.
 * DatePicker отдаёт полночь по UTC, поэтому берём из неё [LocalDate] в зоне UTC, добавляем
 * локальное время и переводим в зону устройства.
 */
private fun combineToMillis(utcMidnightMillis: Long, hour: Int, minute: Int): Long {
    val localDate = Instant.ofEpochMilli(utcMidnightMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return localDate.atTime(hour, minute)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

@Composable
private fun WorkoutsHeader() {
    Text(
        text = "Тренировки",
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
    )
}

@Composable
private fun RoutineCard(
    routine: RoutineCardUi,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onSchedule: () -> Unit,
    onDelete: () -> Unit,
) {
    val borderModifier = if (selected) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, GymCardShape)
    } else {
        Modifier
    }
    GymCard(
        modifier = Modifier.fillMaxWidth().then(borderModifier),
        onClick = onClick,
        contentPadding = PaddingValues(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f).padding(top = 4.dp)) {
                Text(
                    text = "ПРОГРАММА",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = routine.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${routine.exerciseCount} ${exercisesWord(routine.exerciseCount)} · ~${routine.estimatedMinutes} мин",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RoutineCardMenu(
                onEdit = onEdit,
                onDuplicate = onDuplicate,
                onSchedule = onSchedule,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun RoutineCardMenu(
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onSchedule: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "Меню программы",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Редактировать") },
                onClick = {
                    expanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text("Дублировать") },
                onClick = {
                    expanded = false
                    onDuplicate()
                },
            )
            DropdownMenuItem(
                text = { Text("Запланировать") },
                onClick = {
                    expanded = false
                    onSchedule()
                },
            )
            DropdownMenuItem(
                text = { Text("Удалить") },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

/**
 * Блок «Ближайшие» над списком программ. Наступившие тренировки ([UpcomingUi.isDue]) подсвечены
 * рамкой primary и кликабельны — тап запускает тренировку. Иконка-крестик открывает подтверждение
 * отмены.
 */
@Composable
private fun UpcomingSection(
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
private fun ScheduleDatePickerDialog(
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
private fun ScheduleTimePickerDialog(
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
private fun CancelScheduledDialog(
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

@Composable
private fun StartBar(
    startEnabled: Boolean,
    onStart: () -> Unit,
    onEmpty: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PillButton(
            text = "Начать тренировку",
            onClick = onStart,
            enabled = startEnabled,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onEmpty) {
            Text("Пустая тренировка")
        }
    }
}

@Composable
private fun EmptyState(
    onCreateRoutine: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Создайте первую программу",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Соберите список упражнений с подходами, чтобы быстро начинать тренировку.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        PillButton(
            text = "Новая программа",
            onClick = onCreateRoutine,
            leadingIcon = Icons.Default.Add,
        )
    }
}

@Composable
private fun DeleteRoutineDialog(
    routineName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить программу?") },
        text = { Text("Программа «$routineName» будет удалена без возможности восстановления.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Удалить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}

/** Русское склонение слова «упражнение» по количеству. */
private fun exercisesWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "упражнений"
        mod10 == 1 -> "упражнение"
        mod10 in 2..4 -> "упражнения"
        else -> "упражнений"
    }
}
