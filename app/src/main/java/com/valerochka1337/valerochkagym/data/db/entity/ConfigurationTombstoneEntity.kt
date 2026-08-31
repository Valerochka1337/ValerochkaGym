package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity

/** Durable outbox удаления: остаётся в Room, пока tombstone не подтверждён Sheets. */
@Entity(
    tableName = "configuration_tombstones",
    primaryKeys = ["kind", "syncId"],
)
data class ConfigurationTombstoneEntity(
    val kind: String,
    val syncId: String,
    val updatedAt: Long,
)

object ConfigurationTombstoneKind {
    const val GYM = "gym"
    const val ROUTINE = "routine"
}
