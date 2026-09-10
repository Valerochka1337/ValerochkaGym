package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A weekly wall-clock recurrence rule. */
@Entity(
    tableName = "calendar_rules",
    foreignKeys =
        [
            ForeignKey(
                entity = RoutineEntity::class,
                parentColumns = ["id"],
                childColumns = ["routineId"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
    indices = [Index("routineId"), Index(value = ["legacyRuleKey"], unique = true)],
)
data class CalendarRuleEntity(
    @PrimaryKey val id: String,
    val routineId: Long,
    val isoDay: Int,
    val localTime: String,
    val timeZoneId: String,
    val startLocalDate: String,
    val legacyRuleKey: String? = null,
)
