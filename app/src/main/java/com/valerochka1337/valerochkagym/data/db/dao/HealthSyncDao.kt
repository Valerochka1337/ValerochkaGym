package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncBaselineEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncStagingEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncStateEntity

@Dao
interface HealthSyncDao {
  @Query("SELECT * FROM health_sync_state WHERE scope=:scope")
  suspend fun state(scope: String): HealthSyncStateEntity?

  @Upsert suspend fun upsertState(state: HealthSyncStateEntity)

  @Query("SELECT * FROM health_sync_outbox WHERE scope=:scope ORDER BY operationId LIMIT 1")
  suspend fun outbox(scope: String): HealthSyncOutboxEntity?

  @Upsert suspend fun upsertOutbox(outbox: HealthSyncOutboxEntity)

  @Query("UPDATE health_sync_outbox SET dispatched=1 WHERE operationId=:operationId")
  suspend fun markDispatched(operationId: String)

  @Query("DELETE FROM health_sync_outbox WHERE operationId=:operationId")
  suspend fun deleteOutbox(operationId: String)

  @Upsert suspend fun upsertBaseline(item: HealthSyncBaselineEntity)

  @Query("SELECT * FROM health_sync_baseline WHERE scope=:scope")
  suspend fun baseline(scope: String): List<HealthSyncBaselineEntity>

  @Query("DELETE FROM health_sync_baseline WHERE scope=:scope")
  suspend fun deleteBaseline(scope: String)

  @Upsert suspend fun stage(item: HealthSyncStagingEntity)

  @Query(
      "SELECT * FROM health_sync_staging WHERE scope=:scope ORDER BY healthRevision,eventKind,eventId"
  )
  suspend fun staged(scope: String): List<HealthSyncStagingEntity>

  @Query("DELETE FROM health_sync_staging WHERE scope=:scope")
  suspend fun clearStaging(scope: String)
}
