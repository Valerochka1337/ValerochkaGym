package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.valerochka1337.valerochkagym.data.db.entity.ExercisePersonalHintEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExercisePersonalHintDao {
  @Query("SELECT * FROM exercise_personal_hints WHERE exerciseSyncId=:exerciseSyncId")
  fun observe(exerciseSyncId: String): Flow<ExercisePersonalHintEntity?>

  @Query("SELECT * FROM exercise_personal_hints WHERE exerciseSyncId=:exerciseSyncId")
  suspend fun get(exerciseSyncId: String): ExercisePersonalHintEntity?

  @Query("SELECT * FROM exercise_personal_hints")
  suspend fun all(): List<ExercisePersonalHintEntity>

  @Upsert suspend fun upsert(hint: ExercisePersonalHintEntity)

  @Query("DELETE FROM exercise_personal_hints WHERE exerciseSyncId=:exerciseSyncId")
  suspend fun delete(exerciseSyncId: String)
}
