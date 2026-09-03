package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/** Durable unit of delivery. ACK removes only this exact category, identity and version. */
@Entity(
    tableName = "health_sync_outbox",
    primaryKeys = ["category", "syncId", "version"],
    indices = [Index("createdAt")],
)
data class HealthSyncOutboxEntity(
    val category: String,
    val syncId: String,
    val version: Long,
    val canonicalPayload: String,
    val payloadHash: String?,
    val idempotencyKey: String,
    val createdAt: Long,
)
