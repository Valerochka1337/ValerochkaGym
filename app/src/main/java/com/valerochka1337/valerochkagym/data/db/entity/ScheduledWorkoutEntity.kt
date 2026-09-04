package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scheduled_workouts",
    foreignKeys =
        [
            ForeignKey(
                entity = RoutineEntity::class,
                parentColumns = ["id"],
                childColumns = ["routineId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("routineId")],
)
data class ScheduledWorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val dateTimeMillis: Long,
    val calendarEventId: String,
)
