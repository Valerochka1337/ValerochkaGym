package com.valerochka1337.valerochkagym.ui.gyms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.EquipmentCatalog
import com.valerochka1337.valerochkagym.domain.GymConfigurationConflict
import com.valerochka1337.valerochkagym.domain.GymRoutineReference
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.GymFilterChip
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.navigation.GymWindowWidthClass
import com.valerochka1337.valerochkagym.ui.theme.GymMotion

/** Full-screen inventory editor. A standard gym is rendered as an immutable source snapshot. */
@Composable
fun GymEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    windowWidthClass: GymWindowWidthClass = GymWindowWidthClass.Compact,
    viewModel: GymEditorViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val haptics = gymHaptics()
  var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
  BackHandler { if (!state.isBusy) viewModel.requestExit() }

  LaunchedEffect(viewModel) {
    viewModel.finished.collect {
      haptics.success()
      onBack()
    }
  }
  LaunchedEffect(viewModel) { viewModel.exit.collect { onBack() } }

  GlowBackground(modifier = modifier) {
    Column(modifier = Modifier.fillMaxSize()) {
      GymEditorTopBar(
          state = state,
          onBack = viewModel::requestExit,
          onDelete = { showDeleteConfirmation = true },
      )
      when {
        state.isLoading -> LoadingContent()
        state.loadError != null -> GymEditorLoadError(message = state.loadError!!, onBack = onBack)
        else ->
            GymEditorForm(
                state = state,
                compact = windowWidthClass == GymWindowWidthClass.Compact,
                onNameChange = viewModel::setName,
                onQueryChange = viewModel::setQuery,
                onClearQuery = viewModel::clearQuery,
                onToggleEquipment = { equipment ->
                  haptics.toggle(equipment.id !in state.selectedEquipmentIds)
                  viewModel.toggleEquipment(equipment.id)
                },
                onMode = viewModel::setMode,
                onToggleAll = viewModel::toggleAll,
                onToggleGroup = { group ->
                  val entries = state.groupedBulkEquipment[group].orEmpty()
                  haptics.toggle(entries.any { it.id !in state.selectedEquipmentIds })
                  viewModel.toggleGroup(group)
                },
                onToggleGroupExpanded = viewModel::toggleGroupExpanded,
                onUndo = viewModel::undoBulk,
                onPreview = { viewModel.setPreview(!state.preview) },
                onSave = {
                  haptics.confirm()
                  viewModel.save()
                },
            )
      }
    }
  }

  if (showDeleteConfirmation && state.origin != "STANDARD") {
    AlertDialog(
        onDismissRequest = { if (!state.isBusy) showDeleteConfirmation = false },
        title = { Text("Удалить зал?") },
        text = {
          Text("Конфигурация «${state.name}» будет удалена. Это действие нельзя отменить.")
        },
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
          TextButton(onClick = { showDeleteConfirmation = false }, enabled = !state.isBusy) {
            Text("Отмена")
          }
        },
    )
  }

  state.saveConflict?.let {
    SaveConflictDialog(conflict = it, onDismiss = viewModel::dismissSaveConflict)
  }
  state.deleteConflict?.let {
    DeleteConflictDialog(routines = it, onDismiss = viewModel::dismissDeleteConflict)
  }
  state.actionError?.let { message ->
    AlertDialog(
        onDismissRequest = viewModel::dismissActionError,
        title = { Text("Не удалось выполнить действие") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = viewModel::dismissActionError) { Text("Понятно") } },
    )
  }
  if (state.discardConfirmationVisible) {
    AlertDialog(
        onDismissRequest = viewModel::continueEditing,
        title = { Text("Не сохранять изменения?") },
        text = { Text("Черновик оборудования и названия будет потерян.") },
        confirmButton = { TextButton(onClick = viewModel::discardDraft) { Text("Не сохранять") } },
        dismissButton = {
          Row {
            TextButton(onClick = viewModel::continueEditing) { Text("Продолжить") }
            TextButton(
                onClick = {
                  viewModel.continueEditing()
                  viewModel.save()
                },
                enabled = state.canSave,
            ) {
              Text("Сохранить")
            }
          }
        },
    )
  }
  if (state.preview) {
    GymAvailabilityPreviewDialog(
        loading = state.previewLoading,
        exercises = state.previewExercises,
        onDismiss = { viewModel.setPreview(false) },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GymEditorTopBar(
    state: GymEditorUiState,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
  var overflowOpen by rememberSaveable { mutableStateOf(false) }
  val personalExistingGym = !state.isLoading && !state.isNew && state.origin != "STANDARD"
  TopAppBar(
      title = {
        Text(
            text =
                when {
                  state.origin == "STANDARD" -> "Встроенный зал"
                  state.isNew -> "Новый зал"
                  else -> "Редактирование зала"
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
      },
      navigationIcon = {
        IconButton(onClick = onBack, enabled = !state.isBusy) {
          Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
        }
      },
      actions = {
        if (personalExistingGym) {
          Box {
            IconButton(onClick = { overflowOpen = true }, enabled = !state.isBusy) {
              Icon(Icons.Rounded.MoreVert, contentDescription = "Дополнительные действия")
            }
            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
              DropdownMenuItem(
                  text = { Text("Удалить зал", color = MaterialTheme.colorScheme.error) },
                  leadingIcon = {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                  },
                  onClick = {
                    overflowOpen = false
                    onDelete()
                  },
              )
            }
          }
        }
      },
      colors =
          TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
  )
}

@Composable
private fun GymEditorForm(
    state: GymEditorUiState,
    compact: Boolean,
    onNameChange: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onToggleEquipment: (EquipmentCatalog.Equipment) -> Unit,
    onMode: (GymEquipmentMode) -> Unit,
    onToggleAll: () -> Unit,
    onToggleGroup: (String) -> Unit,
    onToggleGroupExpanded: (String) -> Unit,
    onUndo: () -> Unit,
    onPreview: () -> Unit,
    onSave: () -> Unit,
) {
  val isPersonal = state.origin != "STANDARD"
  val controlsEnabled = isPersonal && !state.isBusy
  val horizontalPadding = if (compact) 16.dp else 24.dp
  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag("gym_editor_content"),
      contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (state.copySourceWasLegacy) {
      item("legacy-copy") {
        EditorCard {
          Text(
              "Исходный зал использует старую доступность. Настройте оборудование для новой копии.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    item("name") {
      EditorCard {
        Text("Название", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (isPersonal) {
          OutlinedTextField(
              value = state.name,
              onValueChange = onNameChange,
              modifier = Modifier.fillMaxWidth(),
              enabled = controlsEnabled,
              singleLine = true,
              label = { Text("Название зала") },
              placeholder = { Text("Например, Зал у дома") },
              shape = MaterialTheme.shapes.large,
          )
        } else {
          Text(state.name, style = MaterialTheme.typography.bodyLarge)
          Text(
              "Встроенный зал доступен только для просмотра. Создайте личную копию, чтобы изменить оснащение.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    item("equipment-header") {
      EditorCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
              "Оборудование",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.weight(1f),
          )
          Text(
              "${state.selectedEquipmentIds.size} выбрано",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (isPersonal) {
          OutlinedTextField(
              value = state.query,
              onValueChange = onQueryChange,
              modifier = Modifier.fillMaxWidth(),
              enabled = controlsEnabled,
              singleLine = true,
              placeholder = { Text("Поиск оборудования") },
              leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
              trailingIcon = {
                if (state.query.isNotEmpty()) {
                  IconButton(onClick = onClearQuery, enabled = controlsEnabled) {
                    Icon(Icons.Rounded.Close, contentDescription = "Очистить поиск")
                  }
                }
              },
              keyboardOptions =
                  androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
              shape = MaterialTheme.shapes.large,
          )
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorModeChip(
                selected = state.mode == GymEquipmentMode.ALL,
                onClick = { onMode(GymEquipmentMode.ALL) },
                label = "Все",
                enabled = controlsEnabled,
            )
            EditorModeChip(
                selected = state.mode == GymEquipmentMode.SELECTED,
                onClick = { onMode(GymEquipmentMode.SELECTED) },
                label = "Выбранные",
                count = state.selectedEquipmentIds.size,
                enabled = controlsEnabled,
            )
            TextButton(onClick = onToggleAll, enabled = controlsEnabled) {
              val scope =
                  if (state.query.isBlank()) state.equipment.orEmpty() else state.filteredEquipment
              Text(
                  if (scope.all { it.id in state.selectedEquipmentIds }) {
                    if (state.query.isBlank()) "Снять всё" else "Снять найденное"
                  } else if (state.query.isBlank()) {
                    "Выбрать всё"
                  } else {
                    "Выбрать найденное"
                  },
              )
            }
            state.bulkUndo?.let {
              TextButton(onClick = onUndo, enabled = controlsEnabled) { Text("Отменить") }
            }
          }
        }
      }
    }
    val equipment = state.equipment
    when {
      equipment == null -> item("equipment-loading") { LoadingContent(minHeight = 160.dp) }
      state.groupedBulkEquipment.isEmpty() ->
          item("equipment-empty") {
            EditorCard {
              Text(
                  if (state.query.isBlank()) {
                    "В каталоге пока нет оборудования."
                  } else {
                    "По этому запросу ничего не найдено."
                  },
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
      else -> {
        state.groupedBulkEquipment.forEach { (group, allEntries) ->
          val entries =
              if (state.mode == GymEquipmentMode.ALL) allEntries
              else allEntries.filter { it.id in state.selectedEquipmentIds }
          val groupExpanded =
              group in state.expandedGroups ||
                  state.query.isNotBlank() ||
                  (!isPersonal && entries.isNotEmpty())
          item("group:$group") {
            EquipmentGroupHeader(
                group = group,
                entries = allEntries,
                selectedIds = state.selectedEquipmentIds,
                isPersonal = isPersonal,
                controlsEnabled = controlsEnabled,
                expanded = groupExpanded,
                onToggleExpanded = { onToggleGroupExpanded(group) },
                onToggleGroup = { onToggleGroup(group) },
            )
          }
          if (groupExpanded && entries.isNotEmpty()) {
            items(entries, key = { "equipment:${it.id}" }) { entry ->
              EquipmentChoiceRow(
                  equipment = entry,
                  selected = entry.id in state.selectedEquipmentIds,
                  enabled = controlsEnabled,
                  onToggle = { onToggleEquipment(entry) },
                  modifier = Modifier.animateItem(placementSpec = GymMotion.spatialFast()),
              )
            }
          }
        }
      }
    }
    if (isPersonal) {
      item("actions") {
        EditorCard {
          PillButton(
              text = if (state.isSaving) "Сохраняем…" else "Сохранить",
              onClick = onSave,
              enabled = state.canSave,
              modifier = Modifier.fillMaxWidth(),
          )
          TextButton(
              onClick = onPreview,
              enabled = controlsEnabled,
              modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
                if (state.preview) "Скрыть доступные упражнения"
                else "Проверить доступные упражнения"
            )
          }
        }
      }
    }
  }
}

@Composable
private fun EditorModeChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    enabled: Boolean,
    count: Int? = null,
) {
  if (enabled) {
    GymFilterChip(selected = selected, onClick = onClick, label = label, count = count)
  } else {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = false,
        label = { Text(if (count == null) label else "$label $count") },
    )
  }
}

@Composable
private fun EditorCard(content: @Composable () -> Unit) {
  Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    GymCard(modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth()) {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = { content() })
    }
  }
}

@Composable
private fun EquipmentGroupHeader(
    group: String,
    entries: List<EquipmentCatalog.Equipment>,
    selectedIds: Set<String>,
    isPersonal: Boolean,
    controlsEnabled: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleGroup: () -> Unit,
) {
  val selectedCount = entries.count { it.id in selectedIds }
  val groupState =
      when (selectedCount) {
        0 -> ToggleableState.Off
        entries.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
      }
  Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    GymCard(
        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (isPersonal) {
          TextButton(
              onClick = onToggleExpanded,
              enabled = controlsEnabled,
              modifier = Modifier.weight(1f),
          ) {
            Text("$group · $selectedCount/${entries.size}")
          }
          TriStateCheckbox(
              state = groupState,
              onClick = onToggleGroup,
              enabled = controlsEnabled,
              modifier =
                  Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                    contentDescription = "Оборудование группы $group"
                    stateDescription =
                        when (groupState) {
                          ToggleableState.Off -> "Не выбрано"
                          ToggleableState.On -> "Выбрано всё"
                          ToggleableState.Indeterminate -> "Выбрано частично"
                        }
                  },
          )
        } else {
          Text("$group · $selectedCount/${entries.size}", modifier = Modifier.weight(1f))
          if (expanded) {
            Text(
                "Выбрано",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun EquipmentChoiceRow(
    equipment: EquipmentCatalog.Equipment,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    GymCard(
        modifier =
            Modifier.widthIn(max = 840.dp).fillMaxWidth().semantics {
              contentDescription = equipment.name
              role = Role.Checkbox
              stateDescription = if (selected) "Выбрано" else "Не выбрано"
            },
        onClick = if (enabled) onToggle else null,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
              equipment.name,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
          )
          Text(
              equipment.group,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Checkbox(checked = selected, onCheckedChange = if (enabled) ({ onToggle() }) else null)
      }
    }
  }
}

@Composable
private fun LoadingContent(minHeight: Dp = 0.dp) {
  Box(
      modifier =
          if (minHeight == 0.dp) Modifier.fillMaxSize()
          else Modifier.fillMaxWidth().heightIn(min = minHeight),
      contentAlignment = Alignment.Center,
  ) {
    CircularProgressIndicator()
  }
}

@Composable
private fun GymAvailabilityPreviewDialog(
    loading: Boolean,
    exercises: List<com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity>?,
    onDismiss: () -> Unit,
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("Доступные упражнения") },
      text = {
        when {
          loading -> LoadingContent(minHeight = 120.dp)
          exercises.isNullOrEmpty() -> Text("С этим оснащением пока нет доступных упражнений.")
          else ->
              LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(exercises, key = { it.id }) { exercise ->
                  Text(exercise.name, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
              }
        }
      },
      confirmButton = { TextButton(onClick = onDismiss) { Text("Вернуться к черновику") } },
  )
}

@Composable
private fun GymEditorLoadError(message: String, onBack: () -> Unit) {
  Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
          message,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onBackground,
          textAlign = TextAlign.Center,
      )
      Text(
          "Вернитесь к списку и попробуйте снова.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
      )
      PillButton(text = "Вернуться", onClick = onBack)
    }
  }
}

@Composable
private fun SaveConflictDialog(conflict: GymConfigurationConflict, onDismiss: () -> Unit) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("Состав зала нельзя изменить") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
              "Изменение сделает упражнения недоступными в связанных программах или активной тренировке."
          )
          if (conflict.routines.isNotEmpty())
              ConflictSection("Затронутые связи", conflict.routines.map { it.name })
          if (conflict.exercises.isNotEmpty())
              ConflictSection("Упражнения", conflict.exercises.map { it.name })
        }
      },
      confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } },
  )
}

@Composable
private fun DeleteConflictDialog(routines: List<GymRoutineReference>, onDismiss: () -> Unit) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("Зал сейчас используется") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
              "Удаление заблокировано, пока зал выбран в программе или сохранён в активной тренировке."
          )
          ConflictSection("Затронутые связи", routines.map { it.name })
        }
      },
      confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } },
  )
}

@Composable
private fun ConflictSection(title: String, values: List<String>) {
  Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
  values.distinct().forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
}
