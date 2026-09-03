package com.valerochka1337.valerochkagym.data.measurements

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncConflictEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.data.db.entity.MeasurementSnapshotEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.domain.measurements.ParsedMeasurementConflict
import com.valerochka1337.valerochkagym.domain.measurements.ParsedMeasurementSnapshot
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** The only mutation boundary for the live measurement projection and its immutable history. */
@Singleton
class MeasurementRepository @Inject constructor(
    private val database: GymDatabase,
    private val documentRepository: MeasurementDocumentRepository? = null,
) {
    suspend fun save(measurement: BodyMeasurementEntity, now: Long = System.currentTimeMillis()): BodyMeasurementEntity =
        database.withTransaction {
            val current = database.bodyMeasurementDao().getById(measurement.id)
            val latest = database.healthDao().measurementSnapshots(measurement.id).firstOrNull()
            val version = (latest?.version ?: 0) + 1
            val updatedAt = maxOf(now, (latest?.updatedAt ?: Long.MIN_VALUE) + 1)
            val projection = measurement.copy(
                uploadStatus = UploadStatus.PENDING,
                uploadError = null,
                conditionNote = measurement.conditionNote?.trim()?.takeIf(String::isNotBlank),
            )
            if (current == null) database.bodyMeasurementDao().insert(projection)
            else database.bodyMeasurementDao().update(projection)
            appendSnapshot(projection, version, updatedAt, isTombstone = false)
            projection
        }

    suspend fun delete(measurementId: String, now: Long = System.currentTimeMillis()): Boolean {
        val documentHashes: Set<String>? = database.withTransaction {
            val current = database.bodyMeasurementDao().getById(measurementId) ?: return@withTransaction null
            val readyHashes = database.measurementDocumentDao().forMeasurement(measurementId)
                .filter { it.state == "READY" }
                .mapTo(linkedSetOf()) { it.sha256 }
            val latest = database.healthDao().measurementSnapshots(measurementId).firstOrNull()
            val version = (latest?.version ?: 0) + 1
            val updatedAt = maxOf(now, (latest?.updatedAt ?: Long.MIN_VALUE) + 1)
            database.bodyMeasurementDao().delete(measurementId)
            appendSnapshot(current, version, updatedAt, isTombstone = true)
            readyHashes
        }
        if (documentHashes == null) return false
        documentRepository?.cleanupUnreferenced(documentHashes)
        return true
    }

    /** Retry changes only the visible projection state; it never creates a duplicate snapshot. */
    suspend fun retry(measurementId: String) {
        database.bodyMeasurementDao().setUploadStatus(measurementId, UploadStatus.PENDING, null)
    }

    /** Applies a single remote snapshot through the same aggregate path as a Sheets import. */
    suspend fun applyImported(snapshot: ParsedMeasurementSnapshot): Boolean = applyImportedAggregate(listOf(snapshot))

    /**
     * Stores every accepted remote version in immutable history, then updates the live projection
     * exactly once from the maximal new version. Neither source row order nor timestamp can win.
     */
    suspend fun applyImportedAggregate(
        snapshots: List<ParsedMeasurementSnapshot>,
        /** An outer import transaction owns physical cleanup only after it commits. */
        deferredPostCommitCleanup: MutableSet<String>? = null,
    ): Boolean {
        val deletedDocumentHashes = linkedSetOf<String>()
        val historyChanged = database.withTransaction {
            if (snapshots.isEmpty()) return@withTransaction false
            val syncId = snapshots.first().measurement.id
            require(snapshots.all { it.measurement.id == syncId })
            val existing = database.healthDao().measurementSnapshots(syncId)
            val existingByVersion = existing.associateBy { it.version }.toMutableMap()
            val latestExistingVersion = existing.maxOfOrNull { it.version } ?: Long.MIN_VALUE
            var highestNewVersion: ParsedMeasurementSnapshot? = null
            var changed = false

            snapshots.sortedBy { it.version }.forEach { snapshot ->
                val payload = snapshot.canonicalPayload
                val local = existingByVersion[snapshot.version]
                when {
                    local == null -> {
                        database.healthDao().insertMeasurementSnapshot(
                            MeasurementSnapshotEntity(
                                snapshot.measurement.id, snapshot.version, snapshot.updatedAt,
                                snapshot.isDeleted, payload, snapshot.payloadHash,
                            ),
                        )
                        existingByVersion[snapshot.version] = MeasurementSnapshotEntity(
                            snapshot.measurement.id, snapshot.version, snapshot.updatedAt,
                            snapshot.isDeleted, payload, snapshot.payloadHash,
                        )
                        changed = true
                        if (snapshot.version > latestExistingVersion &&
                            snapshot.version > (highestNewVersion?.version ?: Long.MIN_VALUE)
                        ) {
                            highestNewVersion = snapshot
                        }
                    }
                    local.matches(payload, snapshot.payloadHash) -> Unit
                    else -> storeConflict(local, snapshot, payload)
                }
            }

            highestNewVersion?.let { applyLiveProjection(it, deletedDocumentHashes) }
            changed
        }
        // FK metadata is deleted atomically with the new tombstone. File cleanup is intentionally
        // after that commit so a failed import never removes a still-referenced original.
        if (historyChanged && deletedDocumentHashes.isNotEmpty()) {
            if (deferredPostCommitCleanup != null) deferredPostCommitCleanup += deletedDocumentHashes
            else documentRepository?.cleanupUnreferenced(deletedDocumentHashes)
        }
        return historyChanged
    }

    /** Called strictly after an outer import transaction has returned successfully. */
    suspend fun cleanupDeferredImportedDocuments(hashes: Set<String>) {
        if (hashes.isNotEmpty()) documentRepository?.cleanupUnreferenced(hashes)
    }

    /** Records a remote equal-version split before any current projection is touched. */
    suspend fun recordImportedConflict(conflict: ParsedMeasurementConflict) = database.withTransaction {
        val first = conflict.first
        val second = conflict.second
        val existing = database.healthDao().measurementSnapshots(first.measurement.id)
            .firstOrNull { it.version == first.version }
        val firstPayload = first.canonicalPayload
        val secondPayload = second.canonicalPayload
        if (existing != null) {
            storeConflict(existing, second, secondPayload)
        } else {
            database.healthDao().upsertConflict(
                HealthSyncConflictEntity(
                    HealthSyncCategory.MEASUREMENTS, first.measurement.id, first.version,
                    firstPayload, first.payloadHash, secondPayload, second.payloadHash,
                    maxOf(first.updatedAt, second.updatedAt),
                ),
            )
        }
    }

    private suspend fun storeConflict(
        local: MeasurementSnapshotEntity,
        remote: ParsedMeasurementSnapshot,
        remotePayload: String,
    ) {
        database.healthDao().upsertConflict(
            HealthSyncConflictEntity(
                HealthSyncCategory.MEASUREMENTS, remote.measurement.id, remote.version,
                local.canonicalPayload, local.payloadHash, remotePayload, remote.payloadHash,
                remote.updatedAt,
            ),
        )
    }

    private suspend fun applyLiveProjection(
        snapshot: ParsedMeasurementSnapshot,
        deletedDocumentHashes: MutableSet<String>,
    ) {
        if (snapshot.isDeleted) {
            deletedDocumentHashes += database.measurementDocumentDao().forMeasurement(snapshot.measurement.id)
                .filter { it.state == "READY" }
                .map { it.sha256 }
            database.bodyMeasurementDao().delete(snapshot.measurement.id)
        } else {
            val projection = snapshot.measurement.copy(
                uploadStatus = UploadStatus.UPLOADED,
                uploadError = null,
                conditionNote = snapshot.measurement.conditionNote?.trim()?.takeIf(String::isNotBlank),
            )
            if (database.bodyMeasurementDao().getById(projection.id) == null) database.bodyMeasurementDao().insert(projection)
            else database.bodyMeasurementDao().update(projection)
        }
    }

    private fun MeasurementSnapshotEntity.matches(payload: String, hash: String?): Boolean =
        canonicalPayload == payload && (payloadHash == null || hash == null || payloadHash == hash)

    private suspend fun appendSnapshot(
        measurement: BodyMeasurementEntity,
        version: Long,
        updatedAt: Long,
        isTombstone: Boolean,
    ) {
        val payload = canonicalPayload(measurement, isTombstone)
        val hash = sha256(payload)
        val snapshot = MeasurementSnapshotEntity(measurement.id, version, updatedAt, isTombstone, payload, hash)
        val outbox = HealthSyncOutboxEntity(
            category = HealthSyncCategory.MEASUREMENTS,
            syncId = measurement.id,
            version = version,
            canonicalPayload = payload,
            payloadHash = hash,
            idempotencyKey = "${measurement.id}:$version",
            createdAt = updatedAt,
        )
        database.healthDao().insertMeasurementHistory(snapshot, outbox)
    }

    companion object {
        /**
         * This mirrors `MIGRATION_9_10`'s SQLite `quote()` representation for every live field.
         * Migration v1 rows omit `isDeleted` because false was implicit in the legacy wire format;
         * only a tombstone appends that explicit discriminator.
         */
        fun canonicalPayload(measurement: BodyMeasurementEntity, isTombstone: Boolean = false): String {
            return MeasurementSnapshotCodec.encode(measurement, isTombstone)
        }

        fun sha256(payload: String): String = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
