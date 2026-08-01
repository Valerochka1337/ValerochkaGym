package com.valerochka1337.valerochkagym.ui.routine

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.NumberField

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

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                singleLine = true,
                label = { Text("Название программы") },
                shape = RoundedCornerShape(16.dp),
            )

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 24.dp,
                    end = 24.dp,
                    top = 4.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(state.exercises) { index, exercise ->
                    ExerciseCard(
                        exercise = exercise,
                        isFirst = index == 0,
                        isLast = index == state.exercises.lastIndex,
                        onMoveUp = { viewModel.moveUp(index) },
                        onMoveDown = { viewModel.moveDown(index) },
                        onRemove = { viewModel.removeExercise(index) },
                        onRestChange = { viewModel.setRest(index, it) },
                        onAddSet = { viewModel.addPlannedSet(index) },
                        onRemoveSet = { setIndex -> viewModel.removePlannedSet(index, setIndex) },
                        onSetChange = { setIndex, set -> viewModel.updatePlannedSet(index, setIndex, set) },
                    )
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
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onRestChange: (Int?) -> Unit,
    onAddSet: () -> Unit,
    onRemoveSet: (Int) -> Unit,
    onSetChange: (Int, PlannedSet) -> Unit,
) {
    GymCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            IconButton(onClick = onMoveUp, enabled = !isFirst) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Выше")
            }
            IconButton(onClick = onMoveDown, enabled = !isLast) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Ниже")
            }
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
            label = "Отдых, сек (пусто — по умолчанию)",
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
        Text(
            text = "Подход $number",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        label = "накл %",
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

private fun Int?.toField(): String = this?.toString() ?: ""

private fun Double?.toField(): String = when {
    this == null -> ""
    this % 1.0 == 0.0 -> toInt().toString()
    else -> toString()
}

/** Секунды длительности как строка минут (для CARDIO-поля «мин»). */
private fun Int?.minutesField(): String = this?.let { (it / 60).toString() } ?: ""
