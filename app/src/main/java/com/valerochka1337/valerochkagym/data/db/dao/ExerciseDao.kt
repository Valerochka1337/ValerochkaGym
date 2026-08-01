package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

@Dao
interface ExerciseDao {

    /** Все упражнения, отсортированные по имени; поиск/фильтрация — в памяти на стороне UI. */
    @Query("SELECT * FROM exercises ORDER BY name COLLATE NOCASE ASC")
    fun getAll(): Flow<List<ExerciseEntity>>

    @Insert
    suspend fun insert(exercise: ExerciseEntity): Long

    @Insert
    suspend fun insertAll(exercises: List<ExerciseEntity>)

    @Query("SELECT COUNT(*) FROM exercises")
    suspend fun count(): Int

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun getById(id: Long): ExerciseEntity?

    /**
     * Первое упражнение с таким именем без учёта регистра (матчинг при импорте).
     *
     * Сравнение делается в Kotlin (Unicode-aware): SQLite `COLLATE NOCASE` сворачивает регистр
     * только для ASCII, поэтому для кириллических названий он не годится. Каталог небольшой и
     * при импорте вызовов немного, так что разовое чтение списка допустимо.
     */
    suspend fun findByName(name: String): ExerciseEntity? {
        val target = name.trim().lowercase()
        return getAll().first().firstOrNull { it.name.trim().lowercase() == target }
    }

    @Query("SELECT * FROM exercises WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ExerciseEntity>
}
