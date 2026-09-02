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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogFilters
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogOrigin
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogSort
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogTypeFilter
import com.valerochka1337.valerochkagym.ui.components.ExerciseAvatar
import com.valerochka1337.valerochkagym.ui.components.FadeInContent
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.LoadingState
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.GymFilterChip
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.navigation.GymWindowWidthClass
import com.valerochka1337.valerochkagym.ui.theme.GymMotion

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
    var filterSheetOpen by rememberSaveable { mutableStateOf(false) }
    var sortSheetOpen by rememberSaveable { mutableStateOf(false) }
    val exerciseListState = rememberLazyListState()

    LaunchedEffect(state.sort, state.filters) {
        if (!state.exercises.isNullOrEmpty()) exerciseListState.scrollToItem(0)
    }

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
                LibraryHeader(onBack = onBack)

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
                    onOpenFilters = { filterSheetOpen = true },
                    onOpenSort = { sortSheetOpen = true },
                    filtersActive = state.filters != ExerciseCatalogFilters(),
                    sortActive = state.sort != ExerciseCatalogSort.RECENT,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalPadding),
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
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = exerciseListState,
                            contentPadding = PaddingValues(
                                start = horizontalPadding,
                                end = horizontalPadding,
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
                                    onInfo = onExerciseInfo?.let { callback ->
                                        { haptics.tap(); callback(exercise) }
                                    },
                                    modifier = Modifier.animateItem(placementSpec = GymMotion.spatialFast()),
                                )
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

    if (filterSheetOpen) {
        FilterSheet(
            state = state,
            onDismiss = { filterSheetOpen = false },
            onType = viewModel::setTypeFilter,
            onOrigin = viewModel::setOrigin,
            onGroup = viewModel::toggleGroupFacet,
            onClearGroup = viewModel::clearGroupFacet,
            onReset = viewModel::resetFilters,
        )
    }
    if (sortSheetOpen) {
        SortSheet(
            state = state,
            onDismiss = { sortSheetOpen = false },
            onSort = { sort -> viewModel.setSort(sort); sortSheetOpen = false },
        )
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
    onOpenFilters: () -> Unit,
    onOpenSort: () -> Unit,
    filtersActive: Boolean,
    sortActive: Boolean,
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
            Row {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Close, contentDescription = "Очистить")
                    }
                }
                IconButton(onClick = onOpenFilters) {
                    Icon(
                        Icons.Rounded.FilterList,
                        contentDescription = if (filtersActive) "Фильтры: активны" else "Фильтры",
                        tint = if (filtersActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenSort) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Sort,
                        contentDescription = if (sortActive) "Сортировка: активна" else "Сортировка",
                        tint = if (sortActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(),
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FilterSheet(
    state: ExerciseLibraryUiState,
    onDismiss: () -> Unit,
    onOrigin: (ExerciseCatalogOrigin) -> Unit,
    onType: (ExerciseCatalogTypeFilter) -> Unit,
    onGroup: (MuscleGroup) -> Unit,
    onClearGroup: () -> Unit,
    onReset: () -> Unit,
) {
    val counts = state.facetCounts ?: return
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FilterList, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("Фильтры", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                if (state.filters != ExerciseCatalogFilters()) {
                    TextButton(onClick = onReset) { Text("Сбросить") }
                }
            }
            SheetChipRow("Тип") {
                ExerciseCatalogTypeFilter.entries.forEach { type ->
                    GymFilterChip(type == state.filters.type, { onType(type) }, type.typeLabel(), counts.types[type])
                }
            }
            SheetChipRow("Каталог") {
                ExerciseCatalogOrigin.entries.forEach { origin ->
                    GymFilterChip(origin == state.filters.origin, { onOrigin(origin) }, origin.originLabel(), counts.origins[origin])
                }
            }
            SheetChipRow("Группа мышц") {
                GymFilterChip(state.filters.group == null, onClearGroup, "Все", counts.groups[null])
                MuscleGroup.entries.forEach { group ->
                    GymFilterChip(group == state.filters.group, { onGroup(group) }, group.displayName(), counts.groups[group])
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SortSheet(
    state: ExerciseLibraryUiState,
    onDismiss: () -> Unit,
    onSort: (ExerciseCatalogSort) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("Сортировка", style = MaterialTheme.typography.titleLarge)
            }
            SheetChipRow("Порядок") {
                ExerciseCatalogSort.entries.forEach { sort ->
                    GymFilterChip(sort == state.sort, { onSort(sort) }, sort.sortLabel())
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SheetChipRow(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() } } }
    }
}

private fun ExerciseCatalogTypeFilter.typeLabel() = when (this) {
    ExerciseCatalogTypeFilter.ALL -> "Все"
    ExerciseCatalogTypeFilter.STRENGTH -> "Силовое"
    ExerciseCatalogTypeFilter.CARDIO_OR_TIMED -> "Кардио / время"
}
private fun ExerciseCatalogOrigin.originLabel() = when (this) {
    ExerciseCatalogOrigin.ALL -> "Все"
    ExerciseCatalogOrigin.CUSTOM -> "Свои"
    ExerciseCatalogOrigin.BUILT_IN -> "Встроенные"
}
private fun ExerciseCatalogSort.sortLabel() = when (this) {
    ExerciseCatalogSort.ALPHABETICAL -> "А–Я"
    ExerciseCatalogSort.RECENT -> "Недавние"
    ExerciseCatalogSort.FREQUENT -> "Частые"
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
