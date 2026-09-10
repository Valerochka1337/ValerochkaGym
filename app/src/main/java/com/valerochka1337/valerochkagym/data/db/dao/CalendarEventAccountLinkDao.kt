package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.valerochka1337.valerochkagym.data.db.entity.CalendarEventAccountLinkEntity

@Dao
interface CalendarEventAccountLinkDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(link: CalendarEventAccountLinkEntity)

  @Query("SELECT * FROM calendar_event_account_links WHERE scheduledWorkoutId = :scheduledId")
  suspend fun getByScheduledId(scheduledId: Long): CalendarEventAccountLinkEntity?
}
