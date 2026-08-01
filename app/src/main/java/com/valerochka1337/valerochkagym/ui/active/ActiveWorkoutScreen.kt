package com.valerochka1337.valerochkagym.ui.active

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.NumberField
import com.valerochka1337.valerochkagym.ui.components.PillButton

/** Крупный шаг веса (обычный тап), кг. */
private const val WEIGHT_STEP = 2.5

/** Точный шаг веса (долгое нажатие), кг. */
private const val WEIGHT_FINE_STEP = 0.5

private const val SPEED_STEP = 0.5
private const val INCLINE_STEP = 0.5
private const val DURATION_STEP = 15
private const val REPS_STEP = 1

/**
 * Экран активной тренировки («вариант B» — фокус на текущем подходе). Тренировка уже создана
 * (старт на вкладке «Тренировки»); состояние приходит реактивно из [ActiveWorkoutViewModel].
 * [onFinished] ведёт на итоги, [onDiscarded] — на главную, [onAddExercise] открывает библиотеку.
 */
@Composable
fun ActiveWorkoutScreen(
    onFinished: (workoutId: String) -> Unit,
    onDiscarded: () -> Unit,
    onNavigateBack: () -> Unit,
    onAddExercise: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActiveWorkoutViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ActiveWorkoutEvent.NavigateToSummary -> onFinished(event.workoutId)
                ActiveWorkoutEvent.NavigateHome -> onDiscarded()
            }
        }
    }

    KeepScreenOn()

    val setActions = remember(viewModel) {
        SetActions(
            stepWeight = viewModel::stepWeight,
            stepReps = viewModel::stepReps,
            stepDuration = viewModel::stepDuration,
            stepSpeed = viewModel::stepSpeed,
            stepIncline = viewModel::stepIncline,
            setWeight = viewModel::setWeight,
            setReps = viewModel::setReps,
            setDuration = viewModel::setDuration,
            setSpeed = viewModel::setSpeed,
            setIncline = viewModel::setIncline,
            complete = viewModel::completeSet,
            uncomplete = viewModel::uncompleteSet,
            addSet = viewModel::addSet,
            deleteSet = viewModel::deleteSet,
        )
    }

    GlowBackground(modifier = modifier) {
        val workout = state.workout
        when {
            workout != null -> ActiveWorkoutContent(
                state = state,
                elapsedSeconds = viewModel.elapsedSeconds,
                setActions = setActions,
                onDeleteExercise = viewModel::deleteExercise,
                onAddExercise = onAddExercise,
                onFinish = viewModel::finish,
                onDiscard = viewModel::discard,
            )

            state.loading -> Unit

            else -> NoActiveWorkout(onNavigateBack = onNavigateBack)
        }
    }
}

/** Колбэки правок подходов, прокинутые от [ActiveWorkoutViewModel] в карточки текущего подхода. */
private class SetActions(
    val stepWeight: (Long, Double) -> Unit,
    val stepReps: (Long, Int) -> Unit,
    val stepDuration: (Long, Int) -> Unit,
    val stepSpeed: (Long, Double) -> Unit,
    val stepIncline: (Long, Double) -> Unit,
    val setWeight: (Long, String) -> Unit,
    val setReps: (Long, String) -> Unit,
    val setDuration: (Long, String) -> Unit,
    val setSpeed: (Long, String) -> Unit,
    val setIncline: (Long, String) -> Unit,
    val complete: (Long) -> Unit,
    val uncomplete: (Long) -> Unit,
    val addSet: (Long) -> Unit,
    val deleteSet: (Long) -> Unit,
)

@Composable
private fun ActiveWorkoutContent(
    state: ActiveWorkoutUiState,
    elapsedSeconds: StateFlow<Long>,
    setActions: SetActions,
    onDeleteExercise: (Long) -> Unit,
    onAddExercise: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
) {
    val workout = state.workout ?: return
    val exercises = workout.exercises
    val currentIndex = exercises.indexOfFirst { exercise -> exercise.sets.any { !it.isCompleted } }
    val currentNumber = if (currentIndex >= 0) currentIndex + 1 else exercises.size

    var showFinishDialog by rememberSaveable { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteExerciseId by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        ActiveWorkoutHeader(
            name = workout.workout.name,
            elapsedSeconds = elapsedSeconds,
            currentNumber = currentNumber,
            total = exercises.size,
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(exercises, key = { it.workoutExercise.id }) { exercise ->
                ExerciseSection(
                    exercise = exercise,
                    previous = state.previousByExercise[exercise.exercise.id].orEmpty(),
                    actions = setActions,
                    onDeleteExercise = { pendingDeleteExerciseId = exercise.workoutExercise.id },
                )
            }

            item {
                TextButton(onClick = onAddExercise, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Упражнение")
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PillButton(
                text = "Завершить",
                onClick = { showFinishDialog = true },
                leadingIcon = Icons.Default.Check,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { showDiscardDialog = true }) {
                Text("Отменить тренировку", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showFinishDialog) {
        ConfirmDialog(
            title = "Завершить тренировку?",
            text = "Пустые невыполненные подходы будут отброшены, тренировка попадёт в историю.",
            confirmText = "Завершить",
            onConfirm = {
                showFinishDialog = false
                onFinish()
            },
            onDismiss = { showFinishDialog = false },
        )
    }

    if (showDiscardDialog) {
        ConfirmDialog(
            title = "Отменить тренировку?",
            text = "Тренировка будет удалена без возможности восстановления.",
            confirmText = "Удалить",
            destructive = true,
            onConfirm = {
                showDiscardDialog = false
                onDiscard()
            },
            onDismiss = { showDiscardDialog = false },
        )
    }

    val deleteExerciseId = pendingDeleteExerciseId
    if (deleteExerciseId != null) {
        val name = exercises.firstOrNull { it.workoutExercise.id == deleteExerciseId }
            ?.exercise?.name.orEmpty()
        ConfirmDialog(
            title = "Убрать упражнение?",
            text = "«$name» и его подходы будут удалены из тренировки.",
            confirmText = "Убрать",
            destructive = true,
            onConfirm = {
                pendingDeleteExerciseId = null
                onDeleteExercise(deleteExerciseId)
            },
            onDismiss = { pendingDeleteExerciseId = null },
        )
    }
}

@Composable
private fun ActiveWorkoutHeader(
    name: String,
    elapsedSeconds: StateFlow<Long>,
    currentNumber: Int,
    total: Int,
) {
    // Собираем таймер только здесь, чтобы посекундный тик не рекомпозил список подходов.
    val elapsed by elapsedSeconds.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatElapsed(elapsed),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            if (total > 0) {
                Text(
                    text = "упражнение $currentNumber из $total",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExerciseSection(
    exercise: WorkoutExerciseWithSets,
    previous: String,
    actions: SetActions,
    onDeleteExercise: () -> Unit,
) {
    val type = exercise.exercise.type
    val currentSet = exercise.sets.firstOrNull { !it.isCompleted }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = exercise.exercise.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (previous.isNotEmpty()) {
                    Text(
                        text = "прошлый: $previous",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDeleteExercise) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Убрать упражнение",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        exercise.sets.forEach { set ->
            when {
                set.id == currentSet?.id -> CurrentSetCard(
                    set = set,
                    type = type,
                    actions = actions,
                )

                set.isCompleted -> CompletedSetPill(
                    set = set,
                    type = type,
                    onClick = { actions.uncomplete(set.id) },
                )

                else -> FutureSetPill(set = set, type = type)
            }
            Spacer(Modifier.height(8.dp))
        }

        TextButton(onClick = { actions.addSet(exercise.workoutExercise.id) }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Подход")
        }
    }
}

@Composable
private fun CurrentSetCard(
    set: WorkoutSetEntity,
    type: ExerciseType,
    actions: SetActions,
) {
    GymCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "ПОДХОД ${set.setIndex + 1}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { actions.deleteSet(set.id) }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Удалить подход",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        when (type) {
            ExerciseType.STRENGTH -> {
                StepperField(
                    label = "кг",
                    value = set.weightKg.toField(),
                    onValueChange = { actions.setWeight(set.id, it) },
                    onStepDown = { actions.stepWeight(set.id, -WEIGHT_STEP) },
                    onStepUp = { actions.stepWeight(set.id, WEIGHT_STEP) },
                    onFineDown = { actions.stepWeight(set.id, -WEIGHT_FINE_STEP) },
                    onFineUp = { actions.stepWeight(set.id, WEIGHT_FINE_STEP) },
                    decimal = true,
                )
                Spacer(Modifier.height(10.dp))
                StepperField(
                    label = "повторы",
                    value = set.reps.toField(),
                    onValueChange = { actions.setReps(set.id, it) },
                    onStepDown = { actions.stepReps(set.id, -REPS_STEP) },
                    onStepUp = { actions.stepReps(set.id, REPS_STEP) },
                )
            }

            ExerciseType.TIMED -> {
                StepperField(
                    label = "секунды",
                    value = set.durationSec.toField(),
                    onValueChange = { actions.setDuration(set.id, it) },
                    onStepDown = { actions.stepDuration(set.id, -DURATION_STEP) },
                    onStepUp = { actions.stepDuration(set.id, DURATION_STEP) },
                )
            }

            ExerciseType.CARDIO -> {
                StepperField(
                    label = "км/ч",
                    value = set.speedKmh.toField(),
                    onValueChange = { actions.setSpeed(set.id, it) },
                    onStepDown = { actions.stepSpeed(set.id, -SPEED_STEP) },
                    onStepUp = { actions.stepSpeed(set.id, SPEED_STEP) },
                    decimal = true,
                )
                Spacer(Modifier.height(10.dp))
                StepperField(
                    label = "наклон %",
                    value = set.inclinePct.toField(),
                    onValueChange = { actions.setIncline(set.id, it) },
                    onStepDown = { actions.stepIncline(set.id, -INCLINE_STEP) },
                    onStepUp = { actions.stepIncline(set.id, INCLINE_STEP) },
                    decimal = true,
                )
                Spacer(Modifier.height(10.dp))
                StepperField(
                    label = "секунды",
                    value = set.durationSec.toField(),
                    onValueChange = { actions.setDuration(set.id, it) },
                    onStepDown = { actions.stepDuration(set.id, -DURATION_STEP) },
                    onStepUp = { actions.stepDuration(set.id, DURATION_STEP) },
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        PillButton(
            text = "Подход выполнен",
            onClick = { actions.complete(set.id) },
            leadingIcon = Icons.Default.Check,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Пара кнопок ± вокруг числового поля. Обычный тап — [onStepDown]/[onStepUp]; долгое нажатие,
 * если задано, — [onFineDown]/[onFineUp] (используется для точного шага веса ±0.5).
 */
@Composable
private fun StepperField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onStepDown: () -> Unit,
    onStepUp: () -> Unit,
    modifier: Modifier = Modifier,
    onFineDown: (() -> Unit)? = null,
    onFineUp: (() -> Unit)? = null,
    decimal: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StepButton(
            symbol = "−",
            contentDescription = "уменьшить $label",
            onClick = onStepDown,
            onLongClick = onFineDown,
        )
        NumberField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            label = label,
            decimal = decimal,
        )
        StepButton(
            symbol = "+",
            contentDescription = "увеличить $label",
            onClick = onStepUp,
            onLongClick = onFineUp,
        )
    }
}

@Composable
private fun StepButton(
    symbol: String,
    contentDescription: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CompletedSetPill(
    set: WorkoutSetEntity,
    type: ExerciseType,
    onClick: () -> Unit,
) {
    SetPill(
        number = set.setIndex + 1,
        values = formatSetValues(set, type),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        onClick = onClick,
        trailing = {
            Icon(
                Icons.Default.Check,
                contentDescription = "Выполнено, нажмите чтобы отменить",
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@Composable
private fun FutureSetPill(
    set: WorkoutSetEntity,
    type: ExerciseType,
) {
    SetPill(
        number = set.setIndex + 1,
        values = formatSetValues(set, type),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = null,
        trailing = null,
    )
}

@Composable
private fun SetPill(
    number: Int,
    values: String,
    containerColor: Color,
    contentColor: Color,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) {
    val clickable = if (onClick != null) Modifier.combinedClickable(onClick = onClick) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .then(clickable)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Подход $number",
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = values.ifEmpty { "—" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
private fun NoActiveWorkout(onNavigateBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Нет активной тренировки",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onNavigateBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Назад")
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}

/** Пока экран на переднем плане, экран устройства не гаснет. */
@Composable
private fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Краткое представление значений подхода для свёрнутых пилюль. */
private fun formatSetValues(set: WorkoutSetEntity, type: ExerciseType): String = when (type) {
    ExerciseType.STRENGTH -> {
        val weight = set.weightKg.toField()
        val reps = set.reps.toField()
        when {
            weight.isNotEmpty() && reps.isNotEmpty() -> "$weight×$reps"
            weight.isNotEmpty() -> "$weight кг"
            reps.isNotEmpty() -> "$reps повт"
            else -> ""
        }
    }

    ExerciseType.TIMED -> set.durationSec?.let { "$it сек" }.orEmpty()

    ExerciseType.CARDIO -> buildList {
        set.speedKmh?.let { add("${it.toField()} км/ч") }
        set.inclinePct?.let { add("${it.toField()}%") }
        set.durationSec?.let { add("${it / 60} мин") }
    }.joinToString(" · ")
}

private fun formatElapsed(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun Double?.toField(): String = when {
    this == null -> ""
    this % 1.0 == 0.0 -> toInt().toString()
    else -> toString()
}

private fun Int?.toField(): String = this?.toString() ?: ""
