package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseVariantEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

sealed interface SaveExerciseVariantResult {
    data class Saved(val variant: ExerciseVariantEntity) : SaveExerciseVariantResult
    data object BlankName : SaveExerciseVariantResult
    data object DuplicateName : SaveExerciseVariantResult
    data object NotFound : SaveExerciseVariantResult
}

interface ExerciseVariantRepository {
    fun observeForExercise(exerciseId: Long): Flow<List<ExerciseVariantEntity>>
    suspend fun activeForExercise(exerciseId: Long): List<ExerciseVariantEntity>
    suspend fun create(exerciseId: Long, name: String): SaveExerciseVariantResult
    suspend fun rename(syncId: String, name: String): SaveExerciseVariantResult
    suspend fun setArchived(syncId: String, archived: Boolean): Boolean
}

object NoOpExerciseVariantRepository : ExerciseVariantRepository {
    override fun observeForExercise(exerciseId: Long): Flow<List<ExerciseVariantEntity>> = flowOf(emptyList())
    override suspend fun activeForExercise(exerciseId: Long): List<ExerciseVariantEntity> = emptyList()
    override suspend fun create(exerciseId: Long, name: String): SaveExerciseVariantResult = SaveExerciseVariantResult.NotFound
    override suspend fun rename(syncId: String, name: String): SaveExerciseVariantResult = SaveExerciseVariantResult.NotFound
    override suspend fun setArchived(syncId: String, archived: Boolean): Boolean = false
}
