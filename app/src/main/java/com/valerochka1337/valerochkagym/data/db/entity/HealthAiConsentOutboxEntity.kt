package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Exact consent mutation bytes are durable before their first network dispatch. */
@Entity(tableName = "health_ai_consent_outbox")
data class HealthAiConsentOutboxEntity(
    @PrimaryKey val owner: String,
    val operationId: String,
    val requestBytes: ByteArray,
    val requestSha256: String,
    val dispatched: Boolean = false,
)
