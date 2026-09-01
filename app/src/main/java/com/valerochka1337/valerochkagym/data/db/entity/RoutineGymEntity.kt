package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Gyms selected for a routine. A linked routine deliberately prevents deleting its gym. */
@Entity(
    tableName = "routine_gyms",
    primaryKeys = ["routineId", "gymId"],
    foreignKeys = [
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GymEntity::class,
            parentColumns = ["id"],
            childColumns = ["gymId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("gymId")],
)
data class RoutineGymEntity(
    val routineId: Long,
    val gymId: Long,
)
