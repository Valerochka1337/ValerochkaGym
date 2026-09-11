package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "coach_command_receipts",
    primaryKeys = ["operationId"],
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
data class CoachCommandReceiptEntity(
    val operationId: String,
    val accountId: String,
    val workoutId: String,
    val revision: Long,
    val result: String,
    val createdAt: Long,
)
