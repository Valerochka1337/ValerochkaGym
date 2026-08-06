package com.valerochka1337.valerochkagym.ui.measurements

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.domain.measurements.calculateWaistHipRatio
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/** Черновик формы замера; строки позволяют спокойно вводить промежуточные значения вроде `1.`. */
data class MeasurementEditorUiState(
    val isNew: Boolean = true,
    val isLoading: Boolean = false,
    val measuredAt: Long = System.currentTimeMillis(),
    val weightKg: String = "",
    val skeletalMuscleMassKg: String = "",
    val bodyFatPercentage: String = "",
    val visceralFatLevel: String = "",
    val waistHipRatio: String = "",
    val waistCm: String = "",
    val chestCm: String = "",
    val hipsCm: String = "",
    val rightRelaxedArmCm: String = "",
    val rightThighCm: String = "",
) {
    val parsedWeightKg: Double? get() = decimalOrNull(weightKg)
    val parsedSkeletalMuscleMassKg: Double? get() = decimalOrNull(skeletalMuscleMassKg)
    val parsedBodyFatPercentage: Double? get() = decimalOrNull(bodyFatPercentage)
    val parsedVisceralFatLevel: Int? get() = integerOrNull(visceralFatLevel)
    val parsedWaistCm: Double? get() = decimalOrNull(waistCm)
    val parsedChestCm: Double? get() = decimalOrNull(chestCm)
    val parsedHipsCm: Double? get() = decimalOrNull(hipsCm)
    val parsedRightRelaxedArmCm: Double? get() = decimalOrNull(rightRelaxedArmCm)
    val parsedRightThighCm: Double? get() = decimalOrNull(rightThighCm)

    /** Введённый в InBody WHR приоритетнее, иначе строим его из двух введённых обхватов. */
    val effectiveWaistHipRatio: Double?
        get() = decimalOrNull(waistHipRatio) ?: calculateWaistHipRatio(parsedWaistCm, parsedHipsCm)

    /** Требование формы: хотя бы одно реально распарсенное измерение, не только пустой текст. */
    val canSave: Boolean
        get() = listOfNotNull(
            parsedWeightKg,
            parsedSkeletalMuscleMassKg,
            parsedBodyFatPercentage,
            parsedVisceralFatLevel,
            effectiveWaistHipRatio,
            parsedWaistCm,
            parsedChestCm,
            parsedHipsCm,
            parsedRightRelaxedArmCm,
            parsedRightThighCm,
        ).isNotEmpty()
}

/**
 * Создание и редактирование замера. Новая запись ставится в очередь сразу после записи в Room.
 * Если строка уже была выгружена, её статус сохраняется UPLOADED и новая очередь не создаётся:
 * Sheets — append-only журнал, локальная правка не должна менять прошлую строку.
 */
@HiltViewModel
class MeasurementEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bodyMeasurementDao: BodyMeasurementDao,
    private val uploadScheduler: MeasurementUploadScheduler,
) : ViewModel() {

    private val measurementId: String? = savedStateHandle.get(GymRoutes.MEASUREMENT_ID_ARG)
    private val zone: ZoneId = ZoneId.systemDefault()
    private var existingMeasurement: BodyMeasurementEntity? = null

    private val _uiState = MutableStateFlow(
        MeasurementEditorUiState(isNew = measurementId == null, isLoading = measurementId != null),
    )
    val uiState: StateFlow<MeasurementEditorUiState> = _uiState.asStateFlow()

    private val _finished = Channel<Unit>(Channel.BUFFERED)
    /** Сохранение либо удаление завершено — экран может безопасно вернуться к истории. */
    val finished = _finished.receiveAsFlow()

    init {
        measurementId?.let { id -> viewModelScope.launch { load(id) } }
    }

    private suspend fun load(id: String) {
        val measurement = bodyMeasurementDao.getById(id)
        existingMeasurement = measurement
        _uiState.value = if (measurement == null) {
            MeasurementEditorUiState(isNew = false, isLoading = false)
        } else {
            MeasurementEditorUiState(
                isNew = false,
                measuredAt = measurement.measuredAt,
                weightKg = measurement.weightKg.toInput(),
                skeletalMuscleMassKg = measurement.skeletalMuscleMassKg.toInput(),
                bodyFatPercentage = measurement.bodyFatPercentage.toInput(),
                visceralFatLevel = measurement.visceralFatLevel?.toString().orEmpty(),
                waistHipRatio = measurement.waistHipRatio.toInput(),
                waistCm = measurement.waistCm.toInput(),
                chestCm = measurement.chestCm.toInput(),
                hipsCm = measurement.hipsCm.toInput(),
                rightRelaxedArmCm = measurement.rightRelaxedArmCm.toInput(),
                rightThighCm = measurement.rightThighCm.toInput(),
            )
        }
    }

    /** Меняет только календарную дату, сохраняя время ввода для отдельной колонки экспорта. */
    fun setDateFromUtcMillis(utcMidnightMillis: Long) {
        _uiState.update { state ->
            val pickedDate = Instant.ofEpochMilli(utcMidnightMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
            val currentTime = Instant.ofEpochMilli(state.measuredAt).atZone(zone).toLocalTime()
            val measuredAt = pickedDate.atTime(currentTime).atZone(zone).toInstant().toEpochMilli()
            state.copy(measuredAt = measuredAt)
        }
    }

    fun setWeightKg(value: String) = update { copy(weightKg = value) }
    fun setSkeletalMuscleMassKg(value: String) = update { copy(skeletalMuscleMassKg = value) }
    fun setBodyFatPercentage(value: String) = update { copy(bodyFatPercentage = value) }
    fun setVisceralFatLevel(value: String) = update { copy(visceralFatLevel = value) }
    fun setWaistHipRatio(value: String) = update { copy(waistHipRatio = value) }
    fun setWaistCm(value: String) = update { copy(waistCm = value) }
    fun setChestCm(value: String) = update { copy(chestCm = value) }
    fun setHipsCm(value: String) = update { copy(hipsCm = value) }
    fun setRightRelaxedArmCm(value: String) = update { copy(rightRelaxedArmCm = value) }
    fun setRightThighCm(value: String) = update { copy(rightThighCm = value) }

    fun save() {
        val state = _uiState.value
        if (state.isLoading || !state.canSave) return
        viewModelScope.launch {
            val old = existingMeasurement
            val alreadyUploaded = old?.uploadStatus == UploadStatus.UPLOADED
            val id = old?.id ?: UUID.randomUUID().toString()
            val entity = BodyMeasurementEntity(
                id = id,
                measuredAt = state.measuredAt,
                weightKg = state.parsedWeightKg,
                skeletalMuscleMassKg = state.parsedSkeletalMuscleMassKg,
                bodyFatPercentage = state.parsedBodyFatPercentage,
                visceralFatLevel = state.parsedVisceralFatLevel,
                // Храним только явно введённый WHR из InBody. Автоматический WHR — производная
                // двух сохранённых обхватов; так при следующей правке талии/бёдер он пересчитается,
                // а не останется устаревшим числом, выглядящим как ручное значение.
                waistHipRatio = decimalOrNull(state.waistHipRatio),
                waistCm = state.parsedWaistCm,
                chestCm = state.parsedChestCm,
                hipsCm = state.parsedHipsCm,
                rightRelaxedArmCm = state.parsedRightRelaxedArmCm,
                rightThighCm = state.parsedRightThighCm,
                uploadStatus = if (alreadyUploaded) UploadStatus.UPLOADED else UploadStatus.PENDING,
                uploadError = null,
            )
            if (old == null) bodyMeasurementDao.insert(entity) else bodyMeasurementDao.update(entity)
            existingMeasurement = entity
            if (!alreadyUploaded) uploadScheduler.schedule(id)
            _finished.send(Unit)
        }
    }

    fun delete() {
        val id = existingMeasurement?.id ?: return
        viewModelScope.launch {
            // Удаляем только локальную запись. Уже append-нутая строка Sheets исторически остаётся.
            bodyMeasurementDao.delete(id)
            _finished.send(Unit)
        }
    }

    private inline fun update(transform: MeasurementEditorUiState.() -> MeasurementEditorUiState) {
        _uiState.update(transform)
    }
}

private fun decimalOrNull(value: String): Double? =
    value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

private fun integerOrNull(value: String): Int? = value.toIntOrNull()?.takeIf { it >= 0 }

private fun Double?.toInput(): String = this?.toString().orEmpty()
