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
import androidx.compose.foundation.layout.widthIn
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogLevel
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogOrigin
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogSort
import com.valerochka1337.valerochkagym.ui.components.ExerciseAvatar
import com.valerochka1337.valerochkagym.ui.components.FadeInContent
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.LoadingState
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.GymFilterChip
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.navigation.GymWindowWidthClass

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
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    windowWidthClass: GymWindowWidthClass = GymWindowWidthClass.Compact,
    onExerciseSelected: ((ExerciseEntity) -> Unit)? = null,
    onExerciseAddedToWorkout: (() -> Unit)? = null,
    onExerciseInfo: ((ExerciseEntity) -> Unit)? = null,
    viewModel: ExerciseLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val aiCreation by viewModel.aiCreation.collectAsStateWithLifecycle()
    val haptics = gymHaptics()

    BackHandler { if (viewModel.onBack()) onBack() }

    LaunchedEffect(viewModel, onExerciseSelected, onExerciseAddedToWorkout) {
        viewModel.savedExercise.collect { result ->
            if (result.addedToWorkout) {
                onExerciseAddedToWorkout?.invoke()
            } else {
                onExerciseSelected?.invoke(result.exercise)
            }
        }
    }

    GlowBackground(modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 840.dp)
                    .align(Alignment.TopCenter),
            ) {
                val horizontalPadding = if (windowWidthClass == GymWindowWidthClass.Compact) 16.dp else 24.dp
                LibraryHeader(onBack = { if (viewModel.onBack()) onBack() }, level = state.level)

                if (state.gymNames.isNotEmpty()) {
                    Text(
                        text = "Доступно во всех: ${state.gymNames.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 4.dp),
                    )
                }

                SearchField(
                    query = state.query,
                    onQueryChange = viewModel::onQueryChange,
                    onClear = viewModel::clearQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalPadding),
                )

                Spacer(Modifier.height(12.dp))

                CatalogFacetRows(
                    state = state,
                    padding = horizontalPadding,
                    onSort = viewModel::setSort,
                    onOrigin = viewModel::setOrigin,
                    onType = viewModel::toggleType,
                    onGroup = viewModel::toggleGroupFacet,
                    onMuscle = viewModel::toggleMuscle,
                )

                Spacer(Modifier.height(12.dp))

                val exercises = state.exercises
                when {
                    exercises == null -> LoadingState(
                        label = "Загружаем упражнения…",
                        modifier = Modifier.weight(1f),
                    )
                    exercises.isEmpty() -> FadeInContent {
                        EmptyState(
                            resetVisible = state.hasActiveConstraints,
                            onReset = viewModel::resetCatalog,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> FadeInContent {
                        val leaf = state.level as? ExerciseCatalogLevel.MuscleLeaf
                        val leafResults = leaf?.let { state.projection?.results("", state.filters, state.sort, it) }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = horizontalPadding,
                                end = horizontalPadding,
                                top = 4.dp,
                                bottom = 96.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (!state.hasActiveConstraints && state.level == ExerciseCatalogLevel.Overview) {
                                val quick = state.projection?.quickSections()
                                if (!quick?.recent.isNullOrEmpty()) {
                                    item(key = "recent-heading") { SectionHeading("Недавние") }
                                    items(quick!!.recent, key = { "recent-${it.id}" }) { exercise ->
                                        ExerciseRow(exercise, onExerciseSelected?.let { callback -> { callback(exercise) } } ?: { viewModel.openEdit(exercise) }, onExerciseInfo?.let { callback -> { haptics.tap(); callback(exercise) } })
                                    }
                                }
                                if (!quick?.frequent.isNullOrEmpty()) {
                                    item(key = "frequent-heading") { SectionHeading("Частые") }
                                    items(quick!!.frequent, key = { "frequent-${it.id}" }) { exercise ->
                                        ExerciseRow(exercise, onExerciseSelected?.let { callback -> { callback(exercise) } } ?: { viewModel.openEdit(exercise) }, onExerciseInfo?.let { callback -> { haptics.tap(); callback(exercise) } })
                                    }
                                }
                                item(key = "groups-heading") { SectionHeading("Все упражнения по группам") }
                                state.projection?.groups?.forEach { group ->
                                    item(key = "group-${group.group.name}") {
                                        GroupRow(group.group, group.count) { viewModel.openGroup(group.group) }
                                    }
                                }
                                item(key = "all-heading") { SectionHeading("Полный каталог · ${exercises.size}") }
                            }
                            if (state.level is ExerciseCatalogLevel.Group && !state.hasActiveConstraints) {
                                val group = (state.level as ExerciseCatalogLevel.Group).group
                                item(key = "all-group") { SectionHeading("Все упражнения · ${group.displayName()}") }
                                state.projection?.groups?.firstOrNull { it.group == group }?.muscles?.forEach { muscle ->
                                    item(key = "muscle-${group.name}-${muscle.name}") {
                                        MuscleRow(muscle) { viewModel.openMuscle(group, muscle) }
                                    }
                                }
                                item(key = "group-results") { SectionHeading("Все упражнения · ${exercises.size}") }
                            }
                            if (leafResults != null && !state.hasActiveConstraints) {
                                if (leafResults.primary.isNotEmpty()) {
                                    item(key = "primary-heading") { SectionHeading("Основная нагрузка") }
                                    items(leafResults.primary, key = { "primary-${it.id}" }) { exercise ->
                                        ExerciseRow(
                                            exercise = exercise,
                                            onClick = onExerciseSelected?.let { callback -> { callback(exercise) } } ?: { viewModel.openEdit(exercise) },
                                            onInfo = onExerciseInfo?.let { callback -> { haptics.tap(); callback(exercise) } },
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                }
                                if (leafResults.secondary.isNotEmpty()) {
                                    item(key = "secondary-heading") { SectionHeading("Дополнительная нагрузка") }
                                    items(leafResults.secondary, key = { "secondary-${it.id}" }) { exercise ->
                                        ExerciseRow(
                                            exercise = exercise,
                                            onClick = onExerciseSelected?.let { callback -> { callback(exercise) } } ?: { viewModel.openEdit(exercise) },
                                            onInfo = onExerciseInfo?.let { callback -> { haptics.tap(); callback(exercise) } },
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                }
                            }
                            if (state.hasActiveConstraints) item(key = "result-count") { SectionHeading("Найдено: ${exercises.size}") }
                            if (leafResults == null || state.hasActiveConstraints) {
                                items(exercises, key = { it.id }) { exercise ->
                                    ExerciseRow(
                                        exercise = exercise,
                                        onClick = onExerciseSelected
                                            ?.let { callback -> { callback(exercise) } }
                                            ?: { viewModel.openEdit(exercise) },
                                        onInfo = onExerciseInfo?.let { callback ->
                                            { haptics.tap(); callback(exercise) }
                                        },
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = {
                    haptics.tap()
                    viewModel.openCreate()
                },
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

    aiCreation?.let { creation ->
        AiExerciseCreationSheet(
            state = creation,
            onDescriptionChange = viewModel::onAiDescriptionChange,
            onGenerate = {
                haptics.tap()
                viewModel.generateAiExercise()
            },
            onCreateManually = {
                haptics.tap()
                viewModel.openManualCreate()
            },
            onOpenSettings = {
                viewModel.closeAiCreation()
                onOpenSettings()
            },
            onDismiss = viewModel::closeAiCreation,
        )
    }
}

@Composable
private fun LibraryHeader(onBack: () -> Unit, level: ExerciseCatalogLevel) {
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
            text = when (level) {
                ExerciseCatalogLevel.Overview -> "Упражнения"
                is ExerciseCatalogLevel.Group -> level.group.displayName()
                is ExerciseCatalogLevel.MuscleLeaf -> level.muscle.displayName()
            },
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
private fun CatalogFacetRows(
    state: ExerciseLibraryUiState,
    padding: androidx.compose.ui.unit.Dp,
    onSort: (ExerciseCatalogSort) -> Unit,
    onOrigin: (ExerciseCatalogOrigin) -> Unit,
    onType: (com.valerochka1337.valerochkagym.data.db.entity.ExerciseType) -> Unit,
    onGroup: (MuscleGroup) -> Unit,
    onMuscle: (com.valerochka1337.valerochkagym.data.db.entity.Muscle) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = padding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ExerciseCatalogSort.entries, key = { "sort-${it.name}" }) { sort ->
            GymFilterChip(
                selected = state.sort == sort,
                onClick = { onSort(sort) },
                label = when (sort) {
                    ExerciseCatalogSort.ALPHABETICAL -> "А-Я"
                    ExerciseCatalogSort.RECENT -> "Недавние"
                    ExerciseCatalogSort.FREQUENT -> "Частые"
                },
            )
        }
        items(ExerciseCatalogOrigin.entries, key = { "origin-${it.name}" }) { origin ->
            GymFilterChip(
                selected = state.filters.origin == origin,
                onClick = { onOrigin(origin) },
                label = when (origin) {
                    ExerciseCatalogOrigin.ALL -> "Все"
                    ExerciseCatalogOrigin.BUILT_IN -> "Встроенные"
                    ExerciseCatalogOrigin.CUSTOM -> "Свои"
                },
            )
        }
        items(com.valerochka1337.valerochkagym.data.db.entity.ExerciseType.entries, key = { "type-${it.name}" }) { type ->
            GymFilterChip(
                selected = type == state.filters.type,
                onClick = { onType(type) },
                label = type.displayName(),
            )
        }
        state.projection?.groups?.forEach { group ->
            item(key = "facet-group-${group.group.name}") {
                GymFilterChip(
                    selected = state.filters.group == group.group,
                    onClick = { onGroup(group.group) },
                    label = group.group.displayName(),
                )
            }
        }
        val visibleMuscles = state.projection?.groups
            ?.filter { state.filters.group == null || it.group == state.filters.group }
            ?.flatMap { it.muscles }
            ?.distinct()
            .orEmpty()
        visibleMuscles.forEach { muscle ->
            item(key = "facet-muscle-${muscle.name}") {
                GymFilterChip(
                    selected = state.filters.muscle == muscle,
                    onClick = { onMuscle(muscle) },
                    label = muscle.displayName(),
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun GroupRow(group: MuscleGroup, count: Int, onClick: () -> Unit) {
    GymCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.displayName(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MuscleRow(muscle: com.valerochka1337.valerochkagym.data.db.entity.Muscle, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(muscle.displayName(), modifier = Modifier.weight(1f))
        Text("Открыть")
    }
}

@Composable
private fun ExerciseRow(
    exercise: ExerciseEntity,
    onClick: (() -> Unit)?,
    onInfo: (() -> Unit)?,
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
            if (onInfo != null) {
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onInfo) {
                    Icon(Icons.Rounded.Info, contentDescription = "Открыть карточку упражнения")
                }
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
private fun EmptyState(
    resetVisible: Boolean,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (resetVisible) "По этим ограничениям ничего не найдено" else "Ничего не найдено",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (resetVisible) TextButton(onClick = onReset) { Text("Сбросить всё") }
        }
    }
}
