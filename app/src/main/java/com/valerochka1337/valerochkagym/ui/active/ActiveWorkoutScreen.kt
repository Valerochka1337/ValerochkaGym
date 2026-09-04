package com.valerochka1337.valerochkagym.ui.active

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.domain.currentFocus
import com.valerochka1337.valerochkagym.service.RestTimerState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateConnectionState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateDevice
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateReading
import com.valerochka1337.valerochkagym.ui.common.formatRestClock
import com.valerochka1337.valerochkagym.ui.components.DragHandle
import com.valerochka1337.valerochkagym.ui.components.ExerciseAvatar
import com.valerochka1337.valerochkagym.ui.components.FadeInContent
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.GymCardShape
import com.valerochka1337.valerochkagym.ui.components.NumberField
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.components.rememberGymReorderableLazyListState
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.theme.GymMotion
import kotlinx.coroutines.flow.StateFlow
import sh.calvin.reorderable.ReorderableItem

/** Крупный шаг веса (обычный тап), кг. */
private const val WEIGHT_STEP = 2.5

/** Точный шаг веса (долгое нажатие), кг. */
private const val WEIGHT_FINE_STEP = 0.5

private const val SPEED_STEP = 0.5
private const val INCLINE_STEP = 0.5
private const val DURATION_STEP = 15
private const val REPS_STEP = 1

/** Шаг правки таймера отдыха на пилюле, сек. */
private const val REST_TIMER_STEP = 15

/**
 * Экран активной тренировки («вариант B» — фокус на текущем подходе). Тренировка уже создана (старт
 * на вкладке «Тренировки»); состояние приходит реактивно из [ActiveWorkoutViewModel]. [onFinished]
 * ведёт на итоги, [onDiscarded] — на главную, [onAddExercise] открывает библиотеку.
 */
@Composable
fun ActiveWorkoutScreen(
    onFinished: (workoutId: String) -> Unit,
    onDiscarded: () -> Unit,
    onNavigateBack: () -> Unit,
    onAddExercise: () -> Unit,
    onExerciseClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActiveWorkoutViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }
  var showNearbyDevicesRationale by rememberSaveable { mutableStateOf(false) }
  val nearbyDevicesPermission =
      rememberLauncherForActivityResult(
          ActivityResultContracts.RequestMultiplePermissions(),
      ) {
        // Даже при отказе запускаем монитор: он переведёт плитку в PermissionRequired с понятным
        // объяснением, а не оставит кнопку в неопределённом исходном состоянии.
        viewModel.scanHeartRate()
      }

  fun startHeartRateSearch() {
    val hasScan =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED
    val hasConnect =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    if (hasScan && hasConnect) {
      viewModel.scanHeartRate()
    } else {
      showNearbyDevicesRationale = true
    }
  }

  LaunchedEffect(Unit) {
    viewModel.events.collect { event ->
      when (event) {
        is ActiveWorkoutEvent.NavigateToSummary -> onFinished(event.workoutId)
        ActiveWorkoutEvent.NavigateHome -> onDiscarded()
        is ActiveWorkoutEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
      }
    }
  }

  KeepScreenOn()

  val setActions =
      remember(viewModel) {
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
    Box(Modifier.fillMaxSize()) {
      val workout = state.workout
      when {
        workout != null ->
            FadeInContent {
              ActiveWorkoutContent(
                  state = state,
                  elapsedSeconds = viewModel.elapsedSeconds,
                  restTimer = viewModel.restTimer,
                  heartRateState = viewModel.heartRateState,
                  heartRateReading = viewModel.heartRateReading,
                  setActions = setActions,
                  onDeleteExercise = viewModel::deleteExercise,
                  onReorderExercises = viewModel::reorderExercises,
                  onAddExercise = onAddExercise,
                  onExerciseClick = onExerciseClick,
                  onFinish = viewModel::finish,
                  onDiscard = viewModel::discard,
                  onAddRestSeconds = viewModel::addRestSeconds,
                  onSkipRest = viewModel::skipRest,
                  onScanHeartRate = ::startHeartRateSearch,
                  onConnectHeartRate = viewModel::connectHeartRate,
                  onCancelHeartRateSelection = viewModel::cancelHeartRateSelection,
              )
            }

        state.loading ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator()
            }

        else -> FadeInContent { NoActiveWorkout(onNavigateBack = onNavigateBack) }
      }
      SnackbarHost(
          hostState = snackbarHostState,
          modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
      )
    }
  }

  if (showNearbyDevicesRationale) {
    AlertDialog(
        onDismissRequest = { showNearbyDevicesRationale = false },
        title = { Text("Подключить датчик пульса?") },
        text = {
          Text(
              "Доступ к устройствам поблизости нужен только для поиска и подключения " +
                  "Bluetooth-датчика. Тренировка полностью работает и без него.",
          )
        },
        confirmButton = {
          TextButton(
              onClick = {
                showNearbyDevicesRationale = false
                nearbyDevicesPermission.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    ),
                )
              }
          ) {
            Text("Продолжить")
          }
        },
        dismissButton = {
          TextButton(onClick = { showNearbyDevicesRationale = false }) { Text("Не сейчас") }
        },
    )
  }
}

/** Колбэки правок подходов, прокинутые от [ActiveWorkoutViewModel] в карточки текущего подхода. */
internal class SetActions(
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
internal fun ActiveWorkoutContent(
    state: ActiveWorkoutUiState,
    elapsedSeconds: StateFlow<Long>,
    restTimer: StateFlow<RestTimerState?>,
    heartRateState: StateFlow<HeartRateConnectionState>,
    heartRateReading: StateFlow<HeartRateReading?>,
    setActions: SetActions,
    onDeleteExercise: (Long) -> Unit,
    onReorderExercises: (List<Long>) -> Unit,
    onAddExercise: () -> Unit,
    onExerciseClick: (Long) -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    onAddRestSeconds: (Int) -> Unit,
    onSkipRest: () -> Unit,
    onScanHeartRate: () -> Unit,
    onConnectHeartRate: (HeartRateDevice) -> Unit,
    onCancelHeartRateSelection: () -> Unit,
) {
  val workout = state.workout ?: return
  val roomExercises = workout.exercises
  val roomOrder = roomExercises.map { it.workoutExercise.id }
  var localOrder by remember(workout.workout.id) { mutableStateOf(roomOrder) }
  var draggingExerciseId by remember(workout.workout.id) { mutableStateOf<Long?>(null) }
  var pendingPersistedOrder by remember(workout.workout.id) { mutableStateOf<List<Long>?>(null) }
  val haptics = gymHaptics()

  // Room обновляет дерево после каждого изменения подхода. Пока идёт drag или ждём его единую
  // запись в БД, сохраняем локальный порядок и лишь подмешиваем появившиеся/исчезнувшие id.
  LaunchedEffect(roomOrder, draggingExerciseId, pendingPersistedOrder) {
    val pending = pendingPersistedOrder
    when {
      pending != null && roomOrder.filter { it in pending } == pending -> {
        pendingPersistedOrder = null
        localOrder = mergeExerciseOrder(pending, roomOrder)
      }

      draggingExerciseId != null || pending != null -> {
        localOrder = mergeExerciseOrder(localOrder, roomOrder)
      }

      else -> localOrder = roomOrder
    }
  }

  val exercisesById = roomExercises.associateBy { it.workoutExercise.id }
  val exercises = localOrder.mapNotNull(exercisesById::get)
  val lazyListState = rememberLazyListState()
  val reorderableLazyListState =
      rememberGymReorderableLazyListState(lazyListState) { from, to ->
        if (
            from.index in localOrder.indices &&
                to.index in localOrder.indices &&
                from.index != to.index
        ) {
          // Reorderable ждёт синхронного обновления backing list, иначе dragged item моргает.
          localOrder = localOrder.toMutableList().apply { add(to.index, removeAt(from.index)) }
          haptics.stepFrequent()
        }
      }

  fun moveByAccessibilityAction(fromIndex: Int, toIndex: Int) {
    if (
        fromIndex !in localOrder.indices || toIndex !in localOrder.indices || fromIndex == toIndex
    ) {
      return
    }
    val reordered = localOrder.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    localOrder = reordered
    pendingPersistedOrder = reordered
    haptics.stepFrequent()
    onReorderExercises(reordered)
  }

  // currentFocus — единое правило для экрана, уведомления и Wear. Передаём ему локальный
  // порядок, чтобы фокус немедленно следовал за карточкой ещё до записи перестановки в Room.
  val currentFocus = workout.copy(exercises = exercises).currentFocus()
  val activeSetId = currentFocus?.set?.id
  val currentIndex =
      activeSetId?.let { setId ->
        exercises.indexOfFirst { exercise -> exercise.sets.any { it.id == setId } }
      } ?: -1
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
        heartRateState = heartRateState,
        heartRateReading = heartRateReading,
        onScanHeartRate = onScanHeartRate,
        onConnectHeartRate = onConnectHeartRate,
        onCancelHeartRateSelection = onCancelHeartRateSelection,
        onFinish = { showFinishDialog = true },
        onDiscard = { showDiscardDialog = true },
    )

    LazyColumn(
        modifier = Modifier.weight(1f),
        state = lazyListState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      items(exercises, key = { it.workoutExercise.id }) { exercise ->
        val index = exercises.indexOf(exercise)
        ReorderableItem(
            state = reorderableLazyListState,
            key = exercise.workoutExercise.id,
        ) { isDragging ->
          val reorderableItemScope = this
          val moveActions = buildList {
            if (index > 0) {
              add(
                  CustomAccessibilityAction("Переместить выше") {
                    moveByAccessibilityAction(index, index - 1)
                    true
                  },
              )
            }
            if (index < exercises.lastIndex) {
              add(
                  CustomAccessibilityAction("Переместить ниже") {
                    moveByAccessibilityAction(index, index + 1)
                    true
                  },
              )
            }
          }
          ExerciseSection(
              modifier =
                  Modifier.animateItem(placementSpec = GymMotion.spatialDefault())
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
              previous = state.previousByExercise[exercise.exercise.id].orEmpty(),
              actions = setActions,
              activeSetId = activeSetId,
              dragHandle = {
                DragHandle(
                    reorderableItemScope = reorderableItemScope,
                    onDragStarted = {
                      draggingExerciseId = exercise.workoutExercise.id
                      haptics.dragStart()
                    },
                    onDragStopped = {
                      haptics.dragEnd()
                      draggingExerciseId = null
                      if (localOrder != roomOrder) {
                        pendingPersistedOrder = localOrder
                        onReorderExercises(localOrder)
                      }
                    },
                )
              },
              onDeleteExercise = { pendingDeleteExerciseId = exercise.workoutExercise.id },
              onExerciseClick = { onExerciseClick(exercise.exercise.id) },
          )
        }
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
        modifier =
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      RestTimerPill(
          restTimer = restTimer,
          heartRateReading = heartRateReading,
          onAddRestSeconds = onAddRestSeconds,
          onSkipRest = onSkipRest,
      )
      CurrentSetPrimaryAction(
          restTimer = restTimer,
          activeSetId = activeSetId,
          onComplete = setActions.complete,
      )
    }
  }

  if (showFinishDialog) {
    ConfirmDialog(
        title = "Завершить тренировку?",
        text = "Пустые невыполненные подходы будут отброшены, тренировка попадёт в историю.",
        confirmText = "Завершить",
        onConfirm = {
          haptics.success()
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
          haptics.reject()
          showDiscardDialog = false
          onDiscard()
        },
        onDismiss = { showDiscardDialog = false },
    )
  }

  val deleteExerciseId = pendingDeleteExerciseId
  if (deleteExerciseId != null) {
    val name =
        exercises
            .firstOrNull { it.workoutExercise.id == deleteExerciseId }
            ?.exercise
            ?.name
            .orEmpty()
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

/** Сохраняет пользовательский порядок известных id и добавляет новые строки в порядок Room. */
private fun mergeExerciseOrder(localOrder: List<Long>, roomOrder: List<Long>): List<Long> {
  val roomIds = roomOrder.toSet()
  return localOrder.filter { it in roomIds } + roomOrder.filterNot { it in localOrder }
}

/** Кликабельный live-пульс: занимает одну строку с названием тренировки. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeartRateBubble(
    state: StateFlow<HeartRateConnectionState>,
    reading: StateFlow<HeartRateReading?>,
    onScan: () -> Unit,
    onSelectDevice: (HeartRateDevice) -> Unit,
    onDismissSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val connectionState by state.collectAsStateWithLifecycle()
  val liveReading by reading.collectAsStateWithLifecycle()
  val haptics = gymHaptics()
  val bpm = liveReading?.bpm
  val description =
      bpm?.let { "Пульс $it ударов в минуту. Нажмите, чтобы сменить датчик" }
          ?: "Подключить пульсометр"

  GymCard(
      modifier =
          modifier.animateContentSize(GymMotion.spatialFast()).semantics {
            contentDescription = description
          },
      shape = CircleShape,
      onClick = {
        haptics.tap()
        onScan()
      },
      contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
          imageVector = Icons.Default.Favorite,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
      )
      Spacer(Modifier.width(5.dp))
      Text(
          text = bpm?.toString() ?: "—",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color =
              if (bpm != null) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
      )
    }
  }

  val selection = connectionState as? HeartRateConnectionState.Selection
  if (selection != null) {
    ModalBottomSheet(onDismissRequest = onDismissSelection) {
      Column(
          modifier =
              Modifier.fillMaxWidth()
                  .verticalScroll(rememberScrollState())
                  .padding(horizontal = 24.dp)
                  .padding(bottom = 32.dp),
      ) {
        Text(
            text = "Выберите пульсометр",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text =
                "Найдено несколько источников Heart Rate. Выберите тот, который сейчас передаёт пульс.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        selection.devices.forEach { device ->
          TextButton(
              onClick = {
                haptics.tap()
                onSelectDevice(device)
              },
              modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.fillMaxWidth()) {
              Text(
                  text = device.label,
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                  text = "Сигнал ${device.rssi} dBm",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Пилюля отдыха внизу экрана. В таймерном режиме показывает «−15с / M:SS / +15с», а в режиме пульса
 * — текущий BPM и порог; тап по центру всегда пропускает отдых.
 */
@Composable
private fun RestTimerPill(
    restTimer: StateFlow<RestTimerState?>,
    heartRateReading: StateFlow<HeartRateReading?>,
    onAddRestSeconds: (Int) -> Unit,
    onSkipRest: () -> Unit,
) {
  val rest by restTimer.collectAsStateWithLifecycle()
  val reading by heartRateReading.collectAsStateWithLifecycle()
  AnimatedVisibility(
      visible = rest != null,
      enter =
          slideInVertically(GymMotion.spatialDefault()) { it } + fadeIn(GymMotion.effectsDefault()),
      exit =
          slideOutVertically(GymMotion.spatialDefault()) { it } +
              fadeOut(GymMotion.effectsDefault()),
  ) {
    // Пока идёт exit-анимация, `rest` уже null — держим последнее ненулевое значение,
    // чтобы контент пилюли не исчезал мгновенно.
    var lastState by remember { mutableStateOf(rest) }
    rest?.let { lastState = it }
    val state = lastState ?: return@AnimatedVisibility
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(bottom = 12.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      val haptics = gymHaptics()
      when (state) {
        is RestTimerState.Timed -> {
          RestPillSide(symbol = "−15с", contentDescription = "убавить отдых") {
            haptics.step()
            onAddRestSeconds(-REST_TIMER_STEP)
          }
          SkipRestButton(onSkipRest) {
            Text(
                text = "⏱ ${formatRestClock(state.remainingSec)}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
          }
          RestPillSide(symbol = "+15с", contentDescription = "прибавить отдых") {
            haptics.step()
            onAddRestSeconds(REST_TIMER_STEP)
          }
        }

        is RestTimerState.HeartRate ->
            SkipRestButton(onSkipRest) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.background,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "${reading?.bpm ?: "—"} · ≤ ${state.thresholdBpm} BPM",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
              }
            }
      }
    }
  }
}

@Composable
private fun RowScope.SkipRestButton(onSkipRest: () -> Unit, content: @Composable () -> Unit) {
  val haptics = gymHaptics()
  Row(
      modifier =
          Modifier.weight(1f)
              .fillMaxHeight()
              .clickable(
                  role = Role.Button,
                  onClick = {
                    haptics.tap()
                    onSkipRest()
                  },
              )
              .semantics { contentDescription = "Пропустить отдых" },
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    content()
  }
}

@Composable
private fun RestPillSide(
    symbol: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
  Box(
      modifier =
          Modifier.fillMaxHeight()
              .clip(CircleShape)
              .clickable(role = Role.Button, onClick = onClick)
              .semantics { this.contentDescription = contentDescription }
              .padding(horizontal = 20.dp),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        text = symbol,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimary,
    )
  }
}

@Composable
private fun ActiveWorkoutHeader(
    name: String,
    elapsedSeconds: StateFlow<Long>,
    currentNumber: Int,
    total: Int,
    heartRateState: StateFlow<HeartRateConnectionState>,
    heartRateReading: StateFlow<HeartRateReading?>,
    onScanHeartRate: () -> Unit,
    onConnectHeartRate: (HeartRateDevice) -> Unit,
    onCancelHeartRateSelection: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
) {
  // Собираем таймер только здесь, чтобы посекундный тик не рекомпозил список подходов.
  val elapsed by elapsedSeconds.collectAsStateWithLifecycle()
  var menuExpanded by remember { mutableStateOf(false) }
  Column(
      modifier =
          Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
          text = name,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onBackground,
          modifier = Modifier.weight(1f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.width(12.dp))
      HeartRateBubble(
          state = heartRateState,
          reading = heartRateReading,
          onScan = onScanHeartRate,
          onSelectDevice = onConnectHeartRate,
          onDismissSelection = onCancelHeartRateSelection,
      )
      Box {
        IconButton(onClick = { menuExpanded = true }) {
          Icon(
              imageVector = Icons.Default.MoreVert,
              contentDescription = "Действия тренировки",
          )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
          DropdownMenuItem(
              text = { Text("Завершить тренировку") },
              onClick = {
                menuExpanded = false
                onFinish()
              },
          )
          DropdownMenuItem(
              text = {
                Text(
                    text = "Отменить тренировку",
                    color = MaterialTheme.colorScheme.error,
                )
              },
              onClick = {
                menuExpanded = false
                onDiscard()
              },
          )
        }
      }
    }
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExerciseSection(
    exercise: WorkoutExerciseWithSets,
    previous: String,
    actions: SetActions,
    activeSetId: Long?,
    dragHandle: @Composable () -> Unit,
    onDeleteExercise: () -> Unit,
    onExerciseClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val type = exercise.exercise.type
  val haptics = gymHaptics()

  // Когда текущий подход схлопывается в пилюлю, высота секции меняется плавно (expressive-спек).
  Column(
      modifier = modifier.fillMaxWidth().animateContentSize(GymMotion.spatialDefault()),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Row(
          modifier =
              Modifier.weight(1f).heightIn(min = 48.dp).clickable {
                haptics.tap()
                onExerciseClick()
              },
          verticalAlignment = Alignment.CenterVertically,
      ) {
        ExerciseAvatar(exercise = exercise.exercise)
        Spacer(Modifier.width(12.dp))
        Column {
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
      }
      dragHandle()
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
        set.id == activeSetId ->
            CurrentSetCard(
                set = set,
                type = type,
                actions = actions,
            )

        set.isCompleted -> {
          val haptics = gymHaptics()
          CompletedSetPill(
              set = set,
              type = type,
              onClick = {
                haptics.toggle(on = false)
                actions.uncomplete(set.id)
              },
          )
        }

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
  }
}

/** Контекстная закреплённая кнопка: во время отдыха её место занимают controls таймера. */
@Composable
private fun CurrentSetPrimaryAction(
    restTimer: StateFlow<RestTimerState?>,
    activeSetId: Long?,
    onComplete: (Long) -> Unit,
) {
  val rest by restTimer.collectAsStateWithLifecycle()
  val setId = activeSetId
  if (rest == null && setId != null) {
    val haptics = gymHaptics()
    PillButton(
        text = "Подход выполнен",
        onClick = {
          haptics.confirm()
          onComplete(setId)
        },
        leadingIcon = Icons.Default.Check,
        modifier = Modifier.fillMaxWidth(),
    )
  }
}

/**
 * Пара кнопок ± вокруг числового поля. Обычный тап — [onStepDown]/[onStepUp]; долгое нажатие, если
 * задано, — [onFineDown]/[onFineUp] (используется для точного шага веса ±0.5).
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
  // Тактильный шаг на каждом ±: long-press (точная подстройка) отбивается мягче — это
  // единственное, что отличает его от обычного тапа до того, как изменится число.
  val haptics = gymHaptics()
  Box(
      modifier =
          Modifier.size(48.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.surfaceContainerHighest)
              .combinedClickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = ripple(),
                  role = Role.Button,
                  onClick = {
                    haptics.step()
                    onClick()
                  },
                  onLongClick =
                      onLongClick?.let { fine ->
                        {
                          haptics.stepFrequent()
                          fine()
                        }
                      },
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CompletedSetPill(
    set: WorkoutSetEntity,
    type: ExerciseType,
    onClick: () -> Unit,
) {
  // Короткий scale-панч на галочке при появлении пилюли (подход только что отмечен выполненным).
  val punchSpec = GymMotion.spatialDefault<Float>()
  val checkScale = remember { Animatable(0.6f) }
  LaunchedEffect(Unit) { checkScale.animateTo(1f, punchSpec) }
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
            modifier =
                Modifier.size(18.dp).graphicsLayer {
                  scaleX = checkScale.value
                  scaleY = checkScale.value
                },
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
      modifier =
          Modifier.fillMaxWidth()
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
      modifier = Modifier.fillMaxSize().padding(24.dp),
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
              color =
                  if (destructive) {
                    MaterialTheme.colorScheme.error
                  } else {
                    MaterialTheme.colorScheme.primary
                  },
          )
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
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

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
      is Activity -> this
      is ContextWrapper -> baseContext.findActivity()
      else -> null
    }

/** Краткое представление значений подхода для свёрнутых пилюль. */
private fun formatSetValues(set: WorkoutSetEntity, type: ExerciseType): String =
    when (type) {
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

      ExerciseType.CARDIO ->
          buildList {
                set.speedKmh?.let { add("${it.toField()} км/ч") }
                set.inclinePct?.let { add("${it.toField()}%") }
                set.durationSec?.let { add("${it / 60} мин") }
              }
              .joinToString(" · ")
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

private fun Double?.toField(): String =
    when {
      this == null -> ""
      this % 1.0 == 0.0 -> toInt().toString()
      else -> toString()
    }

private fun Int?.toField(): String = this?.toString() ?: ""
