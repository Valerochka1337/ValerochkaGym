package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import kotlinx.coroutines.flow.Flow

/**
 * Карта вовлечения мышц по упражнениям. Аналитика читает таблицу целиком одним потоком
 * ([observeAll]) и считает эффективные подходы в памяти — карта маленькая (десятки упражнений × ≤7
 * мышц), зато формулы (e1RM, скользящие окна, зоны объёма) остаются в чистом Kotlin.
 */
@Dao
interface ExerciseMuscleDao {

  @Query("SELECT * FROM exercise_muscles") fun observeAll(): Flow<List<ExerciseMuscleEntity>>

  @Query("SELECT * FROM exercise_muscles WHERE exerciseId = :exerciseId ORDER BY contribution DESC")
  suspend fun getForExercise(exerciseId: Long): List<ExerciseMuscleEntity>

  /** Id упражнений, у которых карта уже заполнена — по ним сеятель пропускает работу. */
  @Query("SELECT DISTINCT exerciseId FROM exercise_muscles")
  suspend fun getMappedExerciseIds(): List<Long>

  @Upsert suspend fun upsertAll(rows: List<ExerciseMuscleEntity>)

  @Query("DELETE FROM exercise_muscles WHERE exerciseId = :exerciseId")
  suspend fun deleteForExercise(exerciseId: Long)

  /** Полная замена карты упражнения — редактор сохраняет ровно то, что показал пользователь. */
  @Transaction
  suspend fun replaceForExercise(exerciseId: Long, rows: List<ExerciseMuscleEntity>) {
    deleteForExercise(exerciseId)
    if (rows.isNotEmpty()) upsertAll(rows)
  }
}
