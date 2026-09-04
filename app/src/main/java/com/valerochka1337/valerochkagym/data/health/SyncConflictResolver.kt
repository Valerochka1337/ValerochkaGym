package com.valerochka1337.valerochkagym.data.health

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportSnapshotEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionSnapshotEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.data.db.entity.MeasurementSnapshotEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.measurements.MeasurementRepository
import com.valerochka1337.valerochkagym.data.measurements.MeasurementDocumentRepository
import com.valerochka1337.valerochkagym.data.measurements.MeasurementSnapshotCodec
import com.valerochka1337.valerochkagym.worker.HealthSyncScheduler
import com.valerochka1337.valerochkagym.worker.NoOpHealthSyncScheduler
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves a durable equal-version split by appending a new, explicit successor version. */
enum class ConflictChoice { LOCAL, REMOTE }

sealed interface ConflictResolutionResult {
    data class Resolved(val outbox: HealthSyncOutboxEntity) : ConflictResolutionResult
    data object Missing : ConflictResolutionResult
    data object InvalidPayload : ConflictResolutionResult
}

@Singleton
class SyncConflictResolver @Inject constructor(
    private val database: GymDatabase,
    private val healthSyncScheduler: HealthSyncScheduler = NoOpHealthSyncScheduler,
    private val measurementDocumentRepository: MeasurementDocumentRepository? = null,
    private val measurementUploadScheduler: MeasurementUploadScheduler = NoOpMeasurementUploadScheduler,
) {
    suspend fun resolve(
        category: String,
        syncId: String,
        version: Long,
        choice: ConflictChoice,
        now: Long = System.currentTimeMillis(),
    ): ConflictResolutionResult {
        val conflict = database.healthDao().conflict(category, syncId, version)
            ?: return ConflictResolutionResult.Missing
        val payload = when (choice) {
            ConflictChoice.LOCAL -> conflict.localPayload
            ConflictChoice.REMOTE -> conflict.remotePayload
        }
        // Decode before starting the transaction so malformed remote state cannot partially alter
        // a projection, snapshot, outbox or its durable conflict marker.
        val expectedHash = when (choice) {
            ConflictChoice.LOCAL -> conflict.localPayloadHash
            ConflictChoice.REMOTE -> conflict.remotePayloadHash
        }
        // A resolver must never turn an arbitrary string into a successor. The selected side is
        // bound to this exact durable marker before any projection mutation begins.
        if (expectedHash == null || sha256(payload) != expectedHash) return ConflictResolutionResult.InvalidPayload
        val decoded = decode(category, syncId, version, payload) ?: return ConflictResolutionResult.InvalidPayload
        val deletedDocumentHashes = linkedSetOf<String>()
        val resolved = database.withTransaction {
            // A second resolver may have completed while the caller was decoding. Do not overwrite
            // its successor or delete a newer marker.
            val marker = database.healthDao().conflict(category, syncId, version)
                ?: return@withTransaction null
            val outbox = when (decoded) {
                is Decoded.Report -> resolveReport(marker.category, marker.syncId, marker.version, decoded, now)
                is Decoded.Restriction -> resolveRestriction(marker.category, marker.syncId, marker.version, decoded, now)
                is Decoded.Measurement -> resolveMeasurement(
                    marker.category,
                    marker.syncId,
                    marker.version,
                    decoded,
                    now,
                    deletedDocumentHashes,
                )
            }
            // Superseding a conflict makes the exact old delivery obsolete. Delete it in the
            // same transaction as the marker and successor so a delayed worker cannot upload it.
            database.healthSyncOutboxDao().acknowledge(marker.category, marker.syncId, marker.version)
            database.healthDao().deleteConflict(marker.category, marker.syncId, marker.version)
            outbox
        } ?: return ConflictResolutionResult.Missing
        // The conflict marker, replacement snapshot and FK metadata commit together. Only then
        // may an unreferenced private file be removed.
        if (deletedDocumentHashes.isNotEmpty()) {
            measurementDocumentRepository?.cleanupUnreferenced(deletedDocumentHashes)
        }
        when (resolved.category) {
            HealthSyncCategory.MEASUREMENTS -> measurementUploadScheduler.schedule(resolved.syncId)
            HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS,
            HealthSyncCategory.HEALTH_RESTRICTIONS -> healthSyncScheduler.schedule(resolved)
            else -> error("Unsupported conflict category ${resolved.category}")
        }
        return ConflictResolutionResult.Resolved(resolved)
    }

    private sealed interface Decoded {
        data class Report(val aggregate: HealthSyncPayloadCodec.ReportAggregate) : Decoded
        data class Restriction(val value: com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionEntity) : Decoded
        data class Measurement(val value: MeasurementSnapshotCodec.Decoded) : Decoded
    }

    private fun decode(category: String, syncId: String, version: Long, payload: String): Decoded? = when (category) {
        HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS -> HealthSyncPayloadCodec.decodeReport(payload)
            ?.takeIf { aggregate ->
                aggregate.report.syncId == syncId && aggregate.report.version == version &&
                    aggregate.observations.all { observation ->
                        observation.reportSyncId == syncId && observation.syncId.isNotBlank() && observation.version > 0
                    } && aggregate.observations.map(HealthObservationEntity::syncId).distinct().size == aggregate.observations.size &&
                    (aggregate.report.isTombstone || aggregate.observations.isNotEmpty())
            }?.let(Decoded::Report)
        HealthSyncCategory.HEALTH_RESTRICTIONS -> HealthSyncPayloadCodec.decodeRestriction(payload)
            ?.takeIf { it.syncId == syncId && it.version == version && it.status.isNotBlank() && it.source.isNotBlank() && it.description.isNotBlank() }
            ?.let(Decoded::Restriction)
        HealthSyncCategory.MEASUREMENTS -> MeasurementSnapshotCodec.decode(payload)
            ?.takeIf { it.measurement.id == syncId }
            ?.let(Decoded::Measurement)
        else -> null
    }

    private suspend fun resolveReport(
        category: String,
        syncId: String,
        conflictVersion: Long,
        decoded: Decoded.Report,
        now: Long,
    ): HealthSyncOutboxEntity {
        val latest = database.healthDao().reportSnapshots(syncId).firstOrNull()
        val current = database.healthDao().report(syncId)
        val predecessor = maxOf(latest?.version ?: 0, current?.version ?: 0)
        val newVersion = maxOf(predecessor, conflictVersion) + 1
        val updatedAt = maxOf(
            now,
            (latest?.updatedAt ?: Long.MIN_VALUE) + 1,
            (current?.updatedAt ?: Long.MIN_VALUE) + 1,
            decoded.aggregate.report.updatedAt + 1,
        )
        val report = decoded.aggregate.report.copy(
            version = newVersion,
            updatedAt = updatedAt,
            supersedesVersion = predecessor.takeIf { it > 0 },
        )
        val observations = if (report.isTombstone) emptyList() else decoded.aggregate.observations.map { source ->
            // The correction relation is aggregate-level; a logically same observation retains
            // its stable identity across a resolved successor.
            source.copy(version = source.version + 1, updatedAt = updatedAt, reportSyncId = syncId)
        }
        val payload = HealthSyncPayloadCodec.report(report, observations)
        val outbox = outbox(category, syncId, newVersion, updatedAt, payload)
        database.healthDao().upsertReport(report)
        database.healthDao().deleteObservations(syncId)
        if (observations.isNotEmpty()) database.healthDao().insertObservations(observations)
        database.healthDao().insertReportSnapshot(HealthReportSnapshotEntity(syncId, newVersion, updatedAt, report.isTombstone, payload, outbox.payloadHash))
        database.healthDao().insertOutbox(outbox)
        return outbox
    }

    private suspend fun resolveRestriction(
        category: String,
        syncId: String,
        conflictVersion: Long,
        decoded: Decoded.Restriction,
        now: Long,
    ): HealthSyncOutboxEntity {
        val latest = database.healthDao().restrictionSnapshots(syncId).firstOrNull()
        val current = database.healthDao().restriction(syncId)
        val newVersion = maxOf(latest?.version ?: 0, current?.version ?: 0, conflictVersion) + 1
        val updatedAt = maxOf(
            now,
            (latest?.updatedAt ?: Long.MIN_VALUE) + 1,
            (current?.updatedAt ?: Long.MIN_VALUE) + 1,
            decoded.value.updatedAt + 1,
        )
        // Neither sync choice carries originalText. Keep local private wording, including when
        // REMOTE wins structured state, instead of treating it as remotely authoritative data.
        val retainedOriginal = current?.originalText ?: latest?.originalText
        val restriction = decoded.value.copy(version = newVersion, updatedAt = updatedAt, originalText = retainedOriginal)
        val payload = HealthSyncPayloadCodec.restriction(restriction)
        val outbox = outbox(category, syncId, newVersion, updatedAt, payload)
        database.healthDao().upsertRestriction(restriction)
        database.healthDao().insertRestrictionSnapshot(HealthRestrictionSnapshotEntity(syncId, newVersion, updatedAt, restriction.isTombstone, payload, outbox.payloadHash, retainedOriginal))
        database.healthDao().insertOutbox(outbox)
        return outbox
    }

    private suspend fun resolveMeasurement(
        category: String,
        syncId: String,
        conflictVersion: Long,
        decoded: Decoded.Measurement,
        now: Long,
        deletedDocumentHashes: MutableSet<String>,
    ): HealthSyncOutboxEntity {
        val latest = database.healthDao().measurementSnapshots(syncId).firstOrNull()
        val newVersion = maxOf(latest?.version ?: 0, conflictVersion) + 1
        val updatedAt = maxOf(now, (latest?.updatedAt ?: Long.MIN_VALUE) + 1)
        val payload = MeasurementRepository.canonicalPayload(decoded.value.measurement, decoded.value.isTombstone)
        val outbox = outbox(category, syncId, newVersion, updatedAt, payload)
        val snapshot = MeasurementSnapshotEntity(syncId, newVersion, updatedAt, decoded.value.isTombstone, payload, outbox.payloadHash)
        if (decoded.value.isTombstone) {
            deletedDocumentHashes += database.measurementDocumentDao().forMeasurement(syncId)
                .filter { it.state == "READY" }
                .map { it.sha256 }
            database.bodyMeasurementDao().delete(syncId)
        } else {
            val projection = decoded.value.measurement.copy(uploadStatus = UploadStatus.PENDING, uploadError = null)
            if (database.bodyMeasurementDao().getById(syncId) == null) database.bodyMeasurementDao().insert(projection)
            else database.bodyMeasurementDao().update(projection)
        }
        database.healthDao().insertMeasurementHistory(snapshot, outbox)
        return outbox
    }

    private fun outbox(category: String, syncId: String, version: Long, updatedAt: Long, payload: String): HealthSyncOutboxEntity =
        HealthSyncOutboxEntity(
            category = category,
            syncId = syncId,
            version = version,
            canonicalPayload = payload,
            payloadHash = sha256(payload),
            idempotencyKey = "$syncId:$version",
            createdAt = updatedAt,
        )

    private fun sha256(payload: String): String = when (payload.firstOrNull()) {
        'v' -> MeasurementRepository.sha256(payload)
        else -> java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

private object NoOpMeasurementUploadScheduler : MeasurementUploadScheduler {
    override suspend fun schedule(measurementId: String) = Unit
    override suspend fun retry(measurementId: String) = Unit
    override suspend fun scheduleAllPending() = 0
}
