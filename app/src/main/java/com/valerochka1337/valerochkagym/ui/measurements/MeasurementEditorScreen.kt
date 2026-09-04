package com.valerochka1337.valerochkagym.ui.measurements

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegment
import com.valerochka1337.valerochkagym.ui.components.CircleIconButton
import com.valerochka1337.valerochkagym.ui.components.FadeInContent
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.NumberField
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Полноэкранная форма создания/правки локального замера. */
@Composable
fun MeasurementEditorScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MeasurementEditorViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val haptics = gymHaptics()
  val context = LocalContext.current
  var showDatePicker by remember { mutableStateOf(false) }
  var showDeleteDialog by remember { mutableStateOf(false) }
  var showImportSources by remember { mutableStateOf(false) }
  var pendingCameraFile by remember { mutableStateOf<File?>(null) }
  var showFullReport by remember { mutableStateOf(false) }
  var showSegments by remember { mutableStateOf(false) }
  var showCircumferences by remember { mutableStateOf(false) }

  val galleryLauncher =
      rememberLauncherForActivityResult(
          contract = ActivityResultContracts.PickVisualMedia(),
      ) { uri: Uri? ->
        if (uri != null) viewModel.scanInBody(uri)
      }
  val cameraLauncher =
      rememberLauncherForActivityResult(
          contract = ActivityResultContracts.TakePicture(),
      ) { captured ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (captured && file != null) {
          viewModel.scanInBody(
              uri =
                  FileProvider.getUriForFile(context, "${context.packageName}.inbody-import", file),
              temporaryCameraFile = file,
          )
        } else {
          file?.delete()
        }
      }

  LaunchedEffect(Unit) { viewModel.finished.collect { onBack() } }

  GlowBackground(modifier = modifier) {
    Column(modifier = Modifier.fillMaxSize()) {
      MeasurementEditorHeader(
          isNew = state.isNew,
          onBack = onBack,
          onDelete =
              if (state.isNew || state.isLoading || state.isBusy) {
                null
              } else {
                { showDeleteDialog = true }
              },
      )
      if (state.isLoading) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
          CircularWavyProgressIndicator()
          Spacer(Modifier.height(12.dp))
          Text(
              text = "Загружаем замер…",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        return@Column
      }

      Column(
          modifier =
              Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        InBodyImportCard(
            state = state,
            onScan = {
              haptics.tap()
              showImportSources = true
            },
            onOpenSettings = {
              haptics.tap()
              onOpenSettings()
            },
        )
        DateCard(
            measuredAt = state.measuredAt,
            enabled = !state.isBusy,
            onPickDate = {
              haptics.tap()
              showDatePicker = true
            },
        )
        InBodyCard(state, viewModel)
        OptionalEditorSection(
            title = "Полный отчёт InBody",
            filledCount = state.fullReportFilledCount(),
            totalCount = 8,
            expanded = showFullReport,
            onToggle = { showFullReport = !showFullReport },
        )
        if (showFullReport) FullInBodyReportCard(state, viewModel)
        OptionalEditorSection(
            title = "Сегментный анализ",
            filledCount = state.segmentFilledCount(),
            totalCount = InBodySegment.entries.size * 4,
            expanded = showSegments,
            onToggle = { showSegments = !showSegments },
        )
        if (showSegments) SegmentalInBodyCard(state, viewModel)
        OptionalEditorSection(
            title = "Обхваты",
            filledCount = state.circumferenceFilledCount(),
            totalCount = 5,
            expanded = showCircumferences,
            onToggle = { showCircumferences = !showCircumferences },
        )
        if (showCircumferences) CircumferencesCard(state, viewModel)
        if (!state.isNew) {
          GymCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Google Sheets",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    "Экспорт append-only: правки и удаление этого локального замера не меняют уже добавленную строку в таблице.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Spacer(Modifier.height(12.dp))
      }
      MeasurementSaveBar(
          state = state,
          onSave = {
            haptics.confirm()
            viewModel.save()
          },
      )
    }
  }

  if (showImportSources) {
    InBodyImportSourceSheet(
        state = state,
        onTakePhoto = {
          showImportSources = false
          val directory = File(context.cacheDir, "inbody_imports").apply { mkdirs() }
          val file = File(directory, "inbody-${UUID.randomUUID()}.jpg")
          pendingCameraFile = file
          val uri =
              FileProvider.getUriForFile(context, "${context.packageName}.inbody-import", file)
          cameraLauncher.launch(uri)
        },
        onPickGallery = {
          showImportSources = false
          galleryLauncher.launch(
              PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
          )
        },
        onOpenSettings = {
          showImportSources = false
          onOpenSettings()
        },
        onDismiss = { showImportSources = false },
    )
  }

  if (showDatePicker) {
    MeasurementDatePicker(
        measuredAt = state.measuredAt,
        onConfirm = { utcMillis ->
          viewModel.setDateFromUtcMillis(utcMillis)
          showDatePicker = false
        },
        onDismiss = { showDatePicker = false },
    )
  }

  if (showDeleteDialog) {
    AlertDialog(
        onDismissRequest = { showDeleteDialog = false },
        title = { Text("Удалить замер?") },
        text = {
          Text(
              "Локальный замер будет удалён. Уже выгруженная строка в Google Sheets останется без изменений.",
          )
        },
        confirmButton = {
          TextButton(
              onClick = {
                haptics.reject()
                showDeleteDialog = false
                viewModel.delete()
              }
          ) {
            Text("Удалить", color = MaterialTheme.colorScheme.error)
          }
        },
        dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Отмена") } },
    )
  }
}

@Composable
private fun OptionalEditorSection(
    title: String,
    filledCount: Int,
    totalCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
  GymCard(
      modifier = Modifier.fillMaxWidth(),
      onClick = onToggle,
      contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text =
                if (filledCount == 0) {
                  "Необязательно · не заполнено"
                } else {
                  "Заполнено $filledCount из $totalCount"
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Icon(
          imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
          contentDescription = if (expanded) "Свернуть" else "Развернуть",
      )
    }
  }
}

@Composable
private fun MeasurementSaveBar(
    state: MeasurementEditorUiState,
    onSave: () -> Unit,
) {
  Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
  ) {
    PillButton(
        text =
            when {
              state.isSaving -> "Сохраняю…"
              state.isNew -> "Сохранить замер"
              else -> "Сохранить изменения"
            },
        onClick = onSave,
        enabled = state.canSave && !state.isBusy,
        modifier = Modifier.fillMaxWidth(),
    )
    state.saveError?.let { error ->
      Spacer(Modifier.height(4.dp))
      Text(
          text = error,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
      )
    }
    if (!state.canSave) {
      Spacer(Modifier.height(4.dp))
      Text(
          text = "Укажите хотя бы один показатель.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun MeasurementEditorUiState.fullReportFilledCount(): Int =
    listOf(
            inBodyScore,
            totalBodyWaterLiters,
            proteinKg,
            mineralsKg,
            bodyMassIndex,
            fatFreeMassKg,
            basalMetabolicRateKcal,
            recommendedCalorieIntakeKcal,
        )
        .count(String::isNotBlank)

private fun MeasurementEditorUiState.segmentFilledCount(): Int =
    segments.values.sumOf { input ->
      listOf(input.leanMassKg, input.leanPercentage, input.fatMassKg, input.fatPercentage)
          .count(String::isNotBlank)
    }

private fun MeasurementEditorUiState.circumferenceFilledCount(): Int =
    listOf(
            waistCm,
            chestCm,
            hipsCm,
            rightRelaxedArmCm,
            rightThighCm,
        )
        .count(String::isNotBlank)

@Composable
private fun MeasurementEditorHeader(
    isNew: Boolean,
    onBack: () -> Unit,
    onDelete: (() -> Unit)?,
) {
  Row(
      modifier =
          Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    CircleIconButton(
        icon = Icons.AutoMirrored.Rounded.ArrowBack,
        contentDescription = "Назад",
        onClick = onBack,
    )
    Spacer(Modifier.width(8.dp))
    Text(
        text = if (isNew) "Новый замер" else "Замер",
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.weight(1f),
    )
    onDelete?.let {
      CircleIconButton(
          icon = Icons.Rounded.Delete,
          contentDescription = "Удалить замер",
          onClick = it,
      )
    }
  }
}

@Composable
private fun DateCard(measuredAt: Long, enabled: Boolean, onPickDate: () -> Unit) {
  GymCard(modifier = Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
          imageVector = Icons.Rounded.CalendarMonth,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.width(10.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
            text = "Дата замера",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = formatMeasurementDate(measuredAt, java.time.ZoneId.systemDefault()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      OutlinedButton(onClick = onPickDate, enabled = enabled) { Text("Изменить") }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InBodyImportCard(
    state: MeasurementEditorUiState,
    onScan: () -> Unit,
    onOpenSettings: () -> Unit,
) {
  GymCard(modifier = Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
          imageVector = Icons.Rounded.MonitorWeight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.width(10.dp))
      Text(
          text = "Сканировать лист InBody",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
      )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "Снимите лист целиком, без бликов.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.inBodyScanError?.let { error ->
      Spacer(Modifier.height(8.dp))
      Text(
          text = error,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
      )
      if (state.inBodyScanModelUnavailable) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenSettings) {
          Icon(Icons.Rounded.Settings, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text("Выбрать другую модель")
        }
      }
    }
    if (state.isScanningInBody) {
      Spacer(Modifier.height(14.dp))
      FadeInContent {
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularWavyProgressIndicator(
              modifier = Modifier.size(28.dp),
              color = MaterialTheme.colorScheme.primary,
              trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
              waveSpeed = WavyProgressIndicatorDefaults.CircularWavelength * 1.5f,
          )
          Spacer(Modifier.width(12.dp))
          Text(
              text = "Читаю черновик отчёта…",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    Spacer(Modifier.height(14.dp))
    if (state.isAiConfigured) {
      PillButton(
          text = if (state.isScanningInBody) "Распознаю…" else "Выбрать фото листа",
          onClick = onScan,
          enabled = !state.isBusy,
          leadingIcon = Icons.Rounded.PhotoCamera,
          modifier = Modifier.fillMaxWidth(),
      )
    } else {
      Text(
          text = "Настройте нейросеть в настройках.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(8.dp))
      OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
        Text("Открыть настройки")
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InBodyImportSourceSheet(
    state: MeasurementEditorUiState,
    onTakePhoto: () -> Unit,
    onPickGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
  ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState =
          rememberBottomSheetState(
              initialValue = SheetValue.Hidden,
              enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
          ),
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
          text = "Лист InBody",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
      )
      if (!state.isAiConfigured) {
        Text(
            text = "Настройте нейросеть в настройках.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
          Text("Открыть настройки")
        }
      } else {
        OutlinedButton(
            onClick = onTakePhoto,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Rounded.PhotoCamera, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text("Снять фото")
        }
        OutlinedButton(
            onClick = onPickGallery,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text("Выбрать из галереи")
        }
      }
      TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Отмена") }
    }
  }
}

@Composable
private fun InBodyCard(state: MeasurementEditorUiState, viewModel: MeasurementEditorViewModel) {
  val enabled = !state.isBusy
  GymCard(modifier = Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
          imageVector = Icons.Rounded.MonitorWeight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.width(10.dp))
      Text(
          text = "Состав тела · InBody",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
      )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Для сравнения делайте замеры на одном аппарате и в похожих условиях.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    NumberField(
        value = state.weightKg,
        onValueChange = viewModel::setWeightKg,
        modifier = Modifier.fillMaxWidth(),
        label = "Вес, кг",
        decimal = true,
        enabled = enabled,
    )
    Spacer(Modifier.height(8.dp))
    NumberField(
        value = state.skeletalMuscleMassKg,
        onValueChange = viewModel::setSkeletalMuscleMassKg,
        modifier = Modifier.fillMaxWidth(),
        label = "Масса скелетных мышц, кг",
        decimal = true,
        enabled = enabled,
    )
    Spacer(Modifier.height(8.dp))
    NumberField(
        value = state.bodyFatPercentage,
        onValueChange = viewModel::setBodyFatPercentage,
        modifier = Modifier.fillMaxWidth(),
        label = "Процент жира, %",
        decimal = true,
        enabled = enabled,
    )
    Spacer(Modifier.height(8.dp))
    NumberField(
        value = state.bodyFatMassKg,
        onValueChange = viewModel::setBodyFatMassKg,
        modifier = Modifier.fillMaxWidth(),
        label = "Масса жира по InBody, кг",
        decimal = true,
        enabled = enabled,
    )
    Spacer(Modifier.height(8.dp))
    NumberField(
        value = state.visceralFatLevel,
        onValueChange = viewModel::setVisceralFatLevel,
        modifier = Modifier.fillMaxWidth(),
        label = "Уровень висцерального жира",
        enabled = enabled,
    )
    Spacer(Modifier.height(8.dp))
    NumberField(
        value = state.waistHipRatio,
        onValueChange = viewModel::setWaistHipRatio,
        modifier = Modifier.fillMaxWidth(),
        label = "WHR из InBody",
        decimal = true,
        enabled = enabled,
    )
  }
}

@Composable
private fun FullInBodyReportCard(
    state: MeasurementEditorUiState,
    viewModel: MeasurementEditorViewModel,
) {
  val enabled = !state.isBusy
  GymCard(modifier = Modifier.fillMaxWidth()) {
    Text(
        text = "Полный отчёт InBody",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Фактические показатели с листа. Все поля можно исправить перед сохранением.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    NumberField(
        value = state.inBodyScore,
        onValueChange = viewModel::setInBodyScore,
        modifier = Modifier.fillMaxWidth(),
        label = "Балл InBody",
        enabled = enabled,
    )
    Spacer(Modifier.height(8.dp))
    NumberField(
        value = state.totalBodyWaterLiters,
        onValueChange = viewModel::setTotalBodyWaterLiters,
        modifier = Modifier.fillMaxWidth(),
        label = "Общая вода, л",
        decimal = true,
        enabled = enabled,
    )
    Spacer(Modifier.height(8.dp))
    NumberField(
        value = state.proteinKg,
        onValueChange = viewModel::setProteinKg,
        modifier = Modifier.fillMaxWidth(),
        label = "Белок, кг",
        decimal = true,
        enabled = enabled,
    )
    Spacer(Modifier.height(8.dp))
    NumberField(
        value = state.mineralsKg,
        onValueChange = viewModel::setMineralsKg,
        modifier = Modifier.fillMaxWidth(),
        label = "Минералы, кг",
        decimal = true,
        enabled = enabled,
    )
    Spacer(Modifier.height(8.dp))
    NumberField(
        value = state.bodyMassIndex,
        onValueChange = viewModel::setBodyMassIndex,
        modifier = Modifier.fillMaxWidth(),
        label = "ИМТ",
        decimal = true,
        enabled = enabled,
    )
    Spacer(Modifier.height(8.dp))
    NumberField(
        value = state.fatFreeMassKg,
        onValueChange = viewModel::setFatFreeMassKg,
        modifier = Modifier.fillMaxWidth(),
        label = "Безжировая масса, кг",
        decimal = true,
        enabled = enabled,
    )
    Spacer(Modifier.height(8.dp))
    NumberField(
        value = state.basalMetabolicRateKcal,
        onValueChange = viewModel::setBasalMetabolicRateKcal,
        modifier = Modifier.fillMaxWidth(),
        label = "Базовый обмен, ккал",
        enabled = enabled,
    )
    Spacer(Modifier.height(8.dp))
    NumberField(
        value = state.recommendedCalorieIntakeKcal,
        onValueChange = viewModel::setRecommendedCalorieIntakeKcal,
        modifier = Modifier.fillMaxWidth(),
        label = "Рекомендуемые калории, ккал",
        enabled = enabled,
    )
  }
}

@Composable
private fun SegmentalInBodyCard(
    state: MeasurementEditorUiState,
    viewModel: MeasurementEditorViewModel,
) {
  val enabled = !state.isBusy
  GymCard(modifier = Modifier.fillMaxWidth()) {
    Text(
        text = "Сегментный анализ InBody",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Проценты — напечатанное аппаратом отношение к эталону, а не диагноз.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    InBodySegment.entries.forEachIndexed { index, segment ->
      Spacer(Modifier.height(if (index == 0) 12.dp else 16.dp))
      SegmentInputs(
          segment = segment,
          input = state.segments[segment] ?: InBodySegmentInput(),
          enabled = enabled,
          onLeanMassChange = { viewModel.setSegmentLeanMassKg(segment, it) },
          onLeanPercentageChange = { viewModel.setSegmentLeanPercentage(segment, it) },
          onFatMassChange = { viewModel.setSegmentFatMassKg(segment, it) },
          onFatPercentageChange = { viewModel.setSegmentFatPercentage(segment, it) },
      )
    }
  }
}

@Composable
private fun SegmentInputs(
    segment: InBodySegment,
    input: InBodySegmentInput,
    enabled: Boolean,
    onLeanMassChange: (String) -> Unit,
    onLeanPercentageChange: (String) -> Unit,
    onFatMassChange: (String) -> Unit,
    onFatPercentageChange: (String) -> Unit,
) {
  Text(
      text = segment.displayName,
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
  )
  Spacer(Modifier.height(8.dp))
  NumberField(
      value = input.leanMassKg,
      onValueChange = onLeanMassChange,
      modifier = Modifier.fillMaxWidth(),
      label = "Мышечная масса, кг",
      decimal = true,
      enabled = enabled,
  )
  Spacer(Modifier.height(8.dp))
  NumberField(
      value = input.leanPercentage,
      onValueChange = onLeanPercentageChange,
      modifier = Modifier.fillMaxWidth(),
      label = "Мышцы от эталона, %",
      decimal = true,
      enabled = enabled,
  )
  Spacer(Modifier.height(8.dp))
  NumberField(
      value = input.fatMassKg,
      onValueChange = onFatMassChange,
      modifier = Modifier.fillMaxWidth(),
      label = "Жировая масса, кг",
      decimal = true,
      enabled = enabled,
  )
  Spacer(Modifier.height(8.dp))
  NumberField(
      value = input.fatPercentage,
      onValueChange = onFatPercentageChange,
      modifier = Modifier.fillMaxWidth(),
      label = "Жир от эталона, %",
      decimal = true,
      enabled = enabled,
  )
}

@Composable
private fun CircumferencesCard(
    state: MeasurementEditorUiState,
    viewModel: MeasurementEditorViewModel,
) {
  val calculatedWhr = state.effectiveWaistHipRatio
  val enabled = !state.isBusy
  GymCard(modifier = Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
          imageVector = Icons.Rounded.Straighten,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.width(10.dp))
      Text(
          text = "Обхваты, см",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
      )
    }
    Spacer(Modifier.height(12.dp))
    NumberField(
        value = state.waistCm,
        onValueChange = viewModel::setWaistCm,
        modifier = Modifier.fillMaxWidth(),
        label = "Талия",
        decimal = true,
        enabled = enabled,
    )
    Spacer(Modifier.height(8.dp))
    NumberField(
        value = state.chestCm,
        onValueChange = viewModel::setChestCm,
        modifier = Modifier.fillMaxWidth(),
        label = "Грудь",
        decimal = true,
        enabled = enabled,
    )
    Spacer(Modifier.height(8.dp))
    NumberField(
        value = state.hipsCm,
        onValueChange = viewModel::setHipsCm,
        modifier = Modifier.fillMaxWidth(),
        label = "Бёдра",
        decimal = true,
        enabled = enabled,
    )
    Spacer(Modifier.height(8.dp))
    NumberField(
        value = state.rightRelaxedArmCm,
        onValueChange = viewModel::setRightRelaxedArmCm,
        modifier = Modifier.fillMaxWidth(),
        label = "Правое расслабленное плечо",
        decimal = true,
        enabled = enabled,
    )
    Spacer(Modifier.height(8.dp))
    NumberField(
        value = state.rightThighCm,
        onValueChange = viewModel::setRightThighCm,
        modifier = Modifier.fillMaxWidth(),
        label = "Правое бедро",
        decimal = true,
        enabled = enabled,
    )
    if (state.waistHipRatio.isBlank() && calculatedWhr != null) {
      Spacer(Modifier.height(8.dp))
      Text(
          text =
              "WHR рассчитан из талии и бёдер: ${formatMeasurementValue(com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementMetric.WAIST_HIP_RATIO, calculatedWhr)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeasurementDatePicker(
    measuredAt: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
  // M3 DatePicker работает в UTC-полуночи; из локального measuredAt берём именно дату.
  val initialDate =
      Instant.ofEpochMilli(measuredAt)
          .atZone(java.time.ZoneId.systemDefault())
          .toLocalDate()
          .atStartOfDay(ZoneOffset.UTC)
          .toInstant()
          .toEpochMilli()
  val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialDate)
  DatePickerDialog(
      onDismissRequest = onDismiss,
      confirmButton = {
        TextButton(onClick = { pickerState.selectedDateMillis?.let(onConfirm) }) { Text("Готово") }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
  ) {
    DatePicker(state = pickerState)
  }
}

private val InBodySegment.displayName: String
  get() =
      when (this) {
        InBodySegment.LEFT_ARM -> "Левая рука"
        InBodySegment.RIGHT_ARM -> "Правая рука"
        InBodySegment.TRUNK -> "Корпус"
        InBodySegment.LEFT_LEG -> "Левая нога"
        InBodySegment.RIGHT_LEG -> "Правая нога"
      }
