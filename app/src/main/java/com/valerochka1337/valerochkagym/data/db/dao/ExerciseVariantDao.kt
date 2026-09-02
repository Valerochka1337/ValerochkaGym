package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseVariantEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseVariantDao {
    @Query("SELECT * FROM exercise_variants WHERE exerciseId = :exerciseId ORDER BY isArchived ASC, name COLLATE NOCASE ASC")
    fun observeForExercise(exerciseId: Long): Flow<List<ExerciseVariantEntity>>

    @Query("SELECT * FROM exercise_variants WHERE exerciseId = :exerciseId ORDER BY isArchived ASC, name COLLATE NOCASE ASC")
    suspend fun getForExercise(exerciseId: Long): List<ExerciseVariantEntity>

    @Query("SELECT * FROM exercise_variants WHERE syncId = :syncId")
    suspend fun getBySyncId(syncId: String): ExerciseVariantEntity?

    @Query("SELECT * FROM exercise_variants WHERE exerciseId = :exerciseId AND syncId = :syncId")
    suspend fun getOwned(exerciseId: Long, syncId: String): ExerciseVariantEntity?

    @Query("SELECT * FROM exercise_variants")
    suspend fun getAll(): List<ExerciseVariantEntity>

    @Insert
    suspend fun insert(variant: ExerciseVariantEntity): Long

    @Update
    suspend fun update(variant: ExerciseVariantEntity)
}
