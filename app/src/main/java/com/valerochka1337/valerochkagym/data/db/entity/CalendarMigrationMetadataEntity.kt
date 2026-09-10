package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One-time capture used so restarts never recompute the legacy source identity. */
@Entity(tableName = "calendar_migration_metadata")
data class CalendarMigrationMetadataEntity(
    @PrimaryKey val id: Int = 1,
    val sourceFingerprint: String,
    val zoneId: String,
    val weeklyStartLocalDate: String,
    val observedOwners: String,
)
