package com.valerochka1337.valerochkagym.ui.workouts

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.service.WorkoutSessionService
import com.valerochka1337.valerochkagym.ui.components.CircleIconButton
import com.valerochka1337.valerochkagym.ui.components.FadeInContent
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.GymCardShape
import com.valerochka1337.valerochkagym.ui.components.GymTopBar
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.theme.GymMotion

/**
 * Вкладка «Тренировки»: тап по программе выбирает её, а закреплённый снизу блок запускает выбранную
 * или пустую тренировку. Меню карточки отвечает за вторичные действия.
 *
 * Старт тренировки создаётся в [WorkoutsViewModel] (single-flight); по событию
 * [WorkoutsViewModel.startEvents] экран навигирует на активную тренировку через [onStartWorkout].
 */
@Composable
fun WorkoutsScreen(
    onCreateRoutine: () -> Unit,
    onEditRoutine: (Long) -> Unit,
    onStartWorkout: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorkoutsViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
  val snackbarHostState = remember { SnackbarHostState() }
  var showNotificationRationale by rememberSaveable { mutableStateOf(false) }

  val context = LocalContext.current
  val continueToWorkout = {
    WorkoutSessionService.start(context)
    onStartWorkout()
  }
  // Отказ не блокирует тренировку — таймер остаётся на экране, но не переживёт сворачивание.
  val notificationPermission =
      rememberLauncherForActivityResult(
          ActivityResultContracts.RequestPermission(),
      ) {
        continueToWorkout()
      }

  LaunchedEffect(Unit) {
    viewModel.startEvents.collect {
      if (
          ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
              PackageManager.PERMISSION_GRANTED
      ) {
        showNotificationRationale = true
      } else {
        continueToWorkout()
      }
    }
  }
  LaunchedEffect(Unit) { viewModel.messages.collect(snackbarHostState::showSnackbar) }

  GlowBackground(modifier = modifier) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        GymTopBar(
            title = "Тренировки",
            onOpenSettings = onOpenSettings,
            actions = {
              CircleIconButton(
                  icon = Icons.Default.Add,
                  contentDescription = "Новая программа",
                  onClick = onCreateRoutine,
                  tint = MaterialTheme.colorScheme.primary,
              )
            },
        )

        val routines = state.routines
        when {
          routines == null ->
              Box(
                  modifier = Modifier.fillMaxSize().weight(1f),
                  contentAlignment = Alignment.Center,
              ) {
                CircularProgressIndicator()
              }
          routines.isEmpty() ->
              FadeInContent(modifier = Modifier.weight(1f)) {
                EmptyState(
                    onCreateRoutine = onCreateRoutine,
                    modifier = Modifier.fillMaxSize(),
                )
              }
          else ->
              FadeInContent(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            start = 24.dp,
                            end = 24.dp,
                            top = 4.dp,
                            bottom = 16.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  items(routines, key = { it.id }) { routine ->
                    val haptics = gymHaptics()
                    RoutineCard(
                        routine = routine,
                        selected = routine.id == state.selectedRoutineId,
                        onClick = {
                          haptics.tap()
                          viewModel.onRoutineSelected(routine.id)
                        },
                        onEdit = { onEditRoutine(routine.id) },
                        onDuplicate = { viewModel.duplicate(routine.id) },
                        onDelete = { pendingDeleteId = routine.id },
                        modifier = Modifier.animateItem(),
                    )
                  }
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
      SnackbarHost(
          hostState = snackbarHostState,
          modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
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

  if (showNotificationRationale) {
    AlertDialog(
        onDismissRequest = {
          showNotificationRationale = false
          continueToWorkout()
        },
        title = { Text("Показывать таймер отдыха в уведомлении?") },
        text = {
          Text(
              "Разрешение нужно, чтобы видеть таймер и вернуться к тренировке после " +
                  "сворачивания. Без него тренировка и таймер на экране продолжат работать.",
          )
        },
        confirmButton = {
          TextButton(
              onClick = {
                showNotificationRationale = false
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
              }
          ) {
            Text("Разрешить")
          }
        },
        dismissButton = {
          TextButton(
              onClick = {
                showNotificationRationale = false
                continueToWorkout()
              }
          ) {
            Text("Не сейчас")
          }
        },
    )
  }
}

@Composable
private fun RoutineCard(
    routine: RoutineCardUi,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val borderColor by
      animateColorAsState(
          targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
          animationSpec = GymMotion.effectsFast(),
          label = "routine-border",
      )
  GymCard(
      modifier =
          modifier.fillMaxWidth().border(2.dp, borderColor, GymCardShape).semantics {
            this.selected = selected
            stateDescription = if (selected) "Выбрано" else "Не выбрано"
          },
      onClick = onClick,
      contentPadding = PaddingValues(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
  ) {
    Row(verticalAlignment = Alignment.Top) {
      Column(modifier = Modifier.weight(1f).padding(top = 4.dp)) {
        Text(
            text = routine.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text =
                "${routine.exerciseCount} ${exercisesWord(routine.exerciseCount)} · около ${routine.estimatedMinutes} мин",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text =
                if (routine.gymNames.isEmpty()) {
                  "Без ограничений по залу"
                } else {
                  routine.gymNames.joinToString()
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      RoutineCardMenu(
          onEdit = onEdit,
          onDuplicate = onDuplicate,
          onDelete = onDelete,
      )
    }
  }
}

@Composable
private fun StartBar(
    startEnabled: Boolean,
    onStart: () -> Unit,
    onEmpty: () -> Unit,
) {
  Column(
      modifier =
          Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    PillButton(
        text = "Начать тренировку",
        onClick = onStart,
        enabled = startEnabled,
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(onClick = onEmpty) { Text("Пустая тренировка") }
  }
}

@Composable
private fun RoutineCardMenu(
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
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
          text = { Text("Удалить") },
          onClick = {
            expanded = false
            onDelete()
          },
      )
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
      confirmButton = { TextButton(onClick = onConfirm) { Text("Удалить") } },
      dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
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
