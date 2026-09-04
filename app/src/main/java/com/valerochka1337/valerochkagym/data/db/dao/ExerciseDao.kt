package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {

  /** Все упражнения, отсортированные по имени; поиск/фильтрация — в памяти на стороне UI. */
  @Query("SELECT * FROM exercises ORDER BY name COLLATE NOCASE ASC")
  fun getAll(): Flow<List<ExerciseEntity>>

  @Insert suspend fun insert(exercise: ExerciseEntity): Long

  /** Правка упражнения из редактора: название своего упражнения и тип. */
  @Update suspend fun update(exercise: ExerciseEntity)

  @Insert suspend fun insertAll(exercises: List<ExerciseEntity>)

  @Query("SELECT COUNT(*) FROM exercises") suspend fun count(): Int

  @Query("SELECT * FROM exercises WHERE id = :id") suspend fun getById(id: Long): ExerciseEntity?

  /** Все упражнения одним снимком (для матчинга по имени при импорте). */
  @Query("SELECT * FROM exercises") suspend fun getAllOnce(): List<ExerciseEntity>
}
