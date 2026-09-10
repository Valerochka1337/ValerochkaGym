package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A local one-off plan. Its id is a portable UUID, never a Calendar event id. */
@Entity(
    tableName = "calendar_plans",
    foreignKeys =
        [
            ForeignKey(
                entity = RoutineEntity::class,
                parentColumns = ["id"],
                childColumns = ["routineId"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
    indices = [Index("routineId"), Index(value = ["legacyScheduleId"], unique = true)],
)
data class CalendarPlanEntity(
    @PrimaryKey val id: String,
    val routineId: Long,
    val startsAtMillis: Long,
    val timeZoneId: String,
    val legacyScheduleId: String? = null,
)
