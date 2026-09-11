package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity

/** Per-account durable journal cursor and device identity; never regenerated during a retry. */
@Entity(tableName = "coach_sync_state", primaryKeys = ["accountId"])
data class CoachSyncStateEntity(
    val accountId: String,
    val deviceId: String,
    val watermark: Long = 0,
)
