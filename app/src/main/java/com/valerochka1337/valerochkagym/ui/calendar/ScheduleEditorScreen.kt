package com.valerochka1337.valerochkagym.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.schedule.DayRule
import com.valerochka1337.valerochkagym.data.schedule.WeeklySchedule
import com.valerochka1337.valerochkagym.ui.common.ScheduleTimePickerDialog
import com.valerochka1337.valerochkagym.ui.components.CircleIconButton
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton

private val WEEKDAY_FULL =
    listOf(
        "Понедельник",
        "Вторник",
        "Среда",
        "Четверг",
        "Пятница",
        "Суббота",
        "Воскресенье",
    )

/** Черновик одного дня недели в редакторе расписания. */
private data class DayDraft(
    val enabled: Boolean = false,
    val routineId: Long? = null,
    val hour: Int = 18,
    val minute: Int = 0,
)

/**
 * Полноэкранный редактор недельного расписания. 7 строк дней недели: тумблер + выбор программы +
 * выбор времени. «Сохранить» применяет шаблон (замена серии Google Calendar), «Очистить расписание»
 * удаляет серию. Результат приходит в снекбар; экран не закрывается сам — чтобы показать ошибку.
 */
@Composable
fun ScheduleEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
  val schedule by viewModel.weeklySchedule.collectAsStateWithLifecycle()
  val routines by viewModel.routines.collectAsStateWithLifecycle()
  val isScheduleBusy by viewModel.isScheduleBusy.collectAsStateWithLifecycle()

  val drafts = remember { mutableStateMapOf<Int, DayDraft>() }
  val editedDays = remember { mutableStateMapOf<Int, Boolean>() }
  var routinePickerForDay by remember { mutableStateOf<Int?>(null) }
  var timePickerForDay by remember { mutableStateOf<Int?>(null) }

  val snackbarHostState = remember { SnackbarHostState() }

  // Пока пользователь не трогал день, отражаем сохранённое расписание (переживает начальный
  // пустой эмит StateFlow); отредактированные дни не перетираем.
  LaunchedEffect(schedule) {
    (1..7).forEach { iso ->
      if (editedDays[iso] != true) {
        val rule = schedule.rules.firstOrNull { it.isoDay == iso }
        drafts[iso] =
            if (rule != null) {
              DayDraft(
                  enabled = true,
                  routineId = rule.routineId,
                  hour = rule.hour,
                  minute = rule.minute,
              )
            } else {
              DayDraft()
            }
      }
    }
  }

  LaunchedEffect(Unit) {
    viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
  }

  val routineNames = remember(routines) { routines.associate { it.id to it.name } }

  fun markEdited(iso: Int) {
    editedDays[iso] = true
  }

  GlowBackground(modifier = modifier) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = 16.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          CircleIconButton(
              icon = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = "Назад",
              onClick = onBack,
          )
          Text(
              text = "Расписание",
              style = MaterialTheme.typography.headlineLarge,
              color = MaterialTheme.colorScheme.onBackground,
              modifier = Modifier.weight(1f),
          )
        }

        Column(
            modifier =
                Modifier.weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          (1..7).forEach { iso ->
            val draft = drafts[iso] ?: DayDraft()
            DayRow(
                title = WEEKDAY_FULL[iso - 1],
                draft = draft,
                routineName = draft.routineId?.let { routineNames[it] },
                onToggle = { enabled ->
                  markEdited(iso)
                  drafts[iso] = draft.copy(enabled = enabled)
                },
                onPickRoutine = { routinePickerForDay = iso },
                onPickTime = { timePickerForDay = iso },
            )
          }

          Spacer(Modifier.height(8.dp))
          ScheduleClearButton(enabled = !isScheduleBusy, onClick = viewModel::clearSchedule)
          Spacer(Modifier.height(80.dp))
        }
      }

      ScheduleSaveButton(
          enabled = !isScheduleBusy,
          onClick = {
            val rules =
                (1..7).mapNotNull { iso ->
                  val d = drafts[iso] ?: return@mapNotNull null
                  val routineId = d.routineId
                  if (d.enabled && routineId != null) DayRule(iso, routineId, d.hour, d.minute)
                  else null
                }
            viewModel.saveSchedule(WeeklySchedule(rules))
          },
          modifier =
              Modifier.align(Alignment.BottomCenter)
                  .fillMaxWidth()
                  .padding(horizontal = 24.dp, vertical = 16.dp),
      )

      SnackbarHost(
          hostState = snackbarHostState,
          modifier =
              Modifier.align(Alignment.BottomCenter)
                  .padding(bottom = 88.dp, start = 16.dp, end = 16.dp),
      )
    }
  }

  routinePickerForDay?.let { iso ->
    RoutinePickerSheet(
        routines = routines,
        onPick = { routineId ->
          markEdited(iso)
          val d = drafts[iso] ?: DayDraft()
          drafts[iso] = d.copy(enabled = true, routineId = routineId)
          routinePickerForDay = null
        },
        onDismiss = { routinePickerForDay = null },
    )
  }

  timePickerForDay?.let { iso ->
    val d = drafts[iso] ?: DayDraft()
    ScheduleTimePickerDialog(
        initialHour = d.hour,
        initialMinute = d.minute,
        onConfirm = { hour, minute ->
          markEdited(iso)
          drafts[iso] = d.copy(hour = hour, minute = minute)
          timePickerForDay = null
        },
        onDismiss = { timePickerForDay = null },
    )
  }
}

@Composable
internal fun ScheduleClearButton(enabled: Boolean, onClick: () -> Unit) {
  TextButton(
      onClick = onClick,
      enabled = enabled,
      colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
  ) {
    Text("Очистить расписание")
  }
}

@Composable
internal fun ScheduleSaveButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  PillButton(text = "Сохранить", onClick = onClick, enabled = enabled, modifier = modifier)
}

@Composable
private fun DayRow(
    title: String,
    draft: DayDraft,
    routineName: String?,
    onToggle: (Boolean) -> Unit,
    onPickRoutine: () -> Unit,
    onPickTime: () -> Unit,
) {
  GymCard(
      modifier = Modifier.fillMaxWidth(),
      contentPadding = PaddingValues(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f),
      )
      Switch(checked = draft.enabled, onCheckedChange = onToggle)
    }
    if (draft.enabled) {
      Spacer(Modifier.height(10.dp))
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            text = routineName ?: "Выберите программу",
            style = MaterialTheme.typography.bodyLarge,
            color =
                if (routineName != null) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.primary,
            modifier =
                Modifier.weight(1f)
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .clickable(onClick = onPickRoutine),
        )
        Text(
            text = "%02d:%02d".format(draft.hour, draft.minute),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier.heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .clickable(onClick = onPickTime),
        )
      }
    }
  }
}
