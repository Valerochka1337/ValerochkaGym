package com.valerochka1337.valerochkagym.ui.health

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.HealthDao
import com.valerochka1337.valerochkagym.data.db.entity.HealthDocumentEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncConflictEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportSnapshotEntity
import com.valerochka1337.valerochkagym.data.health.HealthRepository
import com.valerochka1337.valerochkagym.data.health.HealthDocumentRepository
import com.valerochka1337.valerochkagym.domain.health.HealthObservationDraft
import com.valerochka1337.valerochkagym.domain.health.HealthRawValue
import com.valerochka1337.valerochkagym.domain.health.HealthTrendCalculator
import com.valerochka1337.valerochkagym.domain.health.HealthTrendSeries
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HealthReportDetailState(
    val report: HealthReportEntity? = null,
    val observations: List<HealthObservationEntity> = emptyList(),
    val documents: List<HealthDocumentEntity> = emptyList(),
    val comparableSeries: List<HealthTrendSeries> = emptyList(),
    val incompatibilities: List<HealthTrendIncompatibility> = emptyList(),
    val conflicts: List<HealthSyncConflictEntity> = emptyList(),
    val history: List<HealthReportSnapshotEntity> = emptyList(),
    val deleting: Boolean = false,
    val structuredDeleted: Boolean = false,
    val pendingOriginalDeletionIds: Set<String> = emptySet(),
    val deletionError: String? = null,
) {
    val hasReadyOriginal: Boolean get() = documents.any { it.state == "READY" }
    val missingOriginal: Boolean get() = report?.provenance == "DOCUMENT" && !hasReadyOriginal
}

/** Same canonical key is not a trend when its measurement context differs. */
data class HealthTrendIncompatibility(
    val canonicalKey: String,
    val reason: String,
)

/** The detail is keyed by sync id; historical values are grouped only by strict comparable keys. */
@HiltViewModel
class HealthReportDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    healthDao: HealthDao,
    private val repository: HealthRepository,
    private val documentsRepository: HealthDocumentRepository,
) : ViewModel() {
    private val reportId: String = savedStateHandle.get<String>(GymRoutes.HEALTH_REPORT_ID_ARG).orEmpty()
    private val periodStart = savedStateHandle.get<Long>(GymRoutes.HEALTH_PERIOD_START_ARG) ?: Long.MIN_VALUE
    private val periodEnd = savedStateHandle.get<Long>(GymRoutes.HEALTH_PERIOD_END_ARG) ?: Long.MAX_VALUE

    private val deletion = MutableStateFlow(DeleteState())
    private val eventsChannel = Channel<HealthReportDeleteEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()
    private val selectedReport = combine(
        healthDao.observeReport(reportId),
        healthDao.observeObservations(reportId),
        healthDao.observeDocuments(reportId),
        healthDao.observeReportSnapshots(reportId),
    ) { report, observations, documents, snapshots ->
        SelectedReport(report, observations, documents, snapshots)
    }
    private val detail = combine(
        selectedReport,
        healthDao.observeObservationsForHistory(),
        healthDao.observeConflictsForReport(reportId),
    ) { selected, history, conflicts ->
        val report = selected.report
        val observations = selected.observations
        val selectedCanonicalKeys = observations.mapNotNull(HealthObservationEntity::canonicalKey).toSet()
        val selectedDrafts = observations.mapNotNull(HealthObservationEntity::toDraft)
        val matchingHistory = history
            .filter { it.observedAt in periodStart..periodEnd }
            .filter { it.canonicalKey in selectedCanonicalKeys }
            .mapNotNull(HealthObservationEntity::toDraft)
        HealthReportDetailState(
            report = report,
            observations = observations,
            documents = selected.documents,
            comparableSeries = HealthTrendCalculator.series(
                matchingHistory,
            ),
            incompatibilities = trendIncompatibilities(selectedDrafts + matchingHistory),
            conflicts = conflicts,
            history = selected.snapshots,
        )
    }

    private data class SelectedReport(
        val report: HealthReportEntity?,
        val observations: List<HealthObservationEntity>,
        val documents: List<HealthDocumentEntity>,
        val snapshots: List<HealthReportSnapshotEntity>,
    )
    val uiState: StateFlow<HealthReportDetailState> = combine(detail, deletion) { detail, deletion ->
        detail.copy(deleting = deletion.deleting, structuredDeleted = deletion.structuredDeleted, pendingOriginalDeletionIds = deletion.pendingOriginalDeletionIds, deletionError = deletion.error)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        HealthReportDetailState(),
    )

    fun deleteReport(deleteOriginals: Boolean) = viewModelScope.launch {
        if (deletion.value.deleting || deletion.value.structuredDeleted) return@launch
        val report = uiState.value.report ?: return@launch
        deletion.value = DeleteState(deleting = true)
        try { repository.revoke(report.syncId, System.currentTimeMillis()) } catch (_: Exception) {
            deletion.value = DeleteState(error = "Не удалось удалить исследование")
            eventsChannel.send(HealthReportDeleteEvent.Failure("Не удалось удалить исследование")); return@launch
        }
        val failedIds = if (deleteOriginals) uiState.value.documents.filter { it.state == "READY" }
            .filterNot { documentsRepository.delete(it.id) }.mapTo(linkedSetOf()) { it.id } else emptySet()
        if (failedIds.isNotEmpty()) {
            val message = "Запись удалена, но оригинал удалить не удалось"
            deletion.value = DeleteState(structuredDeleted = true, pendingOriginalDeletionIds = failedIds, error = message)
            eventsChannel.send(HealthReportDeleteEvent.PartialFailure(message))
        } else {
            deletion.value = DeleteState(structuredDeleted = true)
            eventsChannel.send(HealthReportDeleteEvent.Success)
        }
    }

    /** Metadata remains after a failed deletion, making this retry idempotent and explicit. */
    fun retryOriginalDeletion() = viewModelScope.launch {
        val pending = deletion.value.pendingOriginalDeletionIds
        if (deletion.value.deleting || pending.isEmpty()) return@launch
        deletion.value = deletion.value.copy(deleting = true, error = null)
        val remaining = pending.filterNotTo(linkedSetOf()) { documentsRepository.delete(it) }
        if (remaining.isEmpty()) {
            deletion.value = DeleteState(structuredDeleted = true)
            eventsChannel.send(HealthReportDeleteEvent.Success)
        } else {
            val message = "Оригинал всё ещё не удалось удалить"
            deletion.value = DeleteState(structuredDeleted = true, pendingOriginalDeletionIds = remaining, error = message)
            eventsChannel.send(HealthReportDeleteEvent.PartialFailure(message))
        }
    }
}

private fun trendIncompatibilities(observations: List<HealthObservationDraft>): List<HealthTrendIncompatibility> =
    observations.filter { it.canonicalKey != null }.groupBy { it.canonicalKey!! }
        .mapNotNull { (key, values) ->
            val units = values.map { it.unit.orEmpty() }.distinct()
            val materials = values.map { it.material.orEmpty() }.distinct()
            val methods = values.map { it.method.orEmpty() }.distinct()
            val sources = values.map { it.source.orEmpty() }.distinct()
            val changed = buildList {
                if (units.size > 1) add("единицы: ${units.labelValues()}")
                if (materials.size > 1) add("материал: ${materials.labelValues()}")
                if (methods.size > 1) add("метод: ${methods.labelValues()}")
                if (sources.size > 1) add("источник: ${sources.labelValues()}")
            }
            changed.takeIf { it.isNotEmpty() }?.let {
                HealthTrendIncompatibility(key, "Показатели не объединены: отличаются ${it.joinToString()}")
            }
        }.sortedBy(HealthTrendIncompatibility::canonicalKey)

private fun List<String>.labelValues() = joinToString(" / ") { it.ifBlank { "не указан" } }

private data class DeleteState(
    val deleting: Boolean = false,
    val structuredDeleted: Boolean = false,
    val pendingOriginalDeletionIds: Set<String> = emptySet(),
    val error: String? = null,
)
sealed interface HealthReportDeleteEvent { data object Success : HealthReportDeleteEvent; data class PartialFailure(val message: String) : HealthReportDeleteEvent; data class Failure(val message: String) : HealthReportDeleteEvent }

private fun HealthObservationEntity.toDraft(): HealthObservationDraft? {
    val value = when (valueType) {
        "NUMBER" -> rawValue.toDoubleOrNull()?.takeIf(Double::isFinite)?.let(HealthRawValue::Number)
        "NUMBER_WITH_OPERATOR" -> Regex("(<=|>=|<|>)(.+)").matchEntire(rawValue)
            ?.let { match -> match.groupValues[2].toDoubleOrNull()?.takeIf(Double::isFinite)
                ?.let { value -> HealthRawValue.NumberWithOperator(match.groupValues[1], value, rawValue) } }
        "RANGE" -> HealthRawValue.Range(null, null, rawValue)
        "CATEGORY" -> HealthRawValue.Category(rawValue)
        "CODE" -> HealthRawValue.Code(rawValue)
        "TEXT" -> HealthRawValue.Text(rawValue)
        else -> null
    } ?: return null
    return HealthObservationDraft(rawName, value, unit, referenceRange, method, material, source, canonicalKey, observedAt, sourcePage)
}
