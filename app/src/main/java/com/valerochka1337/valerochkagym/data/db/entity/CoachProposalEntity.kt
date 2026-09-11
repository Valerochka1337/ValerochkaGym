package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "coach_proposals",
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
    indices = [Index("workoutId"), Index("accountId")],
)
data class CoachProposalEntity(
    val id: String,
    val accountId: String,
    val workoutId: String,
    val baseRevision: Long,
    val beforeSummary: String,
    val afterSummary: String,
    val packetJson: String,
    val expiresAt: Long,
    val state: String = "PENDING",
)
