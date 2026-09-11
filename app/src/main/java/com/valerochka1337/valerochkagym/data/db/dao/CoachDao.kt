package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.valerochka1337.valerochkagym.data.db.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CoachDao {
  @Query("SELECT * FROM coach_messages WHERE workoutId=:workoutId ORDER BY createdAt, id")
  fun observeMessages(workoutId: String): Flow<List<CoachMessageEntity>>

  @Query("SELECT * FROM coach_messages WHERE workoutId=:workoutId ORDER BY createdAt, id")
  suspend fun messages(workoutId: String): List<CoachMessageEntity>

  @Query(
      "SELECT COUNT(*) FROM coach_messages WHERE workoutId=:workoutId AND role='assistant' AND readAt IS NULL"
  )
  fun observeUnreadAssistantCount(workoutId: String): Flow<Int>

  @Query(
      "UPDATE coach_messages SET readAt=:readAt WHERE workoutId=:workoutId AND role='assistant' AND readAt IS NULL"
  )
  suspend fun markAssistantMessagesRead(
      workoutId: String,
      readAt: Long = System.currentTimeMillis(),
  ): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveMessage(message: CoachMessageEntity)

  @Query("UPDATE coach_messages SET status=:status WHERE id=:id")
  suspend fun setMessageStatus(id: String, status: String)

  @Query(
      "UPDATE coach_messages SET status=:status WHERE id=:id AND accountId=:accountId AND workoutId=:workoutId AND status IN ('PENDING', 'PROCESSING')"
  )
  suspend fun finishPendingMessage(
      id: String,
      accountId: String,
      workoutId: String,
      status: String,
  ): Int

  @Query(
      "UPDATE coach_messages SET status='INTERRUPTED' WHERE status IN ('PENDING', 'PROCESSING') AND createdAt < :beforeMillis"
  )
  suspend fun markInterruptedMessages(beforeMillis: Long)

  @Query(
      "SELECT * FROM coach_proposals WHERE workoutId=:workoutId AND state='PENDING' ORDER BY expiresAt DESC LIMIT 1"
  )
  suspend fun pendingProposal(workoutId: String): CoachProposalEntity?

  @Query(
      "SELECT * FROM coach_proposals WHERE workoutId=:workoutId AND state='PENDING' ORDER BY expiresAt DESC LIMIT 1"
  )
  fun observePendingProposal(workoutId: String): Flow<CoachProposalEntity?>

  @Query("SELECT * FROM coach_proposals WHERE id=:id AND state='PENDING' LIMIT 1")
  suspend fun pendingProposalForId(id: String): CoachProposalEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveProposal(proposal: CoachProposalEntity)

  @Query("UPDATE coach_proposals SET state=:state WHERE id=:id")
  suspend fun setProposalState(id: String, state: String)

  @Query(
      "UPDATE coach_proposals SET state='SUPERSEDED' WHERE accountId=:accountId AND workoutId=:workoutId AND state='PENDING'"
  )
  suspend fun supersedePendingProposals(accountId: String, workoutId: String)

  @Query("SELECT * FROM coach_command_receipts WHERE operationId=:operationId")
  suspend fun receipt(operationId: String): CoachCommandReceiptEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveReceipt(receipt: CoachCommandReceiptEntity)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun saveJournal(entry: CoachJournalEntity): Long

  @Query(
      "SELECT * FROM coach_journal WHERE accountId=:accountId AND uploaded=0 ORDER BY createdAt,id LIMIT :limit"
  )
  suspend fun pendingJournal(accountId: String, limit: Int): List<CoachJournalEntity>

  @Query("UPDATE coach_journal SET uploaded=1 WHERE id IN (:ids)")
  suspend fun markJournalUploaded(ids: List<String>)

  @Query("SELECT * FROM coach_session_context WHERE workoutId=:workoutId")
  suspend fun context(workoutId: String): CoachSessionContextEntity?

  @Query("SELECT * FROM coach_session_context WHERE workoutId=:workoutId")
  fun observeContext(workoutId: String): Flow<CoachSessionContextEntity?>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveContext(context: CoachSessionContextEntity)

  @Query("DELETE FROM coach_session_context WHERE accountId=:accountId")
  suspend fun clearContext(accountId: String)

  @Query("DELETE FROM coach_messages WHERE accountId=:accountId")
  suspend fun clearMessages(accountId: String)

  @Query("DELETE FROM coach_proposals WHERE accountId=:accountId")
  suspend fun clearProposals(accountId: String)

  @Query("DELETE FROM coach_command_receipts WHERE accountId=:accountId")
  suspend fun clearReceipts(accountId: String)

  @Query("DELETE FROM coach_journal WHERE accountId=:accountId")
  suspend fun clearJournal(accountId: String)

  @androidx.room.Transaction
  suspend fun clearAccount(accountId: String) {
    clearMessages(accountId)
    clearProposals(accountId)
    clearReceipts(accountId)
    clearJournal(accountId)
    clearContext(accountId)
  }
}
