package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CalendarMigrationPhase {
  PENDING,
  ROOM_COPIED,
  DATASTORE_MARKED,
  READY,
}

@Entity(tableName = "calendar_migration_state")
data class CalendarMigrationStateEntity(
    @PrimaryKey val id: Int = 1,
    val phase: CalendarMigrationPhase = CalendarMigrationPhase.PENDING,
)
