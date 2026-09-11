package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "coach_journal",
    primaryKeys = ["id"],
    foreignKeys =
        [
            ForeignKey(
                entity = WorkoutEntity::class,
                parentColumns = ["id"],
                childColumns = ["workoutId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("workoutId"), Index("accountId"), Index("uploaded")],
)
data class CoachJournalEntity(
    val id: String,
    val accountId: String,
    val workoutId: String,
    val createdAt: Long,
    val payload: String,
    val uploaded: Boolean = false,
    val deviceId: String? = null,
) {
  init {
    require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
      "Coach journal payload exceeds $MAX_PAYLOAD_BYTES bytes"
    }
  }

  companion object {
    const val MAX_PAYLOAD_BYTES = 64 * 1024
  }
}
