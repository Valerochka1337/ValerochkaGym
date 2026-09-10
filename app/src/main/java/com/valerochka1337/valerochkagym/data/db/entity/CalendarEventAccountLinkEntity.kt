package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import java.util.Locale

enum class CalendarEventAccountLinkState {
  OWNED,
  LEGACY_OWNER_UNKNOWN,
}

/** Device-local owner proof for one-off Calendar events. It is never exported or reassigned. */
@Entity(
    tableName = "calendar_event_account_links",
    foreignKeys =
        [
            ForeignKey(
                entity = ScheduledWorkoutEntity::class,
                parentColumns = ["id"],
                childColumns = ["scheduledWorkoutId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class CalendarEventAccountLinkEntity(
    @PrimaryKey val scheduledWorkoutId: Long,
    val ownerEmail: String?,
    val state: CalendarEventAccountLinkState,
) {
  init {
    require(
        (state == CalendarEventAccountLinkState.OWNED &&
            !ownerEmail.isNullOrBlank() &&
            ownerEmail == ownerEmail.trim().lowercase(Locale.ROOT)) ||
            (state == CalendarEventAccountLinkState.LEGACY_OWNER_UNKNOWN && ownerEmail == null)
    )
  }
}
