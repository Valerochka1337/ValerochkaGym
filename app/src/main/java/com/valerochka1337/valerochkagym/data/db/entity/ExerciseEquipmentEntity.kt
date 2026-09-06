package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** One required equipment capability for an exercise with a known requirement definition. */
@Entity(
    tableName = "exercise_equipment",
    primaryKeys = ["exerciseId", "equipmentId"],
    foreignKeys =
        [
            ForeignKey(
                entity = ExerciseEntity::class,
                parentColumns = ["id"],
                childColumns = ["exerciseId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("exerciseId")],
)
data class ExerciseEquipmentEntity(
    val exerciseId: Long,
    val equipmentId: String,
)
