package com.valerochka1337.valerochkagym.data.health

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportSnapshotEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionSnapshotEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.domain.health.ConfirmRestrictionProposalsCommand
import com.valerochka1337.valerochkagym.domain.health.ConfirmedHealthReport
import com.valerochka1337.valerochkagym.domain.health.ConfirmedHealthRestriction
import com.valerochka1337.valerochkagym.domain.health.HealthInformationSource
import com.valerochka1337.valerochkagym.domain.health.HealthObservationDraft
import com.valerochka1337.valerochkagym.domain.health.HealthRawValue
import com.valerochka1337.valerochkagym.domain.health.HealthReportDraft
import com.valerochka1337.valerochkagym.domain.health.HealthReportStatus
import com.valerochka1337.valerochkagym.domain.health.HealthRestrictionDraft
import com.valerochka1337.valerochkagym.domain.health.HealthRestrictionState
import com.valerochka1337.valerochkagym.domain.health.ReportSaveCommand
import com.valerochka1337.valerochkagym.domain.health.ReportSaveResult
import com.valerochka1337.valerochkagym.domain.health.RestrictionConfirmationResult
import com.valerochka1337.valerochkagym.worker.HealthSyncScheduler
import com.valerochka1337.valerochkagym.worker.NoOpHealthSyncScheduler
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Sole transaction boundary for confirmed reports, observations and restrictions. */
@Singleton
class HealthRepository @Inject constructor(
    private val database: GymDatabase,
    private val healthSyncScheduler: HealthSyncScheduler = NoOpHealthSyncScheduler,
) {
    fun observeAllReports(): Flow<List<HealthReportEntity>> = database.healthDao().observeReportsForHistory()
    fun observeCurrentReports(): Flow<List<HealthReportEntity>> = database.healthDao().observeCurrentReports()
    fun observeAllRestrictions(): Flow<List<HealthRestrictionEntity>> = database.healthDao().observeRestrictionsForHistory()
    fun observeCurrentRestrictions(): Flow<List<HealthRestrictionEntity>> = database.healthDao().observeLiveRestrictions()

    /** Current non-revoked aggregate with its immutable revision and stable observation identities. */
    suspend fun correctionTarget(reportSyncId: String): ConfirmedHealthReport? {
        val report = database.healthDao().report(reportSyncId) ?: return null
        if (report.isTombstone) return null
        return confirmed(report, report.toDraft(database.healthDao().observations(reportSyncId)))
    }

    /** Compatibility projection for callers that have not yet adopted [correctionTarget]. */
    suspend fun correctionDraft(reportSyncId: String): HealthReportDraft? = correctionTarget(reportSyncId)?.draft

    /** Compatibility entrypoint for current UI. New callers should retain a [ReportSaveCommand]. */
    suspend fun saveConfirmedReport(draft: HealthReportDraft, now: Long): ConfirmedHealthReport =
        saveReport(ReportSaveCommand(UUID.randomUUID().toString(), UUID.randomUUID().toString(), draft), now).report

    /** A retry with the same operation/report identities returns the first committed revision. */
    suspend fun saveReport(command: ReportSaveCommand, now: Long): ReportSaveResult {
        require(command.operationId.isNotBlank())
        require(command.reportSyncId.isNotBlank())
        validateDraft(command.draft)
        val write = database.withTransaction {
            val dao = database.healthDao()
            dao.reportSnapshotForOperation(command.reportSyncId, command.operationId)?.let { snapshot ->
                return@withTransaction ReportWrite(replay(snapshot), null, true)
            }
            val current = dao.report(command.reportSyncId)
            require(command.correctionOfVersion == null || current?.version == command.correctionOfVersion) {
                "Correction must name the current report revision"
            }
            val version = (current?.version ?: 0) + 1
            val updatedAt = maxOf(now, (current?.updatedAt ?: Long.MIN_VALUE) + 1)
            val relation = command.correctionOfVersion
            val report = HealthReportEntity(
                syncId = command.reportSyncId, version = version, updatedAt = updatedAt,
                status = command.status.name, provenance = command.draft.provenance,
                reportedAt = command.draft.reportedAt, title = command.draft.title, note = command.draft.note,
                supersedesVersion = relation, correctionOfVersion = relation,
                collectedAt = command.draft.collectedAt, conditions = command.draft.conditions,
                originalExpected = command.draft.originalExpected,
            )
            val prior = dao.observations(command.reportSyncId).associateBy { it.syncId }
            val observations = command.draft.observations.map { draft ->
                val previous = draft.observationSyncId?.let(prior::get)
                HealthObservationEntity(
                    syncId = previous?.syncId ?: UUID.randomUUID().toString(), reportSyncId = command.reportSyncId,
                    version = (previous?.version ?: 0) + 1, updatedAt = updatedAt, observedAt = draft.observedAt,
                    rawName = draft.rawName, valueType = draft.value.kind.name, rawValue = draft.value.raw,
                    unit = draft.unit, referenceRange = draft.referenceRange, method = draft.method,
                    material = draft.material, source = draft.source,
                    canonicalKey = draft.canonicalKey.takeIf { draft.canonicalKeyAccepted }, sourcePage = draft.sourcePage,
                )
            }
            require(observations.map(HealthObservationEntity::syncId).distinct().size == observations.size)
            dao.upsertReport(report)
            dao.deleteObservations(command.reportSyncId)
            dao.insertObservations(observations)
            val payload = HealthSyncPayloadCodec.report(report, observations)
            val outbox = outbox(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, report.syncId, report.version, updatedAt, payload)
            dao.insertReportSnapshot(HealthReportSnapshotEntity(report.syncId, report.version, updatedAt, false, payload, outbox.payloadHash, command.operationId))
            dao.insertOutbox(outbox)
            ReportWrite(confirmed(report, command.draft), outbox, false)
        }
        write.outbox?.let { healthSyncScheduler.schedule(it) }
        return if (write.replayed) ReportSaveResult.Replayed(write.report) else ReportSaveResult.Saved(write.report)
    }

    suspend fun correct(reportSyncId: String, draft: HealthReportDraft, now: Long): ConfirmedHealthReport {
        val current = requireNotNull(database.healthDao().report(reportSyncId)) { "Unknown health report" }
        return saveReport(
            ReportSaveCommand(UUID.randomUUID().toString(), reportSyncId, draft, HealthReportStatus.CORRECTED, current.version), now,
        ).report
    }

    suspend fun revoke(reportSyncId: String, now: Long, operationId: String = UUID.randomUUID().toString()): ConfirmedHealthReport {
        val write = database.withTransaction {
            val dao = database.healthDao()
            dao.reportSnapshotForOperation(reportSyncId, operationId)?.let { return@withTransaction ReportWrite(replay(it), null, true) }
            val previous = requireNotNull(dao.report(reportSyncId)) { "Unknown health report" }
            val updatedAt = maxOf(now, previous.updatedAt + 1)
            val revoked = previous.copy(
                version = previous.version + 1, updatedAt = updatedAt, status = HealthReportStatus.REVOKED.name,
                isTombstone = true, supersedesVersion = previous.version, correctionOfVersion = previous.version,
            )
            dao.upsertReport(revoked)
            dao.deleteObservations(revoked.syncId)
            val payload = HealthSyncPayloadCodec.report(revoked, emptyList())
            val outbox = outbox(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, revoked.syncId, revoked.version, updatedAt, payload)
            dao.insertReportSnapshot(HealthReportSnapshotEntity(revoked.syncId, revoked.version, updatedAt, true, payload, outbox.payloadHash, operationId))
            dao.insertOutbox(outbox)
            ReportWrite(confirmed(revoked, revoked.toDraft(emptyList())), outbox, false)
        }
        write.outbox?.let { healthSyncScheduler.schedule(it) }
        return write.report
    }

    /** Compatibility entrypoint: its input is already explicitly confirmed. */
    suspend fun saveRestriction(restriction: ConfirmedHealthRestriction, originalText: String? = null) {
        saveRestrictionEntity(restriction.toEntity(originalText), "restriction:${restriction.syncId}:${restriction.version}", schedule = false)
            ?.let { healthSyncScheduler.schedule(it) }
    }

    /** All proposals persist atomically, or no free-text proposal becomes active. */
    suspend fun confirmRestrictionProposals(
        command: ConfirmRestrictionProposalsCommand,
        now: Long,
    ): RestrictionConfirmationResult {
        require(command.operationId.isNotBlank())
        require(command.proposals.isNotEmpty())
        require(command.proposals.map { it.proposalId }.distinct().size == command.proposals.size)
        val write = database.withTransaction {
            val dao = database.healthDao()
            val retries = dao.restrictionSnapshotsForOperation(command.operationId)
            if (retries.isNotEmpty()) return@withTransaction RestrictionWrite(
                retries.mapNotNull { HealthSyncPayloadCodec.decodeRestriction(it.canonicalPayload)?.toConfirmed() }, emptyList(), true,
            )
            val entries = mutableListOf<HealthSyncOutboxEntity>()
            val restrictions = command.proposals.map { proposal ->
                require(proposal.description.isNotBlank())
                require(dao.restriction(proposal.proposalId) == null) { "Restriction identity already exists" }
                val entity = HealthRestrictionEntity(
                    syncId = proposal.proposalId, version = 1, updatedAt = now, status = proposal.state.name,
                    source = proposal.source.name, confirmedAt = now, startsAt = proposal.startsAt, reviewAt = proposal.reviewAt,
                    description = proposal.description, originalText = command.originalText?.takeIf(String::isNotBlank),
                )
                val payload = HealthSyncPayloadCodec.restriction(entity)
                val entry = outbox(HealthSyncCategory.HEALTH_RESTRICTIONS, entity.syncId, entity.version, entity.updatedAt, payload)
                dao.upsertRestriction(entity)
                dao.insertRestrictionSnapshot(
                    HealthRestrictionSnapshotEntity(entity.syncId, entity.version, entity.updatedAt, entity.isTombstone, payload, entry.payloadHash, entity.originalText, command.operationId),
                )
                dao.insertOutbox(entry)
                entries += entry
                entity.toConfirmed()
            }
            RestrictionWrite(restrictions, entries, false)
        }
        write.entries.forEach { healthSyncScheduler.schedule(it) }
        return if (write.replayed) RestrictionConfirmationResult.Replayed(write.restrictions)
        else RestrictionConfirmationResult.Confirmed(write.restrictions)
    }

    suspend fun liftRestriction(syncId: String, now: Long, operationId: String = UUID.randomUUID().toString()): ConfirmedHealthRestriction {
        val old = requireNotNull(database.healthDao().restriction(syncId)) { "Unknown health restriction" }
        val lifted = old.copy(version = old.version + 1, updatedAt = maxOf(now, old.updatedAt + 1), status = HealthRestrictionState.LIFTED.name, isTombstone = true)
        saveRestrictionEntity(lifted, operationId)?.let { healthSyncScheduler.schedule(it) }
        return lifted.toConfirmed()
    }

    private suspend fun saveRestrictionEntity(entity: HealthRestrictionEntity, operationId: String, schedule: Boolean = true): HealthSyncOutboxEntity? {
        val outbox = database.withTransaction {
            val dao = database.healthDao()
            if (dao.restrictionSnapshotsForOperation(operationId).isNotEmpty()) return@withTransaction null
            val stored = entity.copy(originalText = dao.restriction(entity.syncId)?.originalText ?: entity.originalText)
            val payload = HealthSyncPayloadCodec.restriction(stored)
            val entry = outbox(HealthSyncCategory.HEALTH_RESTRICTIONS, stored.syncId, stored.version, stored.updatedAt, payload)
            dao.upsertRestriction(stored)
            dao.insertRestrictionSnapshot(HealthRestrictionSnapshotEntity(stored.syncId, stored.version, stored.updatedAt, stored.isTombstone, payload, entry.payloadHash, stored.originalText, operationId))
            dao.insertOutbox(entry)
            entry
        }
        if (schedule) outbox?.let { healthSyncScheduler.schedule(it) }
        return outbox
    }

    private fun validateDraft(draft: HealthReportDraft) {
        require(draft.title.isNotBlank() && draft.provenance.isNotBlank() && draft.observations.isNotEmpty())
        require(draft.collectedAt == null || draft.collectedAt >= 0)
        draft.observations.forEach { require(it.rawName.isNotBlank() && it.value.raw.isNotBlank() && (it.sourcePage == null || it.sourcePage > 0)) }
    }

    private fun replay(snapshot: HealthReportSnapshotEntity): ConfirmedHealthReport {
        val aggregate = requireNotNull(HealthSyncPayloadCodec.decodeReport(snapshot.canonicalPayload))
        return confirmed(aggregate.report, aggregate.report.toDraft(aggregate.observations))
    }
    private fun confirmed(report: HealthReportEntity, draft: HealthReportDraft) = ConfirmedHealthReport(
        report.syncId, report.version, report.updatedAt, report.status.safeReportStatus(), report.supersedesVersion, draft, report.correctionOfVersion,
    )
    private fun outbox(category: String, syncId: String, version: Long, now: Long, payload: String) = HealthSyncOutboxEntity(category, syncId, version, payload, sha256(payload), "$syncId:$version", now)
    private data class ReportWrite(val report: ConfirmedHealthReport, val outbox: HealthSyncOutboxEntity?, val replayed: Boolean)
    private data class RestrictionWrite(val restrictions: List<ConfirmedHealthRestriction>, val entries: List<HealthSyncOutboxEntity>, val replayed: Boolean)
    private fun sha256(payload: String) = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
}

private fun String.safeReportStatus() = if (this == "CONFIRMED") HealthReportStatus.FINAL else HealthReportStatus.valueOf(this)
private fun HealthReportEntity.toDraft(observations: List<HealthObservationEntity>) = HealthReportDraft(title, provenance, reportedAt, observations.mapNotNull(HealthObservationEntity::toDraft), note, collectedAt, conditions, originalExpected)
private fun HealthObservationEntity.toDraft(): HealthObservationDraft? {
    val value = when (valueType) {
        "NUMBER" -> rawValue.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { HealthRawValue.Number(it, rawValue) }
        "NUMBER_WITH_OPERATOR" -> Regex("(<=|>=|<|>)(.+)").matchEntire(rawValue)?.let { match -> match.groupValues[2].toDoubleOrNull()?.takeIf(Double::isFinite)?.let { HealthRawValue.NumberWithOperator(match.groupValues[1], it, rawValue) } }
        "RANGE" -> HealthRawValue.Range(null, null, rawValue)
        "CATEGORY" -> HealthRawValue.Category(rawValue)
        "CODE" -> HealthRawValue.Code(rawValue)
        "TEXT" -> HealthRawValue.Text(rawValue)
        else -> null
    } ?: return null
    return HealthObservationDraft(rawName, value, unit, referenceRange, method, material, source, canonicalKey, observedAt, sourcePage, canonicalKey != null, syncId)
}
private fun ConfirmedHealthRestriction.toEntity(originalText: String?) = HealthRestrictionEntity(syncId, version, updatedAt, false, draft.state.name, draft.source.name, draft.confirmedAt, draft.startsAt, draft.reviewAt, draft.description, originalText)
private fun HealthRestrictionEntity.toConfirmed() = ConfirmedHealthRestriction(syncId, version, updatedAt, HealthRestrictionDraft(description, HealthRestrictionState.valueOf(status), HealthInformationSource.valueOf(source), confirmedAt, startsAt, reviewAt))
