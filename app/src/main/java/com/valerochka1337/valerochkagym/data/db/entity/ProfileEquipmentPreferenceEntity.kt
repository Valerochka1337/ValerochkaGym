package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "profile_equipment",
    primaryKeys = ["scope", "equipmentId"],
    foreignKeys =
        [
            ForeignKey(
                entity = ProfileEntity::class,
                parentColumns = ["scope"],
                childColumns = ["scope"],
                onUpdate = ForeignKey.CASCADE,
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("scope")],
)
data class ProfileEquipmentPreferenceEntity(val scope: String, val equipmentId: String)
