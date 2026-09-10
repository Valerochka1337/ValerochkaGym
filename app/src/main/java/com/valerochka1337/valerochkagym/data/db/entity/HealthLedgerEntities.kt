package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "health_logical_records",
    indices = [Index("scope"), Index(value = ["scope", "deleted"])],
)
data class HealthLogicalRecordEntity(
    @PrimaryKey val logicalId: String,
    val scope: String,
    val kind: String,
    val createdAtEpochMs: Long,
    val currentVersionId: String?,
    val headRevision: Long,
    val deleted: Boolean,
    val healthRevision: Long?,
)

@Entity(
    tableName = "health_record_versions",
    foreignKeys =
        [
            ForeignKey(
                entity = HealthLogicalRecordEntity::class,
                parentColumns = ["logicalId"],
                childColumns = ["logicalId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("logicalId"), Index(value = ["logicalId", "enteredAtEpochMs"])],
)
data class HealthRecordVersionEntity(
    @PrimaryKey val versionId: String,
    val logicalId: String,
    val parentVersionId: String?,
    val kind: String,
    val state: String,
    val enteredAtEpochMs: Long,
    /** Strict raw payload JSON; null is valid only for a tombstone. */
    val payloadJson: String?,
    val serverSequence: Long?,
    val healthRevision: Long?,
)

@Entity(
    tableName = "health_head_history",
    primaryKeys = ["logicalId", "headRevision"],
    foreignKeys =
        [
            ForeignKey(
                entity = HealthLogicalRecordEntity::class,
                parentColumns = ["logicalId"],
                childColumns = ["logicalId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("healthRevision")],
)
data class HealthHeadHistoryEntity(
    val logicalId: String,
    val headRevision: Long,
    val currentVersionId: String,
    val kind: String,
    val deleted: Boolean,
    /** Immutable server-assigned revision; local desired heads live only in the exact outbox. */
    val healthRevision: Long,
)

@Entity(tableName = "health_metric_identities", indices = [Index("scope")])
data class HealthMetricIdentityEntity(
    @PrimaryKey val id: String,
    val scope: String,
    val nameOriginal: String,
    val createdAtEpochMs: Long,
)

/** The last acknowledged immutable version bytes, kept apart from generic sync baselines. */
@Entity(tableName = "health_sync_baseline", primaryKeys = ["scope", "versionId"])
data class HealthSyncBaselineEntity(
    val scope: String,
    val versionId: String,
    val versionJson: String,
)

@Entity(tableName = "health_sync_state")
data class HealthSyncStateEntity(
    @PrimaryKey val scope: String,
    val cursor: String?,
    val needsFullRefresh: Boolean = true,
    val pendingCursor: String? = null,
    val pendingWatermark: Long? = null,
)

@Entity(tableName = "health_sync_outbox", indices = [Index("scope")])
data class HealthSyncOutboxEntity(
    @PrimaryKey val operationId: String,
    val scope: String,
    val requestBytes: ByteArray,
    val requestSha256: String,
    val dispatched: Boolean = false,
)

/** Page data stays invisible until the final page transaction commits it into the ledger. */
@Entity(
    tableName = "health_sync_staging",
    primaryKeys = ["scope", "healthRevision", "eventKind", "eventId"],
)
data class HealthSyncStagingEntity(
    val scope: String,
    val healthRevision: Long,
    val eventKind: String,
    val eventId: String,
    val eventJson: String,
)
