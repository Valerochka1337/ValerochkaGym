package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.valerochka1337.valerochkagym.data.db.entity.ScheduledWorkoutEntity
import com.valerochka1337.valerochkagym.data.db.relation.ScheduledWithRoutine
import kotlinx.coroutines.flow.Flow

/**
 * Grace window applied by [ScheduledWorkoutDao.observeUpcoming]: a scheduled workout stays
 * "upcoming" for this long after its start time so a session started slightly late is still
 * surfaced. 6 hours in milliseconds.
 */
const val SCHEDULED_GRACE_MILLIS: Long = 6L * 60L * 60L * 1000L

@Dao
interface ScheduledWorkoutDao {

    @Query(
        """
        SELECT sw.*, r.name AS routineName
        FROM scheduled_workouts sw
        JOIN routines r ON r.id = sw.routineId
        WHERE sw.dateTimeMillis >= (:nowMillis - $SCHEDULED_GRACE_MILLIS)
        ORDER BY sw.dateTimeMillis ASC
        """,
    )
    fun observeUpcoming(nowMillis: Long): Flow<List<ScheduledWithRoutine>>

    @Insert
    suspend fun insert(scheduled: ScheduledWorkoutEntity): Long

    @Query("DELETE FROM scheduled_workouts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM scheduled_workouts WHERE id = :id")
    suspend fun getById(id: Long): ScheduledWorkoutEntity?
}
