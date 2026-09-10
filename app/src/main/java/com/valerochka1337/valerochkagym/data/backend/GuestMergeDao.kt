package com.valerochka1337.valerochkagym.data.backend

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GuestMergeDao {
  @Query("SELECT * FROM backend_state WHERE id=1") suspend fun state(): BackendStateEntity?

  @Query(
      "UPDATE backend_state SET owner=:owner,phase=:phase,mergeId=:mergeId,initialMergeAcknowledged=:acknowledged WHERE id=1"
  )
  suspend fun updateState(
      owner: String?,
      phase: GuestSyncPhase,
      mergeId: String?,
      acknowledged: Boolean,
  )

  @Query(
      "SELECT * FROM backend_conflict_copies WHERE mergeId=:mergeId AND kind=:kind AND originalSyncId=:originalSyncId AND remoteRevision=:remoteRevision AND localPayloadFingerprint=:fingerprint"
  )
  suspend fun conflictCopy(
      mergeId: String,
      kind: String,
      originalSyncId: String,
      remoteRevision: Long,
      fingerprint: String,
  ): BackendConflictCopyEntity?

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertConflictCopy(copy: BackendConflictCopyEntity)
}
