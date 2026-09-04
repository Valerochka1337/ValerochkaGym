package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.valerochka1337.valerochkagym.data.db.entity.ConfigurationTombstoneEntity

@Dao
interface ConfigurationTombstoneDao {
  @Upsert suspend fun upsert(tombstone: ConfigurationTombstoneEntity)

  @Query("SELECT * FROM configuration_tombstones WHERE kind = :kind ORDER BY updatedAt ASC")
  suspend fun getByKind(kind: String): List<ConfigurationTombstoneEntity>

  @Query("SELECT * FROM configuration_tombstones WHERE kind = :kind AND syncId = :syncId")
  suspend fun get(kind: String, syncId: String): ConfigurationTombstoneEntity?

  @Query(
      "DELETE FROM configuration_tombstones " +
          "WHERE kind = :kind AND syncId = :syncId AND updatedAt = :updatedAt",
  )
  suspend fun delete(kind: String, syncId: String, updatedAt: Long): Int
}
