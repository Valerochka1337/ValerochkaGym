package com.valerochka1337.valerochkagym.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.health.HealthArchiveExporter
import com.valerochka1337.valerochkagym.data.health.HealthArchivePreview
import com.valerochka1337.valerochkagym.data.health.HealthArchiveSelection
import com.valerochka1337.valerochkagym.data.health.HealthArchiveExportResult
import com.valerochka1337.valerochkagym.data.health.MeasurementArchiveGroup
import com.valerochka1337.valerochkagym.data.db.dao.HealthDao
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.dao.MeasurementDocumentDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthDocumentEntity
import com.valerochka1337.valerochkagym.data.db.entity.MeasurementDocumentEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.io.OutputStream
import java.time.Clock
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object HealthArchiveClockModule {
    @Provides fun provideClock(): Clock = Clock.systemDefaultZone()
}

data class HealthArchiveUiState(
    val measurementIds: Set<String> = emptySet(),
    val reportIds: Set<String> = emptySet(),
    val restrictionIds: Set<String> = emptySet(),
    val documentIds: Set<String> = emptySet(),
    /** READY originals safe for the currently selected complete primary records. */
    val eligibleDocumentIds: Set<String> = emptySet(),
    val observationIds: Set<String> = emptySet(),
    val measurementGroups: Set<MeasurementArchiveGroup> = MeasurementArchiveGroup.entries.toSet(),
    val periodStart: Long = Long.MIN_VALUE,
    val periodEnd: Long = Long.MAX_VALUE,
    val periodLabel: String = "Всё время",
    val initializing: Boolean = true,
    val preview: HealthArchivePreview? = null,
    val exporting: Boolean = false,
    val success: String? = null,
    val error: String? = null,
)
data class ArchiveDocumentRow(val id: String, val owner: String, val name: String, val state: String, val isMeasurement: Boolean) {
    val selectable: Boolean get() = state == "READY"
}

@HiltViewModel
class HealthArchiveViewModel @Inject constructor(
    private val exporter: HealthArchiveExporter,
    private val healthDao: HealthDao,
    private val bodyMeasurementDao: BodyMeasurementDao,
    private val measurementDocumentDao: MeasurementDocumentDao? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(HealthArchiveUiState())
    val uiState: StateFlow<HealthArchiveUiState> = mutableState.asStateFlow()
    private var previewGeneration = 0L
    private var selectionRevision = 0L

    init { materializeInitialSelection() }
    fun toggleMeasurement(id: String) { mutateSelection { it.copy(measurementIds = it.measurementIds.toggle(id)) } }
    fun toggleReport(id: String) { mutateSelection { it.copy(reportIds = it.reportIds.toggle(id)) } }
    fun toggleRestriction(id: String) { mutateSelection { it.copy(restrictionIds = it.restrictionIds.toggle(id)) } }
    fun toggleDocument(id: String) { mutateSelection { state -> if (id in state.eligibleDocumentIds) state.copy(documentIds = state.documentIds.toggle(id)) else state } }
    fun toggleObservation(id: String) { mutateSelection { it.copy(observationIds = it.observationIds.toggle(id)) } }
    fun toggleMeasurementGroup(group: MeasurementArchiveGroup) { mutateSelection { it.copy(measurementGroups = it.measurementGroups.toggle(group)) } }
    fun refreshPreview() = viewModelScope.launch {
        val captured = mutableState.value
        if (captured.initializing || captured.exporting) return@launch
        val capturedSelection = selection(captured)
        val generation = ++previewGeneration
        try {
            val preview = exporter.preview(capturedSelection)
            // A slow obsolete preview must never overwrite a later selection or frozen export.
            if (generation == previewGeneration && !mutableState.value.exporting && selection(mutableState.value) == capturedSelection) {
                mutableState.update { it.copy(preview = preview) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (generation == previewGeneration) mutableState.update { it.copy(error = "Не удалось подготовить состав архива") }
        }
    }
    /** Called by the SAF host when the provider cannot open a caller-owned destination stream. */
    fun onOutputUnavailable() {
        if (!mutableState.value.exporting) mutableState.update { it.copy(error = "Не удалось открыть файл для архива") }
    }
    fun export(output: OutputStream) = viewModelScope.launch {
        val state = mutableState.value
        if (state.initializing) {
            runCatching { output.close() }
            mutableState.update { it.copy(error = "Состав архива ещё готовится") }
            return@launch
        }
        if (state.exporting) { runCatching { output.close() }; return@launch }
        val frozenSelection = selection(state)
        previewGeneration++
        mutableState.update { it.copy(exporting = true, error = null, success = null) }
        try {
            when (val result = exporter.export(output, frozenSelection)) {
                is HealthArchiveExportResult.Success -> mutableState.update { it.copy(exporting = false, success = "Архив создан: ${result.records} записей") }
                is HealthArchiveExportResult.Failure -> mutableState.update { it.copy(exporting = false, error = result.message) }
            }
        } catch (e: CancellationException) {
            mutableState.update { it.copy(exporting = false) }
            throw e
        } catch (_: Exception) {
            mutableState.update { it.copy(exporting = false, error = "Не удалось создать архив") }
        } finally {
            runCatching { output.close() }
        }
    }
    suspend fun reports() = healthDao.observeLiveReports().first()
    suspend fun restrictions() = healthDao.observeLiveRestrictions().first()
    suspend fun observations(reportId: String): List<HealthObservationEntity> = healthDao.observations(reportId).filterNot { it.isTombstone }
    suspend fun measurements(): List<BodyMeasurementEntity> = bodyMeasurementDao.observeAll().first()
    suspend fun documents(): List<ArchiveDocumentRow> {
        val health = (healthDao.documentsInState("READY") + healthDao.documentsInState("PENDING"))
            .map { ArchiveDocumentRow(it.id, "Исследование ${it.reportSyncId}", it.displayName, it.state, false) }
        val measurement = measurementDocumentDao?.let { dao ->
            measurements().flatMap { measurement -> dao.forMeasurement(measurement.id) }
                .map { ArchiveDocumentRow(it.id, "Замер ${it.measurementId}", it.displayName, it.state, true) }
        }.orEmpty()
        return (health + measurement).sortedBy { it.id }
    }
    fun setPeriod(start: Long, end: Long, label: String = "Выбранный период") { mutateSelection { it.copy(periodStart = start, periodEnd = end, periodLabel = label, preview = null) } }
    fun selectPeriod(days: Long?) {
        val end = clock.millis()
        if (days == null) setPeriod(Long.MIN_VALUE, Long.MAX_VALUE, "Всё время")
        else setPeriod(clock.instant().minus(days, ChronoUnit.DAYS).toEpochMilli(), end, "$days дней")
    }
    private fun materializeInitialSelection() = viewModelScope.launch {
        val measurements = measurements()
        val liveReports = reports()
        val restrictions = restrictions()
        val documents = healthDao.documentsInState("READY").mapTo(linkedSetOf()) { it.id } +
            (measurementDocumentDao?.let { dao -> measurements.flatMap { dao.forMeasurement(it.id) }.filter { it.state == "READY" }.map { it.id } } ?: emptyList())
        val observations = liveReports.flatMap { healthDao.observations(it.syncId) }
            .filterNot { it.isTombstone }.mapTo(linkedSetOf()) { it.syncId }
        // Empty is now an explicit "export nothing", never the ambiguous old "export all".
        mutableState.update {
            if (!it.initializing) it else it.copy(
                measurementIds = measurements.mapTo(linkedSetOf()) { it.id },
                reportIds = liveReports.mapTo(linkedSetOf()) { it.syncId },
                restrictionIds = restrictions.mapTo(linkedSetOf()) { it.syncId },
                documentIds = documents,
                eligibleDocumentIds = documents,
                observationIds = observations,
                initializing = false,
            )
        }
        val eligible = eligibleDocumentIds(mutableState.value)
        mutableState.update { current -> current.copy(eligibleDocumentIds = eligible, documentIds = current.documentIds intersect eligible) }
        refreshPreview()
    }
    private fun selection(state: HealthArchiveUiState) = HealthArchiveSelection(
        measurementIds = state.measurementIds, reportIds = state.reportIds, restrictionIds = state.restrictionIds,
        documentIds = state.documentIds, measurementGroups = state.measurementGroups,
        observationIds = state.observationIds, periodStart = state.periodStart, periodEnd = state.periodEnd,
    )
    private fun <T> Set<T>.toggle(id: T) = if (id in this) this - id else this + id
    private fun mutateSelection(change: (HealthArchiveUiState) -> HealthArchiveUiState) {
        if (mutableState.value.initializing || mutableState.value.exporting) return
        val revision = ++selectionRevision
        mutableState.update(change)
        viewModelScope.launch {
            val captured = mutableState.value
            val eligible = eligibleDocumentIds(captured)
            if (revision == selectionRevision && !mutableState.value.exporting) {
                mutableState.update { current ->
                    current.copy(
                        eligibleDocumentIds = eligible,
                        // Originals include all values: dropping any owner/result/metric group
                        // must immediately remove their private byte links from the selection.
                        documentIds = current.documentIds intersect eligible,
                    )
                }
                refreshPreview()
            }
        }
    }

    private suspend fun eligibleDocumentIds(state: HealthArchiveUiState): Set<String> {
        val selectedReports = reports().filter { it.syncId in state.reportIds && it.reportedAt in state.periodStart..state.periodEnd }
        val completeReportIds = selectedReports.filter { report ->
            healthDao.observations(report.syncId).filterNot { it.isTombstone }.all { it.syncId in state.observationIds }
        }.mapTo(linkedSetOf()) { it.syncId }
        val selectedMeasurements = measurements().filter { it.id in state.measurementIds && it.measuredAt in state.periodStart..state.periodEnd }
        val health = healthDao.documentsInState("READY")
            .filter { it.reportSyncId in completeReportIds }.mapTo(linkedSetOf()) { it.id }
        if (state.measurementGroups != MeasurementArchiveGroup.entries.toSet()) return health
        val measurement = measurementDocumentDao?.let { dao ->
            selectedMeasurements.flatMap { dao.forMeasurement(it.id) }.filter { it.state == "READY" }.map { it.id }
        }.orEmpty()
        return health + measurement
    }
}
