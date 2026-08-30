package com.valerochka1337.valerochkagym.ui.measurements

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.ai.InBodyReportAiReader
import com.valerochka1337.valerochkagym.data.ai.InBodyReportAiResult
import com.valerochka1337.valerochkagym.data.ai.InBodyReportDraft
import com.valerochka1337.valerochkagym.data.ai.AiApiConfigurationProvider
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegment
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegmentValues
import com.valerochka1337.valerochkagym.domain.measurements.calculateWaistHipRatio
import com.valerochka1337.valerochkagym.domain.measurements.inBodySegmentValues
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/** String representation of the four editable values printed for a single InBody segment. */
data class InBodySegmentInput(
    val leanMassKg: String = "",
    val leanPercentage: String = "",
    val fatMassKg: String = "",
    val fatPercentage: String = "",
) {
    val parsedValues: InBodySegmentValues
        get() = InBodySegmentValues(
            leanMassKg = decimalOrNull(leanMassKg),
            leanPercentage = decimalOrNull(leanPercentage),
            fatMassKg = decimalOrNull(fatMassKg),
            fatPercentage = decimalOrNull(fatPercentage),
        )
}

/** Черновик формы замера; строки позволяют спокойно вводить промежуточные значения вроде `1.`. */
data class MeasurementEditorUiState(
    val isNew: Boolean = true,
    val isLoading: Boolean = false,
    val isScanningInBody: Boolean = false,
    val isSaving: Boolean = false,
    val isAiConfigured: Boolean = false,
    val inBodyScanError: String? = null,
    val inBodyScanModelUnavailable: Boolean = false,
    val saveError: String? = null,
    val measuredAt: Long = System.currentTimeMillis(),
    val weightKg: String = "",
    val skeletalMuscleMassKg: String = "",
    val bodyFatPercentage: String = "",
    val bodyFatMassKg: String = "",
    val visceralFatLevel: String = "",
    val waistHipRatio: String = "",
    val inBodyScore: String = "",
    val totalBodyWaterLiters: String = "",
    val proteinKg: String = "",
    val mineralsKg: String = "",
    val bodyMassIndex: String = "",
    val fatFreeMassKg: String = "",
    val basalMetabolicRateKcal: String = "",
    val recommendedCalorieIntakeKcal: String = "",
    val segments: Map<InBodySegment, InBodySegmentInput> = defaultSegmentInputs(),
    val waistCm: String = "",
    val chestCm: String = "",
    val hipsCm: String = "",
    val rightRelaxedArmCm: String = "",
    val rightThighCm: String = "",
) {
    val parsedWeightKg: Double? get() = decimalOrNull(weightKg)
    val parsedSkeletalMuscleMassKg: Double? get() = decimalOrNull(skeletalMuscleMassKg)
    val parsedBodyFatPercentage: Double? get() = decimalOrNull(bodyFatPercentage)
    val parsedBodyFatMassKg: Double? get() = decimalOrNull(bodyFatMassKg)
    val parsedVisceralFatLevel: Int? get() = integerOrNull(visceralFatLevel)
    val parsedInBodyScore: Int? get() = integerOrNull(inBodyScore)
    val parsedTotalBodyWaterLiters: Double? get() = decimalOrNull(totalBodyWaterLiters)
    val parsedProteinKg: Double? get() = decimalOrNull(proteinKg)
    val parsedMineralsKg: Double? get() = decimalOrNull(mineralsKg)
    val parsedBodyMassIndex: Double? get() = decimalOrNull(bodyMassIndex)
    val parsedFatFreeMassKg: Double? get() = decimalOrNull(fatFreeMassKg)
    val parsedBasalMetabolicRateKcal: Int? get() = integerOrNull(basalMetabolicRateKcal)
    val parsedRecommendedCalorieIntakeKcal: Int? get() = integerOrNull(recommendedCalorieIntakeKcal)
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
            parsedBodyFatMassKg,
            parsedVisceralFatLevel,
            effectiveWaistHipRatio,
            parsedInBodyScore,
            parsedTotalBodyWaterLiters,
            parsedProteinKg,
            parsedMineralsKg,
            parsedBodyMassIndex,
            parsedFatFreeMassKg,
            parsedBasalMetabolicRateKcal,
            parsedRecommendedCalorieIntakeKcal,
            parsedWaistCm,
            parsedChestCm,
            parsedHipsCm,
            parsedRightRelaxedArmCm,
            parsedRightThighCm,
        ).isNotEmpty() || segments.values.any { it.parsedValues.hasAnyValue }

    val isBusy: Boolean get() = isScanningInBody || isSaving
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
    private val inBodyReportAiReader: InBodyReportAiReader = NoOpInBodyReportAiReader,
    private val aiApiConfigurationProvider: AiApiConfigurationProvider =
        NoOpAiApiConfigurationProvider,
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
        viewModelScope.launch {
            aiApiConfigurationProvider.isConfigured.collect { isConfigured ->
                _uiState.update { it.copy(isAiConfigured = isConfigured) }
            }
        }
        measurementId?.let { id -> viewModelScope.launch { load(id) } }
    }

    private suspend fun load(id: String) {
        val measurement = bodyMeasurementDao.getById(id)
        existingMeasurement = measurement
        _uiState.value = if (measurement == null) {
            MeasurementEditorUiState(
                isNew = false,
                isLoading = false,
                isAiConfigured = _uiState.value.isAiConfigured,
            )
        } else {
            measurement.toEditorState(isAiConfigured = _uiState.value.isAiConfigured)
        }
    }

    /** Меняет только календарную дату, сохраняя время ввода для отдельной колонки экспорта. */
    fun setDateFromUtcMillis(utcMidnightMillis: Long) {
        _uiState.update { state ->
            if (state.isBusy) return@update state
            val pickedDate = Instant.ofEpochMilli(utcMidnightMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
            val currentTime = Instant.ofEpochMilli(state.measuredAt).atZone(zone).toLocalTime()
            val measuredAt = pickedDate.atTime(currentTime).atZone(zone).toInstant().toEpochMilli()
            state.copy(measuredAt = measuredAt)
        }
    }

    fun setWeightKg(value: String) = update { copy(weightKg = value) }
    fun setSkeletalMuscleMassKg(value: String) = update { copy(skeletalMuscleMassKg = value) }
    fun setBodyFatPercentage(value: String) = update { copy(bodyFatPercentage = value) }
    fun setBodyFatMassKg(value: String) = update { copy(bodyFatMassKg = value) }
    fun setVisceralFatLevel(value: String) = update { copy(visceralFatLevel = value) }
    fun setWaistHipRatio(value: String) = update { copy(waistHipRatio = value) }
    fun setInBodyScore(value: String) = update { copy(inBodyScore = value) }
    fun setTotalBodyWaterLiters(value: String) = update { copy(totalBodyWaterLiters = value) }
    fun setProteinKg(value: String) = update { copy(proteinKg = value) }
    fun setMineralsKg(value: String) = update { copy(mineralsKg = value) }
    fun setBodyMassIndex(value: String) = update { copy(bodyMassIndex = value) }
    fun setFatFreeMassKg(value: String) = update { copy(fatFreeMassKg = value) }
    fun setBasalMetabolicRateKcal(value: String) = update { copy(basalMetabolicRateKcal = value) }
    fun setRecommendedCalorieIntakeKcal(value: String) = update { copy(recommendedCalorieIntakeKcal = value) }
    fun setWaistCm(value: String) = update { copy(waistCm = value) }
    fun setChestCm(value: String) = update { copy(chestCm = value) }
    fun setHipsCm(value: String) = update { copy(hipsCm = value) }
    fun setRightRelaxedArmCm(value: String) = update { copy(rightRelaxedArmCm = value) }
    fun setRightThighCm(value: String) = update { copy(rightThighCm = value) }

    fun setSegmentLeanMassKg(segment: InBodySegment, value: String) = updateSegment(segment) {
        copy(leanMassKg = value)
    }

    fun setSegmentLeanPercentage(segment: InBodySegment, value: String) = updateSegment(segment) {
        copy(leanPercentage = value)
    }

    fun setSegmentFatMassKg(segment: InBodySegment, value: String) = updateSegment(segment) {
        copy(fatMassKg = value)
    }

    fun setSegmentFatPercentage(segment: InBodySegment, value: String) = updateSegment(segment) {
        copy(fatPercentage = value)
    }

    /**
     * Replaces only report fields after a valid response. Manual circumferences are deliberately
     * left untouched, and the draft stays editable until [save]. A camera cache file is deleted
     * in the same coroutine only after its bytes have been consumed by the reader.
     */
    fun scanInBody(uri: Uri, temporaryCameraFile: File? = null) {
        val state = _uiState.value
        if (state.isLoading || state.isBusy) return
        if (!state.isAiConfigured) {
            _uiState.update {
                it.copy(inBodyScanError = MISSING_CONFIGURATION_MESSAGE, inBodyScanModelUnavailable = false)
            }
            temporaryCameraFile?.delete()
            return
        }
        _uiState.update {
            it.copy(
                isScanningInBody = true,
                inBodyScanError = null,
                inBodyScanModelUnavailable = false,
                saveError = null,
            )
        }
        viewModelScope.launch {
            try {
                when (val result = inBodyReportAiReader.read(uri)) {
                    is InBodyReportAiResult.Success -> _uiState.update { current ->
                        current.applyInBodyDraft(result.draft).copy(
                            isScanningInBody = false,
                            inBodyScanError = null,
                            inBodyScanModelUnavailable = false,
                        )
                    }

                    is InBodyReportAiResult.Failure -> _uiState.update { current ->
                        current.copy(
                            isScanningInBody = false,
                            inBodyScanError = result.message,
                            inBodyScanModelUnavailable = result.modelUnavailable,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { current ->
                    current.copy(
                        isScanningInBody = false,
                        inBodyScanError = GENERIC_SCAN_FAILURE_MESSAGE,
                        inBodyScanModelUnavailable = false,
                    )
                }
            } finally {
                temporaryCameraFile?.delete()
            }
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.isLoading || state.isBusy || !state.canSave) return
        _uiState.update { it.copy(isSaving = true, saveError = null) }
        viewModelScope.launch {
            val old = existingMeasurement
            val alreadyUploaded = old?.uploadStatus == UploadStatus.UPLOADED
            val id = old?.id ?: UUID.randomUUID().toString()
            val segments = state.segments
            val entity = BodyMeasurementEntity(
                id = id,
                measuredAt = state.measuredAt,
                weightKg = state.parsedWeightKg,
                skeletalMuscleMassKg = state.parsedSkeletalMuscleMassKg,
                bodyFatPercentage = state.parsedBodyFatPercentage,
                bodyFatMassKg = state.parsedBodyFatMassKg,
                visceralFatLevel = state.parsedVisceralFatLevel,
                // Храним только явно введённый WHR из InBody. Автоматический WHR — производная
                // двух сохранённых обхватов; так при следующей правке талии/бёдер он пересчитается,
                // а не останется устаревшим числом, выглядящим как ручное значение.
                waistHipRatio = decimalOrNull(state.waistHipRatio),
                inBodyScore = state.parsedInBodyScore,
                totalBodyWaterLiters = state.parsedTotalBodyWaterLiters,
                proteinKg = state.parsedProteinKg,
                mineralsKg = state.parsedMineralsKg,
                bodyMassIndex = state.parsedBodyMassIndex,
                fatFreeMassKg = state.parsedFatFreeMassKg,
                basalMetabolicRateKcal = state.parsedBasalMetabolicRateKcal,
                recommendedCalorieIntakeKcal = state.parsedRecommendedCalorieIntakeKcal,
                leftArmLeanMassKg = segments.valuesFor(InBodySegment.LEFT_ARM).leanMassKg,
                leftArmLeanPercentage = segments.valuesFor(InBodySegment.LEFT_ARM).leanPercentage,
                rightArmLeanMassKg = segments.valuesFor(InBodySegment.RIGHT_ARM).leanMassKg,
                rightArmLeanPercentage = segments.valuesFor(InBodySegment.RIGHT_ARM).leanPercentage,
                trunkLeanMassKg = segments.valuesFor(InBodySegment.TRUNK).leanMassKg,
                trunkLeanPercentage = segments.valuesFor(InBodySegment.TRUNK).leanPercentage,
                leftLegLeanMassKg = segments.valuesFor(InBodySegment.LEFT_LEG).leanMassKg,
                leftLegLeanPercentage = segments.valuesFor(InBodySegment.LEFT_LEG).leanPercentage,
                rightLegLeanMassKg = segments.valuesFor(InBodySegment.RIGHT_LEG).leanMassKg,
                rightLegLeanPercentage = segments.valuesFor(InBodySegment.RIGHT_LEG).leanPercentage,
                leftArmFatMassKg = segments.valuesFor(InBodySegment.LEFT_ARM).fatMassKg,
                leftArmFatPercentage = segments.valuesFor(InBodySegment.LEFT_ARM).fatPercentage,
                rightArmFatMassKg = segments.valuesFor(InBodySegment.RIGHT_ARM).fatMassKg,
                rightArmFatPercentage = segments.valuesFor(InBodySegment.RIGHT_ARM).fatPercentage,
                trunkFatMassKg = segments.valuesFor(InBodySegment.TRUNK).fatMassKg,
                trunkFatPercentage = segments.valuesFor(InBodySegment.TRUNK).fatPercentage,
                leftLegFatMassKg = segments.valuesFor(InBodySegment.LEFT_LEG).fatMassKg,
                leftLegFatPercentage = segments.valuesFor(InBodySegment.LEFT_LEG).fatPercentage,
                rightLegFatMassKg = segments.valuesFor(InBodySegment.RIGHT_LEG).fatMassKg,
                rightLegFatPercentage = segments.valuesFor(InBodySegment.RIGHT_LEG).fatPercentage,
                waistCm = state.parsedWaistCm,
                chestCm = state.parsedChestCm,
                hipsCm = state.parsedHipsCm,
                rightRelaxedArmCm = state.parsedRightRelaxedArmCm,
                rightThighCm = state.parsedRightThighCm,
                uploadStatus = if (alreadyUploaded) UploadStatus.UPLOADED else UploadStatus.PENDING,
                uploadError = null,
            )
            try {
                if (old == null) bodyMeasurementDao.insert(entity) else bodyMeasurementDao.update(entity)
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isSaving = false) }
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(isSaving = false, saveError = GENERIC_SAVE_FAILURE_MESSAGE) }
                return@launch
            }
            existingMeasurement = entity
            if (!alreadyUploaded) uploadScheduler.schedule(id)
            _uiState.update { it.copy(isSaving = false) }
            _finished.send(Unit)
        }
    }

    fun delete() {
        if (_uiState.value.isBusy) return
        val id = existingMeasurement?.id ?: return
        viewModelScope.launch {
            // Удаляем только локальную запись. Уже append-нутая строка Sheets исторически остаётся.
            bodyMeasurementDao.delete(id)
            _finished.send(Unit)
        }
    }

    private inline fun update(transform: MeasurementEditorUiState.() -> MeasurementEditorUiState) {
        _uiState.update { state ->
            if (state.isBusy) state else state.transform().copy(saveError = null)
        }
    }

    private fun updateSegment(
        segment: InBodySegment,
        transform: InBodySegmentInput.() -> InBodySegmentInput,
    ) = update {
        copy(segments = segments + (segment to transform(segments.inputFor(segment))))
    }

    private companion object {
        const val MISSING_CONFIGURATION_MESSAGE =
            "Настройте нейросеть в настройках"
        const val GENERIC_SCAN_FAILURE_MESSAGE = "Не удалось распознать лист InBody — попробуйте ещё раз"
        const val GENERIC_SAVE_FAILURE_MESSAGE = "Не удалось сохранить замер — попробуйте ещё раз"

        object NoOpInBodyReportAiReader : InBodyReportAiReader {
            override suspend fun read(uri: Uri): InBodyReportAiResult =
                InBodyReportAiResult.Failure(MISSING_CONFIGURATION_MESSAGE)
        }

        object NoOpAiApiConfigurationProvider : AiApiConfigurationProvider {
            override val isConfigured: Flow<Boolean> = flowOf(false)
            override suspend fun connection() = null
            override suspend fun requestConfiguration() = null
        }
    }
}

private fun defaultSegmentInputs(): Map<InBodySegment, InBodySegmentInput> =
    InBodySegment.entries.associateWith { InBodySegmentInput() }

private fun Map<InBodySegment, InBodySegmentInput>.inputFor(segment: InBodySegment): InBodySegmentInput =
    get(segment) ?: InBodySegmentInput()

private fun Map<InBodySegment, InBodySegmentInput>.valuesFor(segment: InBodySegment): InBodySegmentValues =
    inputFor(segment).parsedValues

private fun MeasurementEditorUiState.applyInBodyDraft(draft: InBodyReportDraft): MeasurementEditorUiState {
    val currentDateTime = Instant.ofEpochMilli(measuredAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
    val importedMeasuredAt = (draft.measuredDate ?: currentDateTime.toLocalDate())
        .atTime(draft.measuredTime ?: currentDateTime.toLocalTime())
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
    return copy(
        measuredAt = importedMeasuredAt,
        weightKg = draft.weightKg.toInputOr(weightKg),
        skeletalMuscleMassKg = draft.skeletalMuscleMassKg.toInputOr(skeletalMuscleMassKg),
        bodyFatPercentage = draft.bodyFatPercentage.toInputOr(bodyFatPercentage),
        bodyFatMassKg = draft.bodyFatMassKg.toInputOr(bodyFatMassKg),
        visceralFatLevel = draft.visceralFatLevel?.toString() ?: visceralFatLevel,
        waistHipRatio = draft.waistHipRatio.toInputOr(waistHipRatio),
        inBodyScore = draft.inBodyScore?.toString() ?: inBodyScore,
        totalBodyWaterLiters = draft.totalBodyWaterLiters.toInputOr(totalBodyWaterLiters),
        proteinKg = draft.proteinKg.toInputOr(proteinKg),
        mineralsKg = draft.mineralsKg.toInputOr(mineralsKg),
        bodyMassIndex = draft.bodyMassIndex.toInputOr(bodyMassIndex),
        fatFreeMassKg = draft.fatFreeMassKg.toInputOr(fatFreeMassKg),
        basalMetabolicRateKcal = draft.basalMetabolicRateKcal?.toString() ?: basalMetabolicRateKcal,
        recommendedCalorieIntakeKcal = draft.recommendedCalorieIntakeKcal?.toString()
            ?: recommendedCalorieIntakeKcal,
        segments = InBodySegment.entries.associateWith { segment ->
            segments.inputFor(segment).merge(draft.segments[segment])
        },
    )
}

private fun BodyMeasurementEntity.toEditorState(isAiConfigured: Boolean): MeasurementEditorUiState =
    MeasurementEditorUiState(
        isNew = false,
        isLoading = false,
        isAiConfigured = isAiConfigured,
        measuredAt = measuredAt,
        weightKg = weightKg.toInput(),
        skeletalMuscleMassKg = skeletalMuscleMassKg.toInput(),
        bodyFatPercentage = bodyFatPercentage.toInput(),
        bodyFatMassKg = bodyFatMassKg.toInput(),
        visceralFatLevel = visceralFatLevel?.toString().orEmpty(),
        waistHipRatio = waistHipRatio.toInput(),
        inBodyScore = inBodyScore?.toString().orEmpty(),
        totalBodyWaterLiters = totalBodyWaterLiters.toInput(),
        proteinKg = proteinKg.toInput(),
        mineralsKg = mineralsKg.toInput(),
        bodyMassIndex = bodyMassIndex.toInput(),
        fatFreeMassKg = fatFreeMassKg.toInput(),
        basalMetabolicRateKcal = basalMetabolicRateKcal?.toString().orEmpty(),
        recommendedCalorieIntakeKcal = recommendedCalorieIntakeKcal?.toString().orEmpty(),
        segments = InBodySegment.entries.associateWith { segment -> inBodySegmentValues(segment).toInput() },
        waistCm = waistCm.toInput(),
        chestCm = chestCm.toInput(),
        hipsCm = hipsCm.toInput(),
        rightRelaxedArmCm = rightRelaxedArmCm.toInput(),
        rightThighCm = rightThighCm.toInput(),
    )

private fun InBodySegmentValues?.toInput(): InBodySegmentInput = InBodySegmentInput(
    leanMassKg = this?.leanMassKg.toInput(),
    leanPercentage = this?.leanPercentage.toInput(),
    fatMassKg = this?.fatMassKg.toInput(),
    fatPercentage = this?.fatPercentage.toInput(),
)

/** Неочитанное поле повторного сканирования не должно стирать уже проверенное значение формы. */
private fun InBodySegmentInput.merge(values: InBodySegmentValues?): InBodySegmentInput = copy(
    leanMassKg = values?.leanMassKg.toInputOr(leanMassKg),
    leanPercentage = values?.leanPercentage.toInputOr(leanPercentage),
    fatMassKg = values?.fatMassKg.toInputOr(fatMassKg),
    fatPercentage = values?.fatPercentage.toInputOr(fatPercentage),
)

private fun decimalOrNull(value: String): Double? =
    value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

private fun integerOrNull(value: String): Int? = value.toIntOrNull()?.takeIf { it >= 0 }

private fun Double?.toInput(): String = this?.toString().orEmpty()

private fun Double?.toInputOr(previous: String): String = this?.toString() ?: previous
