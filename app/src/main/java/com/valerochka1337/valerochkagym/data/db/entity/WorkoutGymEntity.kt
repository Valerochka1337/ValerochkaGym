package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Internal snapshot of the gyms selected when a workout starts. */
@Entity(
    tableName = "workout_gyms",
    primaryKeys = ["workoutId", "gymId"],
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GymEntity::class,
            parentColumns = ["id"],
            childColumns = ["gymId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("gymId")],
)
data class WorkoutGymEntity(
    val workoutId: String,
    val gymId: Long,
)
