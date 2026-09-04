package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/** Immutable restriction history, separate from the mutable current projection. */
@Entity(
    tableName = "health_restriction_snapshots",
    primaryKeys = ["syncId", "version"],
    indices = [Index("updatedAt")],
)
data class HealthRestrictionSnapshotEntity(
    val syncId: String,
    val version: Long,
    val updatedAt: Long,
    val isTombstone: Boolean,
    val canonicalPayload: String,
    val payloadHash: String?,
    /** Immutable local wording matching this confirmed snapshot; never part of canonicalPayload. */
    val originalText: String? = null,
    val operationId: String? = null,
)
