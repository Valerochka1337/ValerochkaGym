package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** A selected, stable built-in equipment id in a configured gym inventory. */
@Entity(
    tableName = "gym_equipment",
    primaryKeys = ["gymId", "equipmentId"],
    foreignKeys =
        [
            ForeignKey(
                entity = GymEntity::class,
                parentColumns = ["id"],
                childColumns = ["gymId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("gymId")],
)
data class GymEquipmentEntity(
    val gymId: Long,
    val equipmentId: String,
)
