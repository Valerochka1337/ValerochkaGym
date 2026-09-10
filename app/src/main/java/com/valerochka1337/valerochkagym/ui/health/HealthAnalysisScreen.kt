package com.valerochka1337.valerochkagym.ui.health

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.domain.*
import com.valerochka1337.valerochkagym.ui.analysis.charts.LinePoint
import com.valerochka1337.valerochkagym.ui.analysis.charts.TrendLineChart
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun HealthAnalysisScreen(
    onOpenDetail: (String) -> Unit,
    onCreate: (HealthRecordKind) -> Unit,
    onOpenMeasurement: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HealthViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val haptics = gymHaptics()
  HealthAnalysisContent(
      state,
      onOpenDetail = {
        haptics.tap()
        onOpenDetail(it)
      },
      onCreate = {
        haptics.tap()
        onCreate(it)
      },
      onOpenMeasurement = {
        haptics.tap()
        onOpenMeasurement(it)
      },
      onAcknowledge = {
        haptics.confirm()
        viewModel.acknowledgeStorageNotice()
      },
      onBackendSync = viewModel::setBackendSync,
      onAiDisclosure = viewModel::setAiDisclosure,
      onRetry = viewModel::retry,
      modifier = modifier,
  )
}

/** This content is a child of Analysis's LazyColumn; it must never add vertical scrolling. */
@Composable
internal fun HealthAnalysisContent(
    state: HealthUiState,
    onOpenDetail: (String) -> Unit,
    onCreate: (HealthRecordKind) -> Unit,
    onOpenMeasurement: (String) -> Unit,
    onAcknowledge: () -> Unit,
    onBackendSync: (Boolean) -> Unit,
    onAiDisclosure: (Boolean) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    val padding = if (maxWidth < 600.dp) 16.dp else 24.dp
    Column(
        Modifier.widthIn(max = 840.dp)
            .fillMaxWidth()
            .padding(horizontal = padding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Здоровье", style = MaterialTheme.typography.headlineSmall)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HealthRecordKind.entries.forEach { kind ->
          HealthChoice("Добавить: ${healthKindLabel(kind)}", false) { onCreate(kind) }
        }
      }
      GymCard(Modifier.fillMaxWidth()) {
        Text("Хранение и передача", style = MaterialTheme.typography.titleMedium)
        Text(
            if (state.consent.localStorageAcknowledgedVersion > 0) "Локальное хранение подтверждено"
            else "Перед первой записью нужно подтвердить локальное хранение"
        )
        Text("Подтверждение хранения на устройстве не разрешает передачу записей.")
        if (state.consent.localStorageAcknowledgedVersion == 0)
            PillButton("Подтвердить хранение", onAcknowledge, modifier = Modifier.fillMaxWidth())
        Text(
            "Синхронизация сохраняет записи здоровья в вашем аккаунте. Доступ тренеру она не открывает."
        )
        PillButton(
            if (state.consent.backendSyncEnabled) "Выключить синхронизацию здоровья"
            else "Включить синхронизацию здоровья",
            { onBackendSync(!state.consent.backendSyncEnabled) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Обработка InBody AI отдельно разрешает отправку выбранного изображения через сервер для анализа AI."
        )
        PillButton(
            if (state.consent.aiDisclosureEnabled) "Запретить обработку InBody AI"
            else "Разрешить обработку InBody AI",
            { onAiDisclosure(!state.consent.aiDisclosureEnabled) },
            modifier = Modifier.fillMaxWidth(),
        )
      }
      if (state.loading) Text("Загружаем записи здоровья…")
      val current = state.records.filterNot { it.deleted }
      if (!state.loading && current.isEmpty())
          Text("Пока нет подтверждённых записей. Добавьте отчёт, наблюдение или ограничение.")
      current.forEach { record ->
        GymCard(Modifier.fillMaxWidth(), onClick = { onOpenDetail(record.logicalId) }) {
          Text(healthRecordLabel(record.payload), style = MaterialTheme.typography.titleMedium)
          Text(healthKindLabel(record.kind), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      state.analysis?.trends?.forEach { trend ->
        val name =
            state.metrics.firstOrNull { it.id == trend.key.metricIdentityId }?.nameOriginal
                ?: (current
                        .firstOrNull {
                          (it.payload as? HealthPayload.Observation)?.metricIdentityId ==
                              trend.key.metricIdentityId
                        }
                        ?.payload as? HealthPayload.Observation)
                    ?.metricNameOriginal
                ?: "Наблюдения"
        HealthTrendCard(name, trend)
      }
      if (state.measurements.isNotEmpty()) {
        Text("Замеры тела и InBody", style = MaterialTheme.typography.titleMedium)
        Text("Исходные записи из раздела замеров")
        state.measurements
            .distinctBy { it.id }
            .forEach { measurement ->
              val date =
                  Instant.ofEpochMilli(measurement.measuredAt)
                      .atZone(ZoneId.systemDefault())
                      .toLocalDate()
              GymCard(
                  Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = "Замер $date"
                  },
                  onClick = { onOpenMeasurement(measurement.id) },
              ) {
                Text("Замер $date")
                measurement.weightKg?.let { Text("Масса: $it кг") }
                measurement.bodyFatPercentage?.let { Text("Доля жира: $it %") }
                measurement.skeletalMuscleMassKg?.let { Text("Скелетная мышечная масса: $it кг") }
              }
            }
      }
      if (state.records.any { it.deleted }) {
        Text("История удалённых записей", style = MaterialTheme.typography.titleMedium)
        state.records
            .filter { it.deleted }
            .forEach { record ->
              PillButton(
                  "История: ${healthKindLabel(record.kind)} · ${Instant.ofEpochMilli(record.createdAtEpochMs).atZone(ZoneId.systemDefault()).toLocalDateTime()}",
                  { onOpenDetail(record.logicalId) },
                  modifier = Modifier.fillMaxWidth(),
              )
            }
      }
      state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
        PillButton("Повторить", onRetry, modifier = Modifier.fillMaxWidth())
      }
    }
  }
}

@Composable
private fun HealthTrendCard(name: String, trend: HealthTrend) {
  var selectedId by rememberSaveable(trend.key) { mutableStateOf<String?>(null) }
  val decimals = remember(trend.points) { trend.points.map { BigDecimal(it.value) } }
  val origin = decimals.minOrNull() ?: BigDecimal.ZERO
  // Removing a common offset before Float conversion retains small changes in large raw values.
  val chartPoints =
      remember(trend.points) {
        trend.points.mapIndexedNotNull { index, point ->
          val millis = healthObservedMillis(point.observedAt) ?: return@mapIndexedNotNull null
          LinePoint(
              millis,
              decimals[index].subtract(origin).toFloat(),
              point.observedAt,
          )
        }
      }
  GymCard(Modifier.fillMaxWidth()) {
    Text(name, style = MaterialTheme.typography.titleMedium)
    Text(
        listOfNotNull(trend.key.unitOriginal, trend.key.methodOriginal, trend.key.specimenOriginal)
            .joinToString(" · ")
    )
    if (trend.points.size >= 2 && chartPoints.size == trend.points.size) {
      TrendLineChart(
          chartPoints,
          selectedIndex =
              trend.points.indexOfFirst { it.logicalId == selectedId }.takeIf { it >= 0 },
          onSelect = { selectedId = it?.let { index -> trend.points.getOrNull(index)?.logicalId } },
          valueFormatter = {
            origin.add(BigDecimal(it.toString())).stripTrailingZeros().toPlainString()
          },
          showTrend = false,
      )
    } else if (chartPoints.size != trend.points.size)
        Text("Дата выходит за диапазон графика. Значения доступны в таблице.")
    else Text("Для линии нужно не менее двух совместимых числовых наблюдений")
    Text("Таблица исходных значений")
    trend.points.forEach { point ->
      HealthChoice("${point.observedAt}: ${point.value}", selectedId == point.logicalId) {
        selectedId = point.logicalId
      }
    }
    trend.points
        .firstOrNull { it.logicalId == selectedId }
        ?.let { Text("Выбрано: ${it.observedAt}, ${it.value}") }
    trend.incompatible.forEach {
      Text(
          "${it.valueOriginal}: ${incompatibilityLabel(it.reason)}",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun healthObservedMillis(value: String): Long? =
    runCatching {
          if ('T' !in value)
              LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
          else OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }
        .getOrNull()

private fun incompatibilityLabel(reason: HealthTrendIncompatibilityReason) =
    when (reason) {
      HealthTrendIncompatibilityReason.TOO_FEW_POINTS -> "недостаточно числовых точек"
      HealthTrendIncompatibilityReason.VALUE_KIND -> "тип значения не подходит для числовой линии"
      HealthTrendIncompatibilityReason.METRIC -> "другая метрика"
      HealthTrendIncompatibilityReason.UNIT -> "другая единица"
      HealthTrendIncompatibilityReason.METHOD -> "другой метод"
      HealthTrendIncompatibilityReason.SPECIMEN -> "другой образец"
    }
