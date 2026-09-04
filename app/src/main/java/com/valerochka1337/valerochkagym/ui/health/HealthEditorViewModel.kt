package com.valerochka1337.valerochkagym.ui.health

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.ai.AiApiConfigurationProvider
import com.valerochka1337.valerochkagym.data.ai.AiApiRequestConfiguration
import com.valerochka1337.valerochkagym.data.ai.HealthAiEndpointDecision
import com.valerochka1337.valerochkagym.data.ai.HealthReportAiReader
import com.valerochka1337.valerochkagym.data.ai.HealthReportAiResult
import com.valerochka1337.valerochkagym.data.ai.healthAiEndpointDecision
import com.valerochka1337.valerochkagym.data.health.HealthDocumentInput
import com.valerochka1337.valerochkagym.data.health.HealthDocumentRepository
import com.valerochka1337.valerochkagym.data.health.HealthDocumentStoreResult
import com.valerochka1337.valerochkagym.data.health.HealthRepository
import com.valerochka1337.valerochkagym.domain.health.HealthObservationDraft
import com.valerochka1337.valerochkagym.domain.health.HealthRawValue
import com.valerochka1337.valerochkagym.domain.health.HealthRawValueKind
import com.valerochka1337.valerochkagym.domain.health.HealthReportDraft
import com.valerochka1337.valerochkagym.domain.health.HealthReportStatus
import com.valerochka1337.valerochkagym.domain.health.ReportSaveCommand
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class HealthObservationInput(
    val rawName: String = "", val rawValue: String = "", val valueKind: HealthRawValueKind = HealthRawValueKind.TEXT,
    val unit: String = "", val referenceRange: String = "", val method: String = "", val material: String = "", val source: String = "", val canonicalKey: String = "",
    val observedAt: Long = System.currentTimeMillis(), val sourcePage: Int? = null, val included: Boolean = true,
    val canonicalKeyAccepted: Boolean = false,
    val observationSyncId: String? = null,
)
data class AiDisclosure(val uri: Uri, val host: String, val model: String, val loopbackWarning: Boolean)
data class HealthEditorUiState(
    val title: String = "", val provenance: String = "", val reportedAt: Long = System.currentTimeMillis(), val note: String = "",
    val observations: List<HealthObservationInput> = listOf(HealthObservationInput()), val correctsSyncId: String? = null,
    val saving: Boolean = false, val reading: Boolean = false, val pendingUri: Uri? = null, val retainOriginal: Boolean = false, val disclosure: AiDisclosure? = null, val error: String? = null,
)

@HiltViewModel
class HealthEditorViewModel @Inject constructor(
    private val repository: HealthRepository, private val reader: HealthReportAiReader,
    private val configurationProvider: AiApiConfigurationProvider, private val documents: HealthDocumentRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HealthEditorUiState(correctsSyncId = savedStateHandle.get<String>(GymRoutes.HEALTH_CORRECTS_ID_ARG)))
    val uiState: StateFlow<HealthEditorUiState> = mutableState.asStateFlow()
    private val finishedEvents = Channel<Unit>(Channel.BUFFERED)
    val finished = finishedEvents.receiveAsFlow()
    /** Secret-bearing configuration never enters saved state; it is the exact disclosed request. */
    private var disclosedConfiguration: AiApiRequestConfiguration? = null
    private var saveOperationId: String? = savedStateHandle[NEW_REPORT_OPERATION_ID]
    private var reportSyncId: String? = savedStateHandle[NEW_REPORT_SYNC_ID]
    private var correctionVersion: Long? = savedStateHandle[CORRECTION_VERSION]
    init { mutableState.value.correctsSyncId?.let(::loadCorrection) }
    fun title(value: String) = mutableState.update { it.copy(title = value) }
    fun provenance(value: String) = mutableState.update { it.copy(provenance = value) }
    fun note(value: String) = mutableState.update { it.copy(note = value) }
    fun reportedAt(value: Long) = mutableState.update { it.copy(reportedAt = value) }
    fun updateObservation(index: Int, value: HealthObservationInput) = mutableState.update { it.copy(observations = it.observations.toMutableList().also { rows -> rows[index] = value }) }
    fun addObservation() = mutableState.update { it.copy(observations = it.observations + HealthObservationInput()) }
    fun removeObservation(index: Int) = mutableState.update { it.copy(observations = it.observations.filterIndexed { i, _ -> i != index }.ifEmpty { listOf(HealthObservationInput()) }) }
    private fun loadCorrection(syncId: String) = viewModelScope.launch {
        repository.correctionTarget(syncId)?.let { target ->
            correctionVersion = target.version
            savedStateHandle[CORRECTION_VERSION] = target.version
            if (savedStateHandle.get<String>(CORRECTION_OPERATION_ID) == null) {
                savedStateHandle[CORRECTION_OPERATION_ID] = UUID.randomUUID().toString()
            }
            mutableState.update { it.copy(title = target.draft.title, provenance = target.draft.provenance, reportedAt = target.draft.reportedAt, note = target.draft.note.orEmpty(), observations = target.draft.observations.map { row -> row.toInput() }) }
        } ?: mutableState.update { it.copy(error = "Исследование для исправления недоступно") }
    }
    fun selectDocument(uri: Uri) = viewModelScope.launch {
        val configuration = configurationProvider.requestConfiguration() ?: run {
            mutableState.update { it.copy(error = "Настройте нейросеть в настройках") }; return@launch
        }
        when (val decision = healthAiEndpointDecision(configuration.connection.baseUrl, false)) {
            HealthAiEndpointDecision.PublicHttpRejected -> mutableState.update { it.copy(error = "Медицинский документ нельзя отправить через публичный HTTP") }
            HealthAiEndpointDecision.Invalid -> mutableState.update { it.copy(error = "Некорректный адрес нейросети") }
            HealthAiEndpointDecision.Allowed, HealthAiEndpointDecision.LoopbackConsentRequired -> {
                disclosedConfiguration = configuration
                mutableState.update { it.copy(pendingUri = uri, disclosure = AiDisclosure(uri, Uri.parse(configuration.connection.baseUrl).host.orEmpty(), configuration.modelId, decision == HealthAiEndpointDecision.LoopbackConsentRequired), error = null) }
            }
        }
    }
    fun cancelDisclosure() { disclosedConfiguration = null; mutableState.update { state -> state.copy(disclosure = null, pendingUri = if (state.disclosure != null) null else state.pendingUri) } }
    fun retainOriginal(value: Boolean) = mutableState.update { it.copy(retainOriginal = value) }
    fun confirmDisclosure() { val disclosure = mutableState.value.disclosure ?: return; val configuration = disclosedConfiguration ?: return; disclosedConfiguration = null; mutableState.update { it.copy(disclosure = null) }; readDocument(disclosure.uri, configuration, disclosure.loopbackWarning) }
    fun readDocument(uri: Uri, allowLoopbackHttp: Boolean) = viewModelScope.launch { mutableState.update { it.copy(reading = true, error = null, pendingUri = uri) }; when (val result = reader.read(uri, allowLoopbackHttp)) { is HealthReportAiResult.Success -> mutableState.update { state -> state.copy(title = result.draft.report.title, provenance = result.draft.report.provenance, reportedAt = result.draft.report.reportedAt, note = result.draft.report.note.orEmpty(), reading = false, observations = result.draft.report.observations.mapIndexed { index, row -> row.toInput(result.draft.sourcePages.getOrNull(index)) }) }; is HealthReportAiResult.Failure -> mutableState.update { it.copy(reading = false, error = result.message) } } }
    private fun readDocument(uri: Uri, configuration: AiApiRequestConfiguration, allowLoopbackHttp: Boolean) = viewModelScope.launch { mutableState.update { it.copy(reading = true, error = null, pendingUri = uri) }; when (val result = reader.read(uri, configuration, allowLoopbackHttp)) { is HealthReportAiResult.Success -> mutableState.update { state -> state.copy(title = result.draft.report.title, provenance = result.draft.report.provenance, reportedAt = result.draft.report.reportedAt, note = result.draft.report.note.orEmpty(), reading = false, observations = result.draft.report.observations.mapIndexed { index, row -> row.toInput(result.draft.sourcePages.getOrNull(index)) }) }; is HealthReportAiResult.Failure -> mutableState.update { it.copy(reading = false, error = result.message) } } }
    fun save() = viewModelScope.launch {
        val state = mutableState.value
        val observations = state.observations.filter { it.included }.map { it.toDraft() }
        if (state.title.isBlank() || state.provenance.isBlank() || observations.isEmpty() || observations.any { it == null }) {
            mutableState.update { it.copy(error = "Заполните исследование; у каждого результата выберите корректный тип и значение") }; return@launch
        }
        val draft = HealthReportDraft(state.title, state.provenance, state.reportedAt, observations.filterNotNull(), state.note.takeIf(String::isNotBlank), originalExpected = state.retainOriginal)
        mutableState.update { it.copy(saving = true, error = null) }
        val report = try {
            val correctionId = state.correctsSyncId
            val operationId = if (correctionId == null) stableNewReportOperationId() else stableCorrectionOperationId()
            val targetId = correctionId ?: stableNewReportSyncId()
            val targetVersion = if (correctionId == null) null else correctionVersion
                ?: error("Исправляемая версия ещё загружается")
            repository.saveReport(ReportSaveCommand(
                operationId = operationId,
                reportSyncId = targetId,
                draft = draft,
                status = if (correctionId == null) HealthReportStatus.FINAL else HealthReportStatus.CORRECTED,
                correctionOfVersion = targetVersion,
            ), System.currentTimeMillis()).report
        } catch (_: Exception) { mutableState.update { it.copy(saving = false, error = "Не удалось сохранить исследование") }; return@launch }
        if (state.retainOriginal && state.pendingUri != null && documents.storeUri(HealthDocumentInput(report.syncId, "medical_original", "application/octet-stream"), state.pendingUri) is HealthDocumentStoreResult.Failure) {
            mutableState.update { it.copy(saving = false, error = "Данные анализа сохранены, оригинал сохранить не удалось") }; return@launch
        }
        mutableState.update { it.copy(saving = false) }; finishedEvents.send(Unit)
    }

    private fun stableNewReportOperationId(): String = saveOperationId ?: UUID.randomUUID().toString().also {
        saveOperationId = it; savedStateHandle[NEW_REPORT_OPERATION_ID] = it
    }
    private fun stableNewReportSyncId(): String = reportSyncId ?: UUID.randomUUID().toString().also {
        reportSyncId = it; savedStateHandle[NEW_REPORT_SYNC_ID] = it
    }
    private fun stableCorrectionOperationId(): String = savedStateHandle.get<String>(CORRECTION_OPERATION_ID) ?: UUID.randomUUID().toString().also {
        savedStateHandle[CORRECTION_OPERATION_ID] = it
    }

    private companion object {
        const val NEW_REPORT_OPERATION_ID = "health_new_report_operation_id"
        const val NEW_REPORT_SYNC_ID = "health_new_report_sync_id"
        const val CORRECTION_OPERATION_ID = "health_correction_operation_id"
        const val CORRECTION_VERSION = "health_correction_version"
    }
}
private fun HealthObservationDraft.toInput(page: Int? = sourcePage) = HealthObservationInput(rawName, value.raw, value.kind, unit.orEmpty(), referenceRange.orEmpty(), method.orEmpty(), material.orEmpty(), source.orEmpty(), canonicalKey.orEmpty(), observedAt, page, canonicalKeyAccepted = canonicalKeyAccepted, observationSyncId = observationSyncId)
private fun HealthObservationInput.toDraft(): HealthObservationDraft? { if (rawName.isBlank() || rawValue.isBlank() || sourcePage?.let { it <= 0 } == true) return null; val value = when (valueKind) { HealthRawValueKind.NUMBER -> rawValue.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { HealthRawValue.Number(it, rawValue) }; HealthRawValueKind.NUMBER_WITH_OPERATOR -> Regex("(<=|>=|<|>)(.+)").matchEntire(rawValue)?.let { m -> m.groupValues[2].toDoubleOrNull()?.takeIf(Double::isFinite)?.let { HealthRawValue.NumberWithOperator(m.groupValues[1], it, rawValue) } }; HealthRawValueKind.RANGE -> HealthRawValue.Range(null, null, rawValue); HealthRawValueKind.CATEGORY -> HealthRawValue.Category(rawValue); HealthRawValueKind.CODE -> HealthRawValue.Code(rawValue); HealthRawValueKind.TEXT -> HealthRawValue.Text(rawValue) } ?: return null; return HealthObservationDraft(rawName, value, unit.takeIf(String::isNotBlank), referenceRange.takeIf(String::isNotBlank), method.takeIf(String::isNotBlank), material.takeIf(String::isNotBlank), source.takeIf(String::isNotBlank), canonicalKey.takeIf(String::isNotBlank), observedAt, sourcePage, canonicalKeyAccepted, observationSyncId) }
