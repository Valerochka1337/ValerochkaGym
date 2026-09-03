package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity

@Dao
interface HealthSyncOutboxDao {
    @Insert
    suspend fun insert(entry: HealthSyncOutboxEntity)

    @Query("SELECT * FROM health_sync_outbox WHERE category = :category ORDER BY createdAt ASC, syncId ASC, version ASC")
    suspend fun pending(category: String): List<HealthSyncOutboxEntity>

    @Query("SELECT * FROM health_sync_outbox WHERE category = :category AND syncId = :syncId AND version = :version")
    suspend fun entry(category: String, syncId: String, version: Long): HealthSyncOutboxEntity?

    @Query(
        "DELETE FROM health_sync_outbox WHERE category = :category AND syncId = :syncId AND version = :version",
    )
    suspend fun acknowledge(category: String, syncId: String, version: Long): Int
}
