package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/** Immutable, append-only version of a body-measurement projection. */
@Entity(
    tableName = "measurement_snapshots",
    primaryKeys = ["syncId", "version"],
    indices = [Index("updatedAt")],
)
data class MeasurementSnapshotEntity(
    val syncId: String,
    val version: Long,
    val updatedAt: Long,
    val isTombstone: Boolean,
    val canonicalPayload: String,
    /** Null only for v9 migration rows: Android SQLite has no SHA-256 implementation. */
    val payloadHash: String? = null,
)

object HealthSyncCategory {
    const val MEASUREMENTS = "MEASUREMENTS"
    const val HEALTH_REPORTS_AND_OBSERVATIONS = "HEALTH_REPORTS_AND_OBSERVATIONS"
    const val HEALTH_RESTRICTIONS = "HEALTH_RESTRICTIONS"
}
