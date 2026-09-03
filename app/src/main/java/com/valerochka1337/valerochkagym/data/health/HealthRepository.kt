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
import com.valerochka1337.valerochkagym.domain.health.ConfirmedHealthReport
import com.valerochka1337.valerochkagym.domain.health.ConfirmedHealthRestriction
import com.valerochka1337.valerochkagym.domain.health.HealthReportDraft
import com.valerochka1337.valerochkagym.domain.health.HealthReportStatus
import com.valerochka1337.valerochkagym.worker.HealthSyncScheduler
import com.valerochka1337.valerochkagym.worker.NoOpHealthSyncScheduler
import java.util.UUID
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthRepository @Inject constructor(
    private val database: GymDatabase,
    private val healthSyncScheduler: HealthSyncScheduler = NoOpHealthSyncScheduler,
) {
    suspend fun correctionDraft(reportSyncId: String): HealthReportDraft? {
        val report = database.healthDao().report(reportSyncId) ?: return null
        if (report.isTombstone) return null
        val observations = database.healthDao().observations(reportSyncId).mapNotNull { observation ->
            observation.toDraft()
        }
        return HealthReportDraft(report.title, report.provenance, report.reportedAt, observations, report.note)
    }
    suspend fun saveConfirmedReport(draft: HealthReportDraft, now: Long): ConfirmedHealthReport =
        saveReport(syncId = UUID.randomUUID().toString(), version = 1, now = now, draft = draft, status = HealthReportStatus.CONFIRMED, supersedesVersion = null)

    suspend fun correct(reportSyncId: String, draft: HealthReportDraft, now: Long): ConfirmedHealthReport {
        val previous = requireNotNull(database.healthDao().report(reportSyncId)) { "Unknown health report" }
        return saveReport(previous.syncId, previous.version + 1, now, draft, HealthReportStatus.CORRECTED, previous.version)
    }

    suspend fun revoke(reportSyncId: String, now: Long) {
        val outbox = database.withTransaction {
            val previous = requireNotNull(database.healthDao().report(reportSyncId)) { "Unknown health report" }
            val revoked = previous.copy(
                version = previous.version + 1,
                updatedAt = maxOf(now, previous.updatedAt + 1),
                status = HealthReportStatus.REVOKED.name,
                isTombstone = true,
                // The tombstone supersedes the version it revoked, not the version that
                // happened to precede an earlier correction.
                supersedesVersion = previous.version,
            )
            database.healthDao().upsertReport(revoked)
            database.healthDao().deleteObservations(revoked.syncId)
            val payload = HealthSyncPayloadCodec.report(revoked, emptyList())
            val entry = HealthSyncOutboxEntity(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, revoked.syncId, revoked.version, payload, sha256(payload), "${revoked.syncId}:${revoked.version}", revoked.updatedAt)
            database.healthDao().insertReportSnapshot(HealthReportSnapshotEntity(revoked.syncId, revoked.version, revoked.updatedAt, true, payload, entry.payloadHash))
            database.healthDao().insertOutbox(entry)
            entry
        }
        healthSyncScheduler.schedule(outbox)
    }

    /** [originalText] is local private context; it never enters the sync payload or Sheets row. */
    suspend fun saveRestriction(restriction: ConfirmedHealthRestriction, originalText: String? = null) {
        val outbox = database.withTransaction {
            require(restriction.draft.description.isNotBlank()) { "Restriction requires confirmed text" }
            val existing = database.healthDao().restriction(restriction.syncId)
            // A revision cannot silently rewrite the original wording that was explicitly saved
            // with the first confirmation of this stable restriction identity.
            val retainedOriginal = existing?.originalText ?: originalText?.takeIf(String::isNotBlank)
            val entity = HealthRestrictionEntity(
                syncId = restriction.syncId, version = restriction.version, updatedAt = restriction.updatedAt,
                status = restriction.draft.state.name, source = restriction.draft.source.name,
                confirmedAt = restriction.draft.confirmedAt, startsAt = restriction.draft.startsAt,
                reviewAt = restriction.draft.reviewAt, description = restriction.draft.description,
                originalText = retainedOriginal,
            )
            database.healthDao().upsertRestriction(entity)
            val payload = HealthSyncPayloadCodec.restriction(entity)
            val entry = HealthSyncOutboxEntity(
                HealthSyncCategory.HEALTH_RESTRICTIONS, entity.syncId, entity.version,
                payload, sha256(payload),
                "${entity.syncId}:${entity.version}", entity.updatedAt,
            )
            database.healthDao().insertRestrictionSnapshot(HealthRestrictionSnapshotEntity(entity.syncId, entity.version, entity.updatedAt, entity.isTombstone, payload, entry.payloadHash, retainedOriginal))
            database.healthDao().insertOutbox(entry)
            entry
        }
        healthSyncScheduler.schedule(outbox)
    }

    private suspend fun saveReport(
        syncId: String, version: Long, now: Long, draft: HealthReportDraft,
        status: HealthReportStatus, supersedesVersion: Long?,
    ): ConfirmedHealthReport {
        val saved = database.withTransaction {
            require(draft.title.isNotBlank())
            require(draft.observations.isNotEmpty())
            val report = HealthReportEntity(syncId, version, now, false, status.name, draft.provenance, draft.reportedAt, draft.title, draft.note, supersedesVersion)
            database.healthDao().upsertReport(report)
            database.healthDao().deleteObservations(syncId)
            val observationEntities = draft.observations.map { observation ->
                HealthObservationEntity(
                    syncId = UUID.randomUUID().toString(), reportSyncId = syncId, version = 1, updatedAt = now,
                    observedAt = observation.observedAt, rawName = observation.rawName, valueType = observation.value.kind.name,
                    rawValue = observation.value.raw, unit = observation.unit, referenceRange = observation.referenceRange,
                    method = observation.method, material = observation.material, source = observation.source,
                    canonicalKey = observation.canonicalKey, sourcePage = observation.sourcePage,
                )
            }
            database.healthDao().insertObservations(observationEntities)
            val payload = HealthSyncPayloadCodec.report(report, observationEntities)
            val outbox = HealthSyncOutboxEntity(
                HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, syncId, version,
                payload, sha256(payload), "$syncId:$version", now,
            )
            database.healthDao().insertReportSnapshot(HealthReportSnapshotEntity(syncId, version, now, false, payload, outbox.payloadHash))
            database.healthDao().insertOutbox(outbox)
            ConfirmedHealthReport(syncId, version, now, status, supersedesVersion, draft) to outbox
        }
        healthSyncScheduler.schedule(saved.second)
        return saved.first
    }

    private fun sha256(payload: String): String = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private fun HealthObservationEntity.toDraft(): com.valerochka1337.valerochkagym.domain.health.HealthObservationDraft? {
    val value = when (valueType) {
        "NUMBER" -> rawValue.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { com.valerochka1337.valerochkagym.domain.health.HealthRawValue.Number(it, rawValue) }
        "NUMBER_WITH_OPERATOR" -> Regex("(<=|>=|<|>)(.+)").matchEntire(rawValue)?.let { match -> match.groupValues[2].toDoubleOrNull()?.takeIf(Double::isFinite)?.let { com.valerochka1337.valerochkagym.domain.health.HealthRawValue.NumberWithOperator(match.groupValues[1], it) } }
        "RANGE" -> com.valerochka1337.valerochkagym.domain.health.HealthRawValue.Range(null, null, rawValue)
        "CATEGORY" -> com.valerochka1337.valerochkagym.domain.health.HealthRawValue.Category(rawValue)
        "CODE" -> com.valerochka1337.valerochkagym.domain.health.HealthRawValue.Code(rawValue)
        "TEXT" -> com.valerochka1337.valerochkagym.domain.health.HealthRawValue.Text(rawValue)
        else -> null
    } ?: return null
    return com.valerochka1337.valerochkagym.domain.health.HealthObservationDraft(rawName, value, unit, referenceRange, method, material, source, canonicalKey, observedAt, sourcePage)
}
