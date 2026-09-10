package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Last server acknowledgement for one authenticated owner's InBody disclosure. */
@Entity(tableName = "health_ai_consent_state")
data class HealthAiConsentStateEntity(
    @PrimaryKey val owner: String,
    val revision: Long,
    val noticeVersion: Int,
    val enabled: Boolean,
    val recordedAtEpochMs: Long,
    /** Exact receipt JSON returned by the server, retained for crash-safe monotonic application. */
    val receiptBytes: ByteArray,
)
