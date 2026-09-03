package com.valerochka1337.valerochkagym.ui.measurements

import android.net.Uri
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.ai.InBodyReportAiReader
import com.valerochka1337.valerochkagym.data.ai.InBodyReportAiResult
import com.valerochka1337.valerochkagym.data.ai.InBodyReportDraft
import com.valerochka1337.valerochkagym.data.ai.AiApiConfigurationProvider
import com.valerochka1337.valerochkagym.data.ai.AiApiRequestConfiguration
import com.valerochka1337.valerochkagym.data.ai.healthAiEndpointDecision
import com.valerochka1337.valerochkagym.data.ai.HealthAiEndpointDecision
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.measurements.MeasurementRepository
import com.valerochka1337.valerochkagym.data.measurements.MeasurementDocumentInput
import com.valerochka1337.valerochkagym.data.measurements.MeasurementDocumentRepository
import com.valerochka1337.valerochkagym.data.measurements.MeasurementDocumentStoreResult
import com.valerochka1337.valerochkagym.data.measurements.PendingInBodyCaptureRegistry
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegment
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegmentValues
import com.valerochka1337.valerochkagym.domain.measurements.calculateWaistHipRatio
import com.valerochka1337.valerochkagym.domain.measurements.inBodySegmentValues
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    val afterMeal: Boolean = false,
    val afterWorkout: Boolean = false,
    val unusualHydration: Boolean = false,
    val conditionNote: String = "",
    val originalAvailable: Boolean = false,
    val retainOriginal: Boolean = false,
    val readyOriginalCount: Int = 0,
    /** Random cache-file token only; never a URI or file path. */
    val pendingCameraToken: String? = null,
    val scanDisclosure: InBodyRecipientDisclosure? = null,
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

data class InBodyRecipientDisclosure(val host: String, val model: String, val loopback: Boolean)

/**
 * Создание и редактирование замера. Новая запись ставится в очередь сразу после записи в Room.
 * Каждое подтверждённое изменение создаёт следующий неизменяемый snapshot для Sheets.
 */
@HiltViewModel
class MeasurementEditorViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val bodyMeasurementDao: BodyMeasurementDao,
    private val uploadScheduler: MeasurementUploadScheduler,
    private val inBodyReportAiReader: InBodyReportAiReader = NoOpInBodyReportAiReader,
    private val aiApiConfigurationProvider: AiApiConfigurationProvider =
        NoOpAiApiConfigurationProvider,
    private val measurementRepository: MeasurementRepository? = null,
    private val measurementDocumentRepository: MeasurementDocumentRepository? = null,
    @param:ApplicationContext private val applicationContext: Context? = null,
    private val captureRegistry: PendingInBodyCaptureRegistry? = null,
) : ViewModel() {

    private val measurementId: String? = savedStateHandle.get(GymRoutes.MEASUREMENT_ID_ARG)
    private val zone: ZoneId = ZoneId.systemDefault()
    private var existingMeasurement: BodyMeasurementEntity? = null
    private var pendingSource: InBodySource? = null
    /** Secret-bearing configuration is deliberately private, never Compose/SavedState state. */
    private var pendingScanConfiguration: AiApiRequestConfiguration? = null
    private var selectedOriginal: InBodySource? = null
    /** The exact structured state already committed before a local-original retry. */
    private var savedMeasurementAwaitingOriginal: BodyMeasurementEntity? = null
    private val cameraTokenKey = "pending_inbody_camera_token"

    private val _uiState = MutableStateFlow(
        MeasurementEditorUiState(isNew = measurementId == null, isLoading = measurementId != null),
    )
    val uiState: StateFlow<MeasurementEditorUiState> = _uiState.asStateFlow()

    private val _finished = Channel<Unit>(Channel.BUFFERED)
    /** Сохранение либо удаление завершено — экран может безопасно вернуться к истории. */
    val finished = _finished.receiveAsFlow()

    init {
        savedStateHandle.get<String>(cameraTokenKey)?.let { token ->
            _uiState.update { it.copy(pendingCameraToken = token) }
        }
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
            measurement.toEditorState(
                isAiConfigured = _uiState.value.isAiConfigured,
                readyOriginalCount = measurementDocumentRepository?.readyForMeasurement(id)?.size ?: 0,
            )
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
    fun setAfterMeal(value: Boolean) = update { copy(afterMeal = value) }
    fun setAfterWorkout(value: Boolean) = update { copy(afterWorkout = value) }
    fun setUnusualHydration(value: Boolean) = update { copy(unusualHydration = value) }
    fun setConditionNote(value: String) = update { copy(conditionNote = value) }

    fun setRetainOriginal(value: Boolean) {
        val finishWithoutOriginal = !value && savedMeasurementAwaitingOriginal != null
        _uiState.update { if (it.isBusy) it else it.copy(retainOriginal = value, saveError = null) }
        if (finishWithoutOriginal) viewModelScope.launch {
            discardSelectedSource()
            savedMeasurementAwaitingOriginal = null
            _finished.send(Unit)
        }
    }

    fun removeReadyOriginals() = viewModelScope.launch {
        val id = existingMeasurement?.id ?: return@launch
        if (_uiState.value.isBusy) return@launch
        _uiState.update { it.copy(isSaving = true, saveError = null) }
        try {
            val repository = measurementDocumentRepository ?: error("Хранилище оригиналов недоступно")
            repository.deleteAll(id)
            val actualCount = repository.readyForMeasurement(id).size
            _uiState.update { it.copy(isSaving = false, readyOriginalCount = actualCount) }
        } catch (e: CancellationException) {
            _uiState.update { it.copy(isSaving = false) }
            throw e
        } catch (_: Exception) {
            // A failed removal must never pretend that files disappeared. A second repository
            // failure merely retains the last verified count and keeps the action retryable.
            val actualCount = runCatching {
                measurementDocumentRepository?.readyForMeasurement(id)?.size
            }.getOrNull() ?: _uiState.value.readyOriginalCount
            _uiState.update { it.copy(isSaving = false, readyOriginalCount = actualCount, saveError = "Не удалось удалить локальные оригиналы") }
        }
    }

    /** Screen disposal/back owns only the unpersisted selected source, never READY originals. */
    fun discardUnretainedSource() {
        clearCameraCapture()
        if (savedMeasurementAwaitingOriginal == null) {
            discardPendingSource()
            discardSelectedSource()
        }
    }

    /** Creates a reconstructable cache name; a URI/path never enters saveable ViewModel state. */
    fun beginCameraCapture(): String {
        clearCameraCapture()
        val token = "inbody-${UUID.randomUUID()}.jpg"
        val directory = applicationContext?.let { File(it.cacheDir, CAMERA_IMPORT_DIRECTORY) }
        if (captureRegistry != null && directory != null) captureRegistry.replaceWith(token, directory)
        else captureRegistry?.register(token)
        savedStateHandle[cameraTokenKey] = token
        _uiState.update { it.copy(pendingCameraToken = token) }
        return token
    }

    /** Consumes the token once the camera callback arrives, preserving it across rotation meanwhile. */
    fun consumeCameraCapture(): String? {
        val token = savedStateHandle.get<String>(cameraTokenKey)
        token?.let { captureRegistry?.clear(it) }
        savedStateHandle[cameraTokenKey] = null
        _uiState.update { it.copy(pendingCameraToken = null) }
        return token
    }

    fun clearCameraCapture() {
        val token = savedStateHandle.get<String>(cameraTokenKey) ?: return
        savedStateHandle[cameraTokenKey] = null
        _uiState.update { it.copy(pendingCameraToken = null) }
        val directory = applicationContext?.let { File(it.cacheDir, CAMERA_IMPORT_DIRECTORY) }
        if (captureRegistry != null && directory != null) captureRegistry.abandon(token, directory)
        else cameraFile(token)?.delete()
    }

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
    /** Consent is deliberately explicit per send; callers must not reuse a previous loopback choice. */
    fun scanInBody(uri: Uri, temporaryCameraFile: File? = null, allowLoopbackHttp: Boolean = false) {
        replacePendingSource(InBodySource(uri, temporaryCameraFile))
        startInBodyScan(pendingSource ?: return, allowLoopbackHttp)
    }

    private fun startInBodyScan(
        source: InBodySource,
        allowLoopbackHttp: Boolean,
        disclosedConfiguration: AiApiRequestConfiguration? = null,
    ) {
        val state = _uiState.value
        if (state.isLoading || state.isBusy) return
        if (!state.isAiConfigured) {
            _uiState.update {
                it.copy(inBodyScanError = MISSING_CONFIGURATION_MESSAGE, inBodyScanModelUnavailable = false)
            }
            discardSource(source)
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
                val result = disclosedConfiguration?.let { configuration ->
                    inBodyReportAiReader.read(source.uri, configuration, allowLoopbackHttp)
                } ?: inBodyReportAiReader.read(source.uri, allowLoopbackHttp)
                when (result) {
                    is InBodyReportAiResult.Success -> _uiState.update { current ->
                        pendingSource = null
                        selectedOriginal = source
                        current.applyInBodyDraft(result.draft).copy(
                            isScanningInBody = false,
                            inBodyScanError = null,
                            inBodyScanModelUnavailable = false,
                            originalAvailable = true,
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
                discardSource(source)
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
                if (selectedOriginal !== source) {
                    pendingSource = null
                    discardSource(source)
                }
            }
        }
    }

    fun requestInBodyConsent(uri: Uri, temporaryCameraFile: File? = null, displayName: String = "Фото InBody", mimeType: String = "image/jpeg") { viewModelScope.launch {
        replacePendingSource(InBodySource(uri, temporaryCameraFile, displayName, mimeType))
        val config = aiApiConfigurationProvider.requestConfiguration() ?: run {
            discardPendingSource()
            _uiState.update { it.copy(inBodyScanError = MISSING_CONFIGURATION_MESSAGE) }
            return@launch
        }
        when (healthAiEndpointDecision(config.connection.baseUrl, false)) {
            HealthAiEndpointDecision.PublicHttpRejected -> { discardPendingSource(); _uiState.update { it.copy(inBodyScanError = "Снимок InBody нельзя отправить через публичный HTTP") } }
            HealthAiEndpointDecision.Invalid -> { discardPendingSource(); _uiState.update { it.copy(inBodyScanError = "Некорректный адрес нейросети") } }
            HealthAiEndpointDecision.Allowed, HealthAiEndpointDecision.LoopbackConsentRequired -> {
                pendingScanConfiguration = config
                _uiState.update { it.copy(scanDisclosure = InBodyRecipientDisclosure(android.net.Uri.parse(config.connection.baseUrl).host.orEmpty(), config.modelId, healthAiEndpointDecision(config.connection.baseUrl, false) == HealthAiEndpointDecision.LoopbackConsentRequired)) }
            }
        }
    } }
    fun cancelInBodyConsent() {
        pendingScanConfiguration = null
        discardPendingSource()
        _uiState.update { it.copy(scanDisclosure = null) }
    }
    fun confirmInBodyConsent() {
        val disclosure = _uiState.value.scanDisclosure ?: return
        val source = pendingSource ?: return
        val configuration = pendingScanConfiguration ?: return
        pendingScanConfiguration = null
        _uiState.update { it.copy(scanDisclosure = null) }
        startInBodyScan(source, allowLoopbackHttp = disclosure.loopback, disclosedConfiguration = configuration)
    }

    fun save() {
        val state = _uiState.value
        if (state.isLoading || state.isBusy || !state.canSave) return
        _uiState.update { it.copy(isSaving = true, saveError = null) }
        viewModelScope.launch {
            val old = existingMeasurement
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
                afterMeal = state.afterMeal,
                afterWorkout = state.afterWorkout,
                unusualHydration = state.unusualHydration,
                conditionNote = state.conditionNote.trim().takeIf(String::isNotBlank),
                uploadStatus = UploadStatus.PENDING,
                uploadError = null,
            )
            val awaitingOriginal = savedMeasurementAwaitingOriginal
            if (awaitingOriginal != null && entity.sameStructuredMeasurement(awaitingOriginal)) {
                saveOriginalAfterMeasurement(awaitingOriginal.id)
                return@launch
            }
            // A normal edit screen must not manufacture a snapshot merely because Save was tapped.
            // A newly selected private original is independent and can still be copied for this ID.
            if (old != null && entity.sameStructuredMeasurement(old)) {
                if (state.retainOriginal && selectedOriginal != null) {
                    savedMeasurementAwaitingOriginal = old
                    saveOriginalAfterMeasurement(old.id)
                } else {
                    discardSelectedSource()
                    _uiState.update { it.copy(isSaving = false) }
                    _finished.send(Unit)
                }
                return@launch
            }
            try {
                measurementRepository?.save(entity) ?: run {
                    if (old == null) bodyMeasurementDao.insert(entity) else bodyMeasurementDao.update(entity)
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isSaving = false) }
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(isSaving = false, saveError = GENERIC_SAVE_FAILURE_MESSAGE) }
                return@launch
            }
            existingMeasurement = entity
            uploadScheduler.schedule(id)
            if (state.retainOriginal && selectedOriginal != null) {
                savedMeasurementAwaitingOriginal = entity
                saveOriginalAfterMeasurement(id)
            } else {
                discardSelectedSource()
                _uiState.update { it.copy(isSaving = false) }
                _finished.send(Unit)
            }
        }
    }

    fun delete() {
        if (_uiState.value.isBusy) return
        val id = existingMeasurement?.id ?: return
        viewModelScope.launch {
            measurementRepository?.delete(id) ?: bodyMeasurementDao.delete(id)
            uploadScheduler.schedule(id)
            _finished.send(Unit)
        }
    }

    private suspend fun saveOriginalAfterMeasurement(measurementId: String) {
        val source = selectedOriginal
        if (source == null || !_uiState.value.retainOriginal) {
            savedMeasurementAwaitingOriginal = null
            _uiState.update { it.copy(isSaving = false) }
            _finished.send(Unit)
            return
        }
        _uiState.update { it.copy(isSaving = true, saveError = null) }
        val result = measurementDocumentRepository?.storeUri(
            MeasurementDocumentInput(measurementId, source.displayName, source.mimeType),
            source.uri,
        ) ?: MeasurementDocumentStoreResult.Failure("Хранилище оригиналов недоступно")
        when (result) {
            is MeasurementDocumentStoreResult.Ready -> {
                discardSelectedSource()
                savedMeasurementAwaitingOriginal = null
                _uiState.update { it.copy(isSaving = false, originalAvailable = false, readyOriginalCount = it.readyOriginalCount + 1) }
                _finished.send(Unit)
            }
            is MeasurementDocumentStoreResult.Failure -> _uiState.update {
                it.copy(isSaving = false, saveError = "Замер сохранён, оригинал не удалось сохранить")
            }
        }
    }

    private fun replacePendingSource(source: InBodySource) {
        pendingScanConfiguration = null
        discardPendingSource()
        discardSelectedSource()
        pendingSource = source
        _uiState.update { it.copy(originalAvailable = false, retainOriginal = false, saveError = null) }
    }

    private fun discardPendingSource() {
        pendingScanConfiguration = null
        pendingSource?.let(::discardSource)
        pendingSource = null
    }

    private fun discardSelectedSource() {
        selectedOriginal?.let(::discardSource)
        selectedOriginal = null
        _uiState.update { it.copy(originalAvailable = false, retainOriginal = false) }
    }

    private fun discardSource(source: InBodySource) { source.temporaryCameraFile?.delete() }

    private fun cameraFile(token: String): File? = applicationContext?.let { context ->
        File(File(context.cacheDir, CAMERA_IMPORT_DIRECTORY), token)
    }

    override fun onCleared() {
        clearCameraCapture()
        discardPendingSource()
        discardSelectedSource()
        super.onCleared()
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
        const val CAMERA_IMPORT_DIRECTORY = "inbody_imports"
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

private data class InBodySource(
    val uri: Uri,
    val temporaryCameraFile: File? = null,
    val displayName: String = "Фото InBody",
    val mimeType: String = "image/jpeg",
)

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

private fun BodyMeasurementEntity.toEditorState(
    isAiConfigured: Boolean,
    readyOriginalCount: Int = 0,
): MeasurementEditorUiState =
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
        afterMeal = afterMeal,
        afterWorkout = afterWorkout,
        unusualHydration = unusualHydration,
        conditionNote = conditionNote.orEmpty(),
        readyOriginalCount = readyOriginalCount,
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

/** Upload presentation is not user data and must not turn an original-copy retry into a new edit. */
private fun BodyMeasurementEntity.sameStructuredMeasurement(other: BodyMeasurementEntity): Boolean =
    copy(uploadStatus = UploadStatus.PENDING, uploadError = null) ==
        other.copy(uploadStatus = UploadStatus.PENDING, uploadError = null)
