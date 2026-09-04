package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.valerochka1337.valerochkagym.data.db.entity.ScheduledWorkoutEntity
import com.valerochka1337.valerochkagym.data.db.relation.ScheduledWithRoutine
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledWorkoutDao {

  /**
   * Все запланированные тренировки (ad-hoc) с именем программы, по времени начала — включая
   * прошлые: календарю нужно рисовать ячейки любого дня и шторку выбранного дня.
   */
  @Query(
      """
        SELECT sw.*, r.name AS routineName
        FROM scheduled_workouts sw
        JOIN routines r ON r.id = sw.routineId
        ORDER BY sw.dateTimeMillis ASC
        """,
  )
  fun observeAll(): Flow<List<ScheduledWithRoutine>>

  @Insert suspend fun insert(scheduled: ScheduledWorkoutEntity): Long

  @Query("DELETE FROM scheduled_workouts WHERE id = :id") suspend fun delete(id: Long)

  @Query("SELECT * FROM scheduled_workouts WHERE id = :id")
  suspend fun getById(id: Long): ScheduledWorkoutEntity?
}
