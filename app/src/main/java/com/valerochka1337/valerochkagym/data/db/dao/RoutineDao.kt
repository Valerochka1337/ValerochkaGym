package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithCount
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineDao {

    @Query(
        """
        SELECT r.*,
            (SELECT COUNT(*) FROM routine_exercises re WHERE re.routineId = r.id) AS exerciseCount
        FROM routines r
        ORDER BY r.name COLLATE NOCASE ASC
        """,
    )
    fun observeRoutinesWithCount(): Flow<List<RoutineWithCount>>

    @Transaction
    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun getRoutineWithExercises(id: Long): RoutineWithExercises?

    @Upsert
    suspend fun upsertRoutine(routine: RoutineEntity): Long

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteRoutine(id: Long)

    @Insert
    suspend fun insertRoutineExercise(routineExercise: RoutineExerciseEntity): Long

    @Insert
    suspend fun insertRoutineExercises(routineExercises: List<RoutineExerciseEntity>): List<Long>

    @Update
    suspend fun updateRoutineExercise(routineExercise: RoutineExerciseEntity)

    @Query("DELETE FROM routine_exercises WHERE id = :id")
    suspend fun deleteRoutineExercise(id: Long)

    @Query("DELETE FROM routine_exercises WHERE routineId = :routineId")
    suspend fun deleteRoutineExercises(routineId: Long)

    @Transaction
    suspend fun replaceRoutineExercises(routineId: Long, list: List<RoutineExerciseEntity>) {
        deleteRoutineExercises(routineId)
        insertRoutineExercises(list)
    }
}
