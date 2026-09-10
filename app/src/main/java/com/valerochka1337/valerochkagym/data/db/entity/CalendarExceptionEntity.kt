package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class CalendarExceptionKind {
  CANCELLED,
  MOVED,
}

/** Keeps a change attached to the original rule instance, including after a move. */
@Entity(
    tableName = "calendar_exceptions",
    foreignKeys =
        [
            ForeignKey(
                entity = CalendarRuleEntity::class,
                parentColumns = ["id"],
                childColumns = ["ruleId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("ruleId"), Index(value = ["ruleId", "instanceKey"], unique = true)],
)
data class CalendarExceptionEntity(
    @PrimaryKey val id: String,
    val ruleId: String,
    val instanceKey: String,
    val kind: CalendarExceptionKind,
    val movedAtMillis: Long? = null,
)
