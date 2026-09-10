package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Latest explicit owner-bound choice while a prior exact operation drains. */
@Entity(tableName = "health_ai_consent_intent")
data class HealthAiConsentIntentEntity(
    @PrimaryKey val owner: String,
    val enabled: Boolean,
)
