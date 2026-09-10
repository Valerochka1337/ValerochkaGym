package com.valerochka1337.valerochkagym.data.coachrelation

import androidx.room.*

/** Secret-bearing invitations store only their digest and sanitized terminal metadata. */
@Entity(tableName = "coach_relation_operations", primaryKeys = ["owner", "operationId"])
data class CoachRelationOperationEntity(
    val owner: String,
    val operationId: String,
    val action: String,
    val route: String,
    val resource: String,
    val rawSha256: String,
    val firstSendBytes: ByteArray?,
    val state: String,
    val resultJson: String?,
)

@Dao
interface CoachRelationOperationDao {
  @Query("SELECT * FROM coach_relation_operations WHERE owner=:owner AND operationId=:id")
  suspend fun get(owner: String, id: String): CoachRelationOperationEntity?

  @Query("SELECT * FROM coach_relation_operations WHERE owner=:owner ORDER BY rowid DESC")
  suspend fun list(owner: String): List<CoachRelationOperationEntity>

  @Insert suspend fun insert(value: CoachRelationOperationEntity)

  @Query(
      "UPDATE coach_relation_operations SET state=:state,resultJson=:result WHERE owner=:owner AND operationId=:id AND state='PENDING'"
  )
  suspend fun finish(owner: String, id: String, state: String, result: String?)
}
