package com.valerochka1337.valerochkagym.ui.gyms

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.domain.GymConfigurationConflict
import com.valerochka1337.valerochkagym.domain.GymRoutineReference
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.components.ExerciseAvatar
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics

/** Полноэкранный редактор имени зала и доступного в нём каталога упражнений. */
@Composable
fun GymEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GymEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = gymHaptics()
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = state.isBusy) { }

    LaunchedEffect(viewModel) {
        viewModel.finished.collect {
            haptics.success()
            onBack()
        }
    }

    GlowBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            GymEditorHeader(
                title = if (state.isNew) "Новый зал" else "Редактирование зала",
                onBack = onBack,
                backEnabled = !state.isBusy,
            )

            val loadError = state.loadError
            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                loadError != null -> GymEditorLoadError(
                    message = loadError,
                    onBack = onBack,
                )
                else -> GymEditorForm(
                    state = state,
                    onNameChange = viewModel::setName,
                    onQueryChange = viewModel::setQuery,
                    onClearQuery = viewModel::clearQuery,
                    onToggleExercise = { exercise ->
                        val willBeSelected = exercise.id !in state.selectedExerciseIds
                        haptics.toggle(willBeSelected)
                        viewModel.toggleExercise(exercise.id)
                    },
                    onSave = {
                        haptics.confirm()
                        viewModel.save()
                    },
                    onDelete = { showDeleteConfirmation = true },
                )
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!state.isBusy) showDeleteConfirmation = false },
            title = { Text("Удалить зал?") },
            text = { Text("Конфигурация «${state.name}» будет удалена. Это действие нельзя отменить.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.reject()
                        showDeleteConfirmation = false
                        viewModel.delete()
                    },
                    enabled = !state.isBusy,
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmation = false },
                    enabled = !state.isBusy,
                ) {
                    Text("Отмена")
                }
            },
        )
    }

    state.saveConflict?.let { conflict ->
        SaveConflictDialog(conflict = conflict, onDismiss = viewModel::dismissSaveConflict)
    }

    state.deleteConflict?.let { routines ->
        DeleteConflictDialog(routines = routines, onDismiss = viewModel::dismissDeleteConflict)
    }

    state.actionError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissActionError,
            title = { Text("Не удалось выполнить действие") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissActionError) { Text("Понятно") }
            },
        )
    }
}

@Composable
private fun GymEditorHeader(
    title: String,
    onBack: () -> Unit,
    backEnabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, enabled = backEnabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Назад",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun GymEditorForm(
    state: GymEditorUiState,
    onNameChange: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onToggleExercise: (ExerciseEntity) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            enabled = !state.isBusy,
            singleLine = true,
            label = { Text("Название зала") },
            placeholder = { Text("Например, Зал у дома") },
            shape = RoundedCornerShape(16.dp),
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            enabled = !state.isBusy,
            singleLine = true,
            placeholder = { Text("Поиск упражнения") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = onClearQuery) {
                        Icon(Icons.Rounded.Close, contentDescription = "Очистить поиск")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(),
            shape = RoundedCornerShape(16.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Упражнения",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Выбрано: ${state.selectedExerciseIds.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val exercises = state.exercises
        when {
            exercises == null -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            state.filteredExercises.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.query.isBlank()) {
                        "В каталоге пока нет упражнений."
                    } else {
                        "По этому запросу ничего не найдено."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.filteredExercises, key = { it.id }) { exercise ->
                    val selected = exercise.id in state.selectedExerciseIds
                    ExerciseChoiceRow(
                        exercise = exercise,
                        selected = selected,
                        enabled = !state.isBusy,
                        onToggle = { onToggleExercise(exercise) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PillButton(
                text = if (state.isSaving) "Сохраняем…" else "Сохранить",
                onClick = onSave,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!state.isNew) {
                TextButton(
                    onClick = onDelete,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (state.isDeleting) "Удаляем…" else "Удалить зал",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExerciseChoiceRow(
    exercise: ExerciseEntity,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GymCard(
        modifier = modifier.fillMaxWidth(),
        onClick = if (enabled) onToggle else null,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExerciseAvatar(exercise = exercise)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = exercise.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${exercise.muscleGroup.displayName()} · ${exercise.type.displayName()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(
                checked = selected,
                onCheckedChange = if (enabled) ({ onToggle() }) else null,
            )
        }
    }
}

@Composable
private fun GymEditorLoadError(
    message: String,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Вернитесь к списку и попробуйте снова.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            PillButton(text = "Вернуться", onClick = onBack)
        }
    }
}

@Composable
private fun SaveConflictDialog(
    conflict: GymConfigurationConflict,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Состав зала нельзя изменить") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Изменение сделает упражнения недоступными в связанных программах " +
                        "или активной тренировке.",
                )
                if (conflict.routines.isNotEmpty()) {
                    ConflictSection(
                        title = "Затронутые связи",
                        values = conflict.routines.map { it.name },
                    )
                }
                if (conflict.exercises.isNotEmpty()) {
                    ConflictSection(
                        title = "Упражнения",
                        values = conflict.exercises.map { it.name },
                    )
                }
                Text(
                    "Оставьте упражнения в зале, измените программы либо завершите " +
                        "активную тренировку.",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Понятно") }
        },
    )
}

@Composable
private fun DeleteConflictDialog(
    routines: List<GymRoutineReference>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Зал сейчас используется") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Удаление заблокировано, пока зал выбран в программе или сохранён " +
                        "в снимке активной тренировки.",
                )
                ConflictSection(title = "Затронутые связи", values = routines.map { it.name })
                Text("Отвяжите зал от программ либо завершите активную тренировку.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Понятно") }
        },
    )
}

@Composable
private fun ConflictSection(
    title: String,
    values: List<String>,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
    values.distinct().forEach { value ->
        Text(text = "• $value", style = MaterialTheme.typography.bodyMedium)
    }
}
