package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/** Divergent copies of exactly the same version; resolution is a user action, never a clock tie-break. */
@Entity(
    tableName = "health_sync_conflicts",
    primaryKeys = ["category", "syncId", "version"],
    indices = [Index("createdAt")],
)
data class HealthSyncConflictEntity(
    val category: String,
    val syncId: String,
    val version: Long,
    val localPayload: String,
    val localPayloadHash: String?,
    val remotePayload: String,
    val remotePayloadHash: String?,
    val createdAt: Long,
)
