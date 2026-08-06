package com.valerochka1337.valerochkagym.ui.measurements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.di.ComputeDispatcher
import com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementMetric
import com.valerochka1337.valerochkagym.domain.measurements.MeasurementMetricComparison
import com.valerochka1337.valerochkagym.domain.measurements.MeasurementPeriod
import com.valerochka1337.valerochkagym.domain.measurements.filterMeasurementsByPeriod
import com.valerochka1337.valerochkagym.domain.measurements.latestMetricComparisons
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

/** Состояние pushed-экрана «Замеры». Списки остаются null до первой эмиссии Room. */
data class MeasurementsUiState(
    val loading: Boolean = true,
    val period: MeasurementPeriod = MeasurementPeriod.MONTHS_3,
    val measurements: List<BodyMeasurementEntity>? = null,
    val summary: List<MeasurementMetricComparison> = emptyList(),
    val compositionMetric: BodyMeasurementMetric = BodyMeasurementMetric.WEIGHT,
    val circumferenceMetric: BodyMeasurementMetric = BodyMeasurementMetric.WAIST,
    val riskMetric: BodyMeasurementMetric = BodyMeasurementMetric.WAIST_HIP_RATIO,
    val selectedMeasurementId: String? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Последняя точка — дефолт таблицы, когда пользователь ещё не тапал график. */
    val selectedMeasurement: BodyMeasurementEntity?
        get() = measurements?.firstOrNull { it.id == selectedMeasurementId }
            ?: measurements?.maxByOrNull { it.measuredAt }

    val hasMeasurements: Boolean get() = !measurements.isNullOrEmpty()
}

/**
 * Реактивно собирает историю замеров, единый период и выбор трёх графиков. Фильтрация и сводка
 * выполняются вне Main: история обычно короткая, но это сохраняет правило приложения для всех
 * вычислений над Flow и позволяет подменить диспетчер в тесте.
 */
@HiltViewModel
class MeasurementsViewModel @Inject constructor(
    private val bodyMeasurementDao: BodyMeasurementDao,
    private val uploadScheduler: MeasurementUploadScheduler,
    @param:ComputeDispatcher private val computeDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val zone = ZoneId.systemDefault()
    private val period = MutableStateFlow(MeasurementPeriod.MONTHS_3)
    private val compositionMetric = MutableStateFlow(BodyMeasurementMetric.WEIGHT)
    private val circumferenceMetric = MutableStateFlow(BodyMeasurementMetric.WAIST)
    private val riskMetric = MutableStateFlow(BodyMeasurementMetric.WAIST_HIP_RATIO)
    private val selectedMeasurementId = MutableStateFlow<String?>(null)

    private val selection = combine(
        period,
        compositionMetric,
        circumferenceMetric,
        riskMetric,
        selectedMeasurementId,
    ) { selectedPeriod, composition, circumference, risk, selectedId ->
        Selection(selectedPeriod, composition, circumference, risk, selectedId)
    }

    val uiState: StateFlow<MeasurementsUiState> = combine(
        bodyMeasurementDao.observeAll(),
        selection,
    ) { all, selected ->
        val measurements = filterMeasurementsByPeriod(
            measurements = all,
            period = selected.period,
            nowMillis = System.currentTimeMillis(),
            zone = zone,
        )
        MeasurementsUiState(
            loading = false,
            period = selected.period,
            measurements = measurements,
            summary = latestMetricComparisons(measurements),
            compositionMetric = selected.compositionMetric,
            circumferenceMetric = selected.circumferenceMetric,
            riskMetric = selected.riskMetric,
            selectedMeasurementId = selected.measurementId,
            zone = zone,
        )
    }
        .flowOn(computeDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MeasurementsUiState(zone = zone),
        )

    fun onPeriodSelected(value: MeasurementPeriod) {
        period.value = value
        // Выбранная точка может не входить в новое окно; вернём таблицу к последней точке.
        selectedMeasurementId.value = null
    }

    fun onCompositionMetricSelected(value: BodyMeasurementMetric) {
        compositionMetric.value = value
        selectedMeasurementId.value = null
    }

    fun onCircumferenceMetricSelected(value: BodyMeasurementMetric) {
        circumferenceMetric.value = value
        selectedMeasurementId.value = null
    }

    fun onRiskMetricSelected(value: BodyMeasurementMetric) {
        riskMetric.value = value
        selectedMeasurementId.value = null
    }

    /** Повторный тап снимает выбор и возвращает таблицу к последнему замеру. */
    fun onMeasurementSelected(id: String?) {
        selectedMeasurementId.value = if (id == selectedMeasurementId.value) null else id
    }

    fun retryUpload(measurementId: String) {
        viewModelScope.launch { uploadScheduler.retry(measurementId) }
    }

    private data class Selection(
        val period: MeasurementPeriod,
        val compositionMetric: BodyMeasurementMetric,
        val circumferenceMetric: BodyMeasurementMetric,
        val riskMetric: BodyMeasurementMetric,
        val measurementId: String?,
    )
}
