package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** A user-managed gym configuration with a stable identity independent of the local Room ID. */
@Entity(
    tableName = "gyms",
    indices = [Index(value = ["syncId"], unique = true)],
)
data class GymEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncId: String = UUID.randomUUID().toString(),
    val updatedAt: Long = System.currentTimeMillis(),
    val name: String,
)

/** Returns a strictly newer gym snapshot version even for saves in the same millisecond. */
fun GymEntity.withNextUpdatedAt(now: Long = System.currentTimeMillis()): GymEntity =
    copy(updatedAt = maxOf(now, updatedAt + 1))
