package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/** Immutable report aggregate history; observations are embedded in [canonicalPayload]. */
@Entity(
    tableName = "health_report_snapshots",
    primaryKeys = ["syncId", "version"],
    indices = [Index("updatedAt")],
)
data class HealthReportSnapshotEntity(
    val syncId: String,
    val version: Long,
    val updatedAt: Long,
    val isTombstone: Boolean,
    val canonicalPayload: String,
    val payloadHash: String?,
)
