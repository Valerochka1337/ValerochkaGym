package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Explicitly confirmed important health information or training restriction. */
@Entity(
    tableName = "health_restrictions",
    indices = [Index("status"), Index("reviewAt")],
)
data class HealthRestrictionEntity(
    @PrimaryKey val syncId: String,
    val version: Long,
    val updatedAt: Long,
    val isTombstone: Boolean = false,
    val status: String,
    val source: String,
    val confirmedAt: Long,
    val startsAt: Long? = null,
    val reviewAt: Long? = null,
    val description: String,
    /** Local-only original wording; deliberately excluded from Sheets and sync payloads. */
    val originalText: String? = null,
)
