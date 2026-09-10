package com.valerochka1337.valerochkagym.data.trainingproposal

import androidx.room.*

@Entity(tableName = "training_proposal_drafts", primaryKeys = ["owner", "proposalId", "version"])
data class TrainingProposalDraftEntity(
    val owner: String,
    val proposalId: String,
    val version: Int,
    val proposalJson: String,
    val draftJson: String,
)

@Entity(
    tableName = "training_proposal_operations",
    primaryKeys = ["owner", "proposalId", "version", "operationId"],
    indices = [Index(value = ["operationId"], unique = true)],
)
data class TrainingProposalOperationEntity(
    val owner: String,
    val proposalId: String,
    val version: Int,
    val operationId: String,
    val requestBytes: ByteArray,
    val requestSha256: String,
    val acceptedResultJson: String?,
    @ColumnInfo(defaultValue = "0") val rejected: Boolean = false,
)

@Entity(
    tableName = "training_proposal_projections",
    primaryKeys = ["owner", "proposalId", "version"],
)
data class TrainingProposalProjectionEntity(
    val owner: String,
    val proposalId: String,
    val version: Int,
    val routineId: String,
    val calendarPlanId: String,
    val syncRevision: Long,
)

@Dao
interface TrainingProposalDao {
  @Query(
      "SELECT * FROM training_proposal_drafts WHERE owner=:owner AND proposalId=:proposalId AND version=:version"
  )
  suspend fun draft(owner: String, proposalId: String, version: Int): TrainingProposalDraftEntity?

  @Upsert suspend fun saveDraft(draft: TrainingProposalDraftEntity)

  @Query(
      "SELECT * FROM training_proposal_operations WHERE owner=:owner AND proposalId=:proposalId AND version=:version AND rejected=0 LIMIT 1"
  )
  suspend fun operation(
      owner: String,
      proposalId: String,
      version: Int,
  ): TrainingProposalOperationEntity?

  @Query(
      "UPDATE training_proposal_operations SET rejected=1 WHERE operationId=:operationId AND acceptedResultJson IS NULL AND rejected=0"
  )
  suspend fun rejectOperation(operationId: String)

  @Insert suspend fun insertOperation(operation: TrainingProposalOperationEntity)

  @Query(
      "UPDATE training_proposal_operations SET acceptedResultJson=:result WHERE owner=:owner AND proposalId=:proposalId AND version=:version AND acceptedResultJson IS NULL AND rejected=0"
  )
  suspend fun accept(owner: String, proposalId: String, version: Int, result: String)

  @Query(
      "SELECT p.* FROM training_proposal_projections p JOIN calendar_plans c ON c.id=p.calendarPlanId JOIN routines r ON r.id=c.routineId AND r.syncId=p.routineId WHERE p.owner=:owner AND p.proposalId=:proposalId AND p.version=:version"
  )
  suspend fun projection(
      owner: String,
      proposalId: String,
      version: Int,
  ): TrainingProposalProjectionEntity?

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun project(projection: TrainingProposalProjectionEntity)
}
