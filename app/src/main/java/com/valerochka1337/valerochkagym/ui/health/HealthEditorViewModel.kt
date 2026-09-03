package com.valerochka1337.valerochkagym.ui.health

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.ai.AiApiConfigurationProvider
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
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HealthObservationInput(
    val rawName: String = "", val rawValue: String = "", val valueKind: HealthRawValueKind = HealthRawValueKind.TEXT,
    val unit: String = "", val referenceRange: String = "", val method: String = "", val material: String = "", val source: String = "", val canonicalKey: String = "",
    val observedAt: Long = System.currentTimeMillis(), val sourcePage: Int? = null, val included: Boolean = true,
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
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HealthEditorUiState(correctsSyncId = savedStateHandle.get<String>(GymRoutes.HEALTH_CORRECTS_ID_ARG)))
    val uiState: StateFlow<HealthEditorUiState> = mutableState.asStateFlow()
    private val finishedEvents = Channel<Unit>(Channel.BUFFERED)
    val finished = finishedEvents.receiveAsFlow()
    init { mutableState.value.correctsSyncId?.let(::loadCorrection) }
    fun title(value: String) = mutableState.update { it.copy(title = value) }
    fun provenance(value: String) = mutableState.update { it.copy(provenance = value) }
    fun note(value: String) = mutableState.update { it.copy(note = value) }
    fun reportedAt(value: Long) = mutableState.update { it.copy(reportedAt = value) }
    fun updateObservation(index: Int, value: HealthObservationInput) = mutableState.update { it.copy(observations = it.observations.toMutableList().also { rows -> rows[index] = value }) }
    fun addObservation() = mutableState.update { it.copy(observations = it.observations + HealthObservationInput()) }
    fun removeObservation(index: Int) = mutableState.update { it.copy(observations = it.observations.filterIndexed { i, _ -> i != index }.ifEmpty { listOf(HealthObservationInput()) }) }
    private fun loadCorrection(syncId: String) = viewModelScope.launch { repository.correctionDraft(syncId)?.let { draft -> mutableState.update { it.copy(title = draft.title, provenance = draft.provenance, reportedAt = draft.reportedAt, note = draft.note.orEmpty(), observations = draft.observations.map { row -> row.toInput() }) } } ?: mutableState.update { it.copy(error = "Исследование для исправления недоступно") } }
    fun selectDocument(uri: Uri) { viewModelScope.launch { val configuration = configurationProvider.requestConfiguration() ?: run { mutableState.update { it.copy(error = "Настройте нейросеть в настройках") }; return@launch }; when (healthAiEndpointDecision(configuration.connection.baseUrl, false)) { HealthAiEndpointDecision.PublicHttpRejected -> mutableState.update { it.copy(error = "Медицинский документ нельзя отправить через публичный HTTP") }; HealthAiEndpointDecision.Invalid -> mutableState.update { it.copy(error = "Некорректный адрес нейросети") }; HealthAiEndpointDecision.Allowed, HealthAiEndpointDecision.LoopbackConsentRequired -> mutableState.update { it.copy(pendingUri = uri, disclosure = AiDisclosure(uri, Uri.parse(configuration.connection.baseUrl).host.orEmpty(), configuration.modelId, healthAiEndpointDecision(configuration.connection.baseUrl, false) == HealthAiEndpointDecision.LoopbackConsentRequired), error = null) } } } }
    fun cancelDisclosure() = mutableState.update { state -> state.copy(disclosure = null, pendingUri = if (state.disclosure != null) null else state.pendingUri) }
    fun retainOriginal(value: Boolean) = mutableState.update { it.copy(retainOriginal = value) }
    fun confirmDisclosure() { val disclosure = mutableState.value.disclosure ?: return; mutableState.update { it.copy(disclosure = null) }; readDocument(disclosure.uri, disclosure.loopbackWarning) }
    fun readDocument(uri: Uri, allowLoopbackHttp: Boolean) { viewModelScope.launch { mutableState.update { it.copy(reading = true, error = null, pendingUri = uri) }; when (val result = reader.read(uri, allowLoopbackHttp)) { is HealthReportAiResult.Success -> mutableState.update { state -> state.copy(title = result.draft.report.title, provenance = result.draft.report.provenance, reportedAt = result.draft.report.reportedAt, note = result.draft.report.note.orEmpty(), reading = false, observations = result.draft.report.observations.mapIndexed { index, row -> row.toInput(result.draft.sourcePages.getOrNull(index)) }) }; is HealthReportAiResult.Failure -> mutableState.update { it.copy(reading = false, error = result.message) } } } }
    fun save() { viewModelScope.launch { val state = mutableState.value; val observations = state.observations.filter { it.included }.map { it.toDraft() }; if (state.title.isBlank() || state.provenance.isBlank() || observations.isEmpty() || observations.any { it == null }) { mutableState.update { it.copy(error = "Заполните исследование; у каждого результата выберите корректный тип и значение") }; return@launch }; val draft = HealthReportDraft(state.title, state.provenance, state.reportedAt, observations.filterNotNull(), state.note.takeIf(String::isNotBlank)); mutableState.update { it.copy(saving = true, error = null) }; val report = try { if (state.correctsSyncId == null) repository.saveConfirmedReport(draft, System.currentTimeMillis()) else repository.correct(state.correctsSyncId, draft, System.currentTimeMillis()) } catch (_: Exception) { mutableState.update { it.copy(saving = false, error = "Не удалось сохранить исследование") }; return@launch }; if (state.retainOriginal && state.pendingUri != null && documents.storeUri(HealthDocumentInput(report.syncId, "medical_original", "application/octet-stream"), state.pendingUri) is HealthDocumentStoreResult.Failure) { mutableState.update { it.copy(saving = false, error = "Данные анализа сохранены, оригинал сохранить не удалось") }; return@launch }; mutableState.update { it.copy(saving = false) }; finishedEvents.send(Unit) } }
}
private fun HealthObservationDraft.toInput(page: Int? = sourcePage) = HealthObservationInput(rawName, value.raw, value.kind, unit.orEmpty(), referenceRange.orEmpty(), method.orEmpty(), material.orEmpty(), source.orEmpty(), canonicalKey.orEmpty(), observedAt, page)
private fun HealthObservationInput.toDraft(): HealthObservationDraft? { if (rawName.isBlank() || rawValue.isBlank() || sourcePage?.let { it <= 0 } == true) return null; val value = when (valueKind) { HealthRawValueKind.NUMBER -> rawValue.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { HealthRawValue.Number(it, rawValue) }; HealthRawValueKind.NUMBER_WITH_OPERATOR -> Regex("(<=|>=|<|>)(.+)").matchEntire(rawValue)?.let { m -> m.groupValues[2].toDoubleOrNull()?.takeIf(Double::isFinite)?.let { HealthRawValue.NumberWithOperator(m.groupValues[1], it) } }; HealthRawValueKind.RANGE -> HealthRawValue.Range(null, null, rawValue); HealthRawValueKind.CATEGORY -> HealthRawValue.Category(rawValue); HealthRawValueKind.CODE -> HealthRawValue.Code(rawValue); HealthRawValueKind.TEXT -> HealthRawValue.Text(rawValue) } ?: return null; return HealthObservationDraft(rawName, value, unit.takeIf(String::isNotBlank), referenceRange.takeIf(String::isNotBlank), method.takeIf(String::isNotBlank), material.takeIf(String::isNotBlank), source.takeIf(String::isNotBlank), canonicalKey.takeIf(String::isNotBlank), observedAt, sourcePage) }
