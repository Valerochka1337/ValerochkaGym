package com.valerochka1337.valerochkagym.ui.gyms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.domain.GymConfiguration
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.components.CircleIconButton
import com.valerochka1337.valerochkagym.ui.components.ConfigurationCloneDialog
import com.valerochka1337.valerochkagym.ui.components.ConfigurationCloneTarget
import com.valerochka1337.valerochkagym.ui.components.ConfigurationCloneViewModel
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.navigation.GymWindowWidthClass

@Composable
fun GymDetailScreen(
    onBack: () -> Unit,
    onEditGym: (String) -> Unit,
    onExerciseClick: (Long) -> Unit,
    windowWidthClass: GymWindowWidthClass = GymWindowWidthClass.Compact,
    modifier: Modifier = Modifier,
    viewModel: GymDetailViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val cloneViewModel: ConfigurationCloneViewModel = hiltViewModel()
  val cloneState by cloneViewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val haptics = gymHaptics()
  LaunchedEffect(Unit) { cloneViewModel.messages.collect(snackbarHostState::showSnackbar) }
  GlowBackground(modifier = modifier) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        GymDetailHeader(
            gym = state.gym,
            onBack = onBack,
            onEdit = {
              state.gym?.let {
                haptics.tap()
                onEditGym(it.id)
              }
            },
            onCopy = {
              state.gym?.let {
                haptics.tap()
                cloneViewModel.open(ConfigurationCloneTarget.Gym(it.id, it.name))
              }
            },
        )
        GymDetailBody(
            state = state,
            onCopyGym = {
              haptics.tap()
              state.gym
                  ?.takeIf { gym -> gym.id == it }
                  ?.let { gym ->
                    cloneViewModel.open(ConfigurationCloneTarget.Gym(gym.id, gym.name))
                  }
            },
            onExerciseClick = {
              haptics.tap()
              onExerciseClick(it)
            },
            onRetry = {
              haptics.tap()
              viewModel.retry()
            },
            onBack = onBack,
            windowWidthClass = windowWidthClass,
        )
      }
      SnackbarHost(
          hostState = snackbarHostState,
          modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
      )
    }
  }
  ConfigurationCloneDialog(
      state = cloneState,
      onNameChange = cloneViewModel::setName,
      onSave = cloneViewModel::save,
      onDismiss = cloneViewModel::dismiss,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GymDetailHeader(
    gym: GymConfiguration?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
) {
  var menuExpanded by remember { mutableStateOf(false) }
  TopAppBar(
      navigationIcon = {
        IconButton(onClick = onBack) {
          Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
        }
      },
      title = { Text(gym?.name ?: "Зал", maxLines = 1, overflow = TextOverflow.Ellipsis) },
      actions = {
        if (gym?.origin != "STANDARD" && gym != null) {
          CircleIconButton(
              icon = Icons.Rounded.Edit,
              contentDescription = "Редактировать зал",
              onClick = onEdit,
          )
          Box {
            IconButton(onClick = { menuExpanded = true }) {
              Icon(Icons.Rounded.MoreVert, contentDescription = "Меню зала")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
              DropdownMenuItem(
                  text = { Text("Клонировать") },
                  onClick = {
                    menuExpanded = false
                    onCopy()
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
internal fun GymDetailBody(
    state: GymDetailUiState,
    onCopyGym: (String) -> Unit,
    onExerciseClick: (Long) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    windowWidthClass: GymWindowWidthClass,
    modifier: Modifier = Modifier,
) {
  when {
    state.loading ->
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
    state.loadError ->
        GymDetailMessage(
            title = "Не удалось загрузить зал",
            description = "Проверьте подключение и попробуйте снова.",
            action = "Повторить",
            onAction = onRetry,
        )
    state.gym == null ->
        GymDetailMessage(
            title = "Зал не найден",
            description = "Возможно, он был удалён или больше недоступен.",
            action = "Вернуться",
            onAction = onBack,
        )
    else ->
        GymDetailContent(
            gym = state.gym,
            equipment = state.equipment,
            availableExercises = state.availableExercises,
            onCopyGym = onCopyGym,
            onExerciseClick = onExerciseClick,
            windowWidthClass = windowWidthClass,
            modifier = modifier,
        )
  }
}

@Composable
internal fun GymDetailContent(
    gym: GymConfiguration,
    equipment: List<com.valerochka1337.valerochkagym.data.db.EquipmentCatalog.Equipment>,
    availableExercises: List<ExerciseEntity>,
    onCopyGym: (String) -> Unit,
    onExerciseClick: (Long) -> Unit,
    windowWidthClass: GymWindowWidthClass,
    modifier: Modifier = Modifier,
) {
  val horizontalPadding = if (windowWidthClass == GymWindowWidthClass.Compact) 16.dp else 24.dp
  LazyColumn(
      modifier = modifier.fillMaxSize(),
      contentPadding =
          PaddingValues(
              start = horizontalPadding,
              end = horizontalPadding,
              top = 4.dp,
              bottom = 32.dp,
          ),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      GymCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Оснащение",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        EquipmentSummary(gym = gym, equipment = equipment)
      }
    }
    if (gym.origin == "STANDARD") {
      item {
        PillButton(
            text = "Клонировать",
            onClick = { onCopyGym(gym.id) },
            modifier = Modifier.fillMaxWidth(),
        )
      }
    }
    item {
      Text(
          text = "Доступные упражнения · ${availableExercises.size}",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onBackground,
          modifier = Modifier.padding(top = 4.dp),
      )
    }
    if (availableExercises.isEmpty()) {
      item {
        GymCard(modifier = Modifier.fillMaxWidth()) {
          Text(
              text = "С этим оснащением пока нет доступных упражнений.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      items(availableExercises, key = { it.id }) { exercise ->
        GymCard(
            modifier =
                Modifier.fillMaxWidth().semantics {
                  contentDescription = "Упражнение ${exercise.name}"
                },
            onClick = { onExerciseClick(exercise.id) },
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        ) {
          Text(
              text = exercise.name,
              modifier = Modifier.heightIn(min = 24.dp),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
          )
          Text(
              text = exercise.muscleGroup.displayName(),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun EquipmentSummary(
    gym: GymConfiguration,
    equipment: List<com.valerochka1337.valerochkagym.data.db.EquipmentCatalog.Equipment>,
) {
  when {
    !gym.inventoryConfigured ->
        Text(
            text = "Оснащение ещё не настроено. Доступность берётся из прежнего каталога зала.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    gym.equipmentIds.isEmpty() ->
        Text(
            text = "Оборудование не выбрано.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    equipment.isEmpty() ->
        Text(
            text = "Загружаем названия оборудования…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    else -> {
      var expanded by rememberSaveable(gym.id) { mutableStateOf(false) }
      val haptics = gymHaptics()
      Text(
          text = "${gym.equipmentIds.size} ${equipmentCountWord(gym.equipmentIds.size)}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 6.dp),
      )
      Text(
          text = equipment.take(3).joinToString { it.name },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 2.dp),
      )
      TextButton(
          onClick = {
            haptics.tap()
            expanded = !expanded
          },
      ) {
        Text(if (expanded) "Скрыть оснащение" else "Показать всё")
      }
      if (expanded) {
        equipment
            .groupBy { it.group }
            .toSortedMap()
            .forEach { (group, entries) ->
              Text(
                  text = group,
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(top = 6.dp),
              )
              Text(
                  text = entries.joinToString { it.name },
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
              )
            }
      }
    }
  }
}

@Composable
private fun GymDetailMessage(
    title: String,
    description: String,
    action: String,
    onAction: () -> Unit,
) {
  Box(
      modifier = Modifier.fillMaxSize().padding(32.dp),
      contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(
          imageVector = Icons.Rounded.FitnessCenter,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
      )
      Text(
          text = title,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 16.dp),
      )
      Text(
          text = description,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
      )
      TextButton(onClick = onAction) { Text(action) }
    }
  }
}

private fun equipmentCountWord(count: Int): String {
  val lastTwo = count % 100
  val last = count % 10
  return when {
    lastTwo in 11..14 -> "единиц оборудования"
    last == 1 -> "единица оборудования"
    last in 2..4 -> "единицы оборудования"
    else -> "единиц оборудования"
  }
}
