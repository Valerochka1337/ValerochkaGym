package com.valerochka1337.valerochkagym.ui.library

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.components.ExerciseAvatar
import com.valerochka1337.valerochkagym.ui.components.FadeInContent
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.GymFilterChip

/**
 * The exercise library: search field, muscle-group filter chips and a list of
 * exercises. A FAB opens the editor sheet for creating a custom exercise.
 *
 * When [onExerciseSelected] is non-null the screen acts as a picker: tapping a
 * row invokes the callback. В режиме просмотра тап по строке открывает разметку мышц
 * упражнения — тот же редактор, что и при создании (см. [ExerciseEditorSheet]).
 */
@Composable
fun ExerciseLibraryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onExerciseSelected: ((ExerciseEntity) -> Unit)? = null,
    viewModel: ExerciseLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()

    GlowBackground(modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                LibraryHeader(onBack = onBack)

                SearchField(
                    query = state.query,
                    onQueryChange = viewModel::onQueryChange,
                    onClear = viewModel::clearQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                )

                Spacer(Modifier.height(12.dp))

                GroupFilterRow(
                    selectedGroup = state.selectedGroup,
                    onGroupClicked = viewModel::onGroupClicked,
                )

                Spacer(Modifier.height(12.dp))

                val exercises = state.exercises
                when {
                    // Not loaded yet: show nothing (Room emits quickly).
                    exercises == null -> Unit
                    exercises.isEmpty() -> FadeInContent { EmptyState(modifier = Modifier.fillMaxSize()) }
                    else -> FadeInContent {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 24.dp,
                                end = 24.dp,
                                top = 4.dp,
                                bottom = 96.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(exercises, key = { it.id }) { exercise ->
                                ExerciseRow(
                                    exercise = exercise,
                                    onClick = onExerciseSelected
                                        ?.let { callback -> { callback(exercise) } }
                                        ?: { viewModel.openEdit(exercise) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = viewModel::openCreate,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Создать упражнение")
            }
        }
    }

    editor?.let { initial ->
        ExerciseEditorSheet(
            initial = initial,
            onDismiss = viewModel::closeEditor,
            onSave = viewModel::saveEditor,
        )
    }
}

@Composable
private fun LibraryHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 24.dp, top = 12.dp, bottom = 8.dp),
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
            text = "Упражнения",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        singleLine = true,
        placeholder = { Text("Поиск упражнения") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Close, contentDescription = "Очистить")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(),
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun GroupFilterRow(
    selectedGroup: MuscleGroup?,
    onGroupClicked: (MuscleGroup) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(MuscleGroup.entries, key = { it.name }) { group ->
            val selected = group == selectedGroup
            GymFilterChip(
                selected = selected,
                onClick = { onGroupClicked(group) },
                label = group.displayName(),
            )
        }
    }
}

@Composable
private fun ExerciseRow(
    exercise: ExerciseEntity,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    GymCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
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
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${exercise.muscleGroup.displayName()} · ${exercise.type.displayName()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (exercise.isCustom) {
                Spacer(Modifier.width(12.dp))
                CustomBadge()
            }
        }
    }
}

@Composable
private fun CustomBadge() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = "своё",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Ничего не найдено",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
