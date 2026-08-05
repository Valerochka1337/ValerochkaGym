package com.valerochka1337.valerochkagym.ui.routine

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.components.ExerciseAvatar
import com.valerochka1337.valerochkagym.ui.components.DragHandle
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.GymCardShape
import com.valerochka1337.valerochkagym.ui.components.NumberField
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.theme.GymMotion
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Полноэкранный редактор программы: имя, список упражнений с подходами и отдыхом.
 * По событию [RoutineEditorViewModel.saved] экран закрывается через [onBack].
 * [onAddExercise] открывает библиотеку в режиме выбора упражнения.
 */
@Composable
fun RoutineEditorScreen(
    onBack: () -> Unit,
    onAddExercise: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RoutineEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = gymHaptics()
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (from.index in state.exercises.indices && to.index in state.exercises.indices) {
            // Reorderable expects the backing list to change before this callback returns.
            viewModel.moveExercise(from.index, to.index)
            haptics.stepFrequent()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.saved.collect { onBack() }
    }

    GlowBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            EditorHeader(
                isNew = state.isNew,
                canSave = state.isValid,
                onBack = onBack,
                onSave = viewModel::save,
            )

            // Тело появляется только с готовыми данными — до этого показываем пустой фон, а не
            // мигающую пустую форму. Для новой программы isLoading = false, тело сразу на месте.
            if (state.isLoading) return@Column

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                singleLine = true,
                label = { Text("Название программы") },
                shape = MaterialTheme.shapes.medium,
            )

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = lazyListState,
                contentPadding = PaddingValues(
                    start = 24.dp,
                    end = 24.dp,
                    top = 4.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(
                    state.exercises,
                    // Ключ по упражнению (а не по позиции): корректная анимация reorder/удаления
                    // и правильная привязка локального состояния полей ввода к своей карточке.
                    key = { _, exercise -> exercise.exerciseId },
                ) { index, exercise ->
                    ReorderableItem(
                        state = reorderableLazyListState,
                        key = exercise.exerciseId,
                    ) { isDragging ->
                        val reorderableItemScope = this
                        val moveActions = buildList {
                            if (index > 0) {
                                add(
                                    CustomAccessibilityAction("Переместить выше") {
                                        viewModel.moveExercise(index, index - 1)
                                        true
                                    },
                                )
                            }
                            if (index < state.exercises.lastIndex) {
                                add(
                                    CustomAccessibilityAction("Переместить ниже") {
                                        viewModel.moveExercise(index, index + 1)
                                        true
                                    },
                                )
                            }
                        }
                        ExerciseCard(
                            modifier = Modifier
                                .animateItem(placementSpec = GymMotion.spatialDefault())
                                .then(
                                    if (isDragging) {
                                        Modifier.border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = GymCardShape,
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .semantics { customActions = moveActions },
                            exercise = exercise,
                            dragHandle = {
                                DragHandle(
                                    reorderableItemScope = reorderableItemScope,
                                    onDragStarted = haptics::dragStart,
                                    onDragStopped = haptics::dragEnd,
                                )
                            },
                            onRemove = { viewModel.removeExercise(index) },
                            onRestChange = { viewModel.setRest(index, it) },
                            onAddSet = { viewModel.addPlannedSet(index) },
                            onRemoveSet = { setIndex -> viewModel.removePlannedSet(index, setIndex) },
                            onSetChange = { setIndex, set -> viewModel.updatePlannedSet(index, setIndex, set) },
                        )
                    }
                }

                item {
                    TextButton(
                        onClick = onAddExercise,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Упражнение")
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorHeader(
    isNew: Boolean,
    canSave: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
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
            text = if (isNew) "Новая программа" else "Редактирование",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSave, enabled = canSave) {
            Text("Сохранить")
        }
    }
}

@Composable
private fun ExerciseCard(
    exercise: EditorExercise,
    dragHandle: @Composable () -> Unit,
    onRemove: () -> Unit,
    onRestChange: (Int?) -> Unit,
    onAddSet: () -> Unit,
    onRemoveSet: (Int) -> Unit,
    onSetChange: (Int, PlannedSet) -> Unit,
    modifier: Modifier = Modifier,
) {
    GymCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExerciseAvatar(name = exercise.exerciseName, type = exercise.exerciseType)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = exercise.exerciseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = exercise.exerciseType.displayName(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            dragHandle()
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Удалить упражнение",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        NumberField(
            value = exercise.restSeconds.toField(),
            onValueChange = { onRestChange(it.toIntOrNull()) },
            modifier = Modifier.fillMaxWidth(),
            label = "Отдых, сек",
        )

        Spacer(Modifier.height(12.dp))

        exercise.plannedSets.forEachIndexed { setIndex, set ->
            PlannedSetRow(
                type = exercise.exerciseType,
                number = setIndex + 1,
                set = set,
                canRemove = exercise.plannedSets.size > 1,
                onChange = { onSetChange(setIndex, it) },
                onRemove = { onRemoveSet(setIndex) },
            )
            Spacer(Modifier.height(8.dp))
        }

        TextButton(onClick = onAddSet) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Подход")
        }
    }
}

@Composable
private fun PlannedSetRow(
    type: ExerciseType,
    number: Int,
    set: PlannedSet,
    canRemove: Boolean,
    onChange: (PlannedSet) -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SetNumberBadge(number)
        Spacer(Modifier.width(10.dp))
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (type) {
                ExerciseType.STRENGTH -> {
                    NumberField(
                        value = set.weightKg.toField(),
                        onValueChange = { onChange(set.copy(weightKg = it.toDoubleOrNull())) },
                        modifier = Modifier.weight(1f),
                        label = "кг",
                        decimal = true,
                    )
                    NumberField(
                        value = set.reps.toField(),
                        onValueChange = { onChange(set.copy(reps = it.toIntOrNull())) },
                        modifier = Modifier.weight(1f),
                        label = "повт",
                    )
                }

                ExerciseType.TIMED -> {
                    NumberField(
                        value = set.durationSec.toField(),
                        onValueChange = { onChange(set.copy(durationSec = it.toIntOrNull())) },
                        modifier = Modifier.weight(1f),
                        label = "сек",
                    )
                }

                ExerciseType.CARDIO -> {
                    NumberField(
                        value = set.speedKmh.toField(),
                        onValueChange = { onChange(set.copy(speedKmh = it.toDoubleOrNull())) },
                        modifier = Modifier.weight(1f),
                        label = "км/ч",
                        decimal = true,
                    )
                    NumberField(
                        value = set.inclinePct.toField(),
                        onValueChange = { onChange(set.copy(inclinePct = it.toDoubleOrNull())) },
                        modifier = Modifier.weight(1f),
                        label = "накл",
                        decimal = true,
                    )
                    NumberField(
                        value = set.durationSec.minutesField(),
                        onValueChange = {
                            onChange(set.copy(durationSec = it.toIntOrNull()?.let { min -> min * 60 }))
                        },
                        modifier = Modifier.weight(1f),
                        label = "мин",
                    )
                }
            }
        }
        IconButton(onClick = onRemove, enabled = canRemove) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Удалить подход",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Номер подхода в виде компактного кружка вместо надписи «Подход N». */
@Composable
private fun SetNumberBadge(number: Int) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun Int?.toField(): String = this?.toString() ?: ""

private fun Double?.toField(): String = when {
    this == null -> ""
    this % 1.0 == 0.0 -> toInt().toString()
    else -> toString()
}

/** Секунды длительности как строка минут (для CARDIO-поля «мин»). */
private fun Int?.minutesField(): String = this?.let { (it / 60).toString() } ?: ""
