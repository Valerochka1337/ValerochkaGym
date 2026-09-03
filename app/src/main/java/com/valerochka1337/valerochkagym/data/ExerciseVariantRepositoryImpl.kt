package com.valerochka1337.valerochkagym.data

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseVariantDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseVariantEntity
import com.valerochka1337.valerochkagym.data.db.entity.withNextUpdatedAt
import com.valerochka1337.valerochkagym.data.db.entity.normalizedVariantName
import com.valerochka1337.valerochkagym.domain.ExerciseVariantRepository
import com.valerochka1337.valerochkagym.domain.SaveExerciseVariantResult
import com.valerochka1337.valerochkagym.worker.ConfigurationUploadScheduler
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ExerciseVariantRepositoryImpl @Inject constructor(
    private val database: GymDatabase,
    private val variantDao: ExerciseVariantDao,
    private val configurationUploadScheduler: ConfigurationUploadScheduler,
) : ExerciseVariantRepository {
    override fun observeForExercise(exerciseId: Long): Flow<List<ExerciseVariantEntity>> =
        variantDao.observeForExercise(exerciseId)

    override suspend fun activeForExercise(exerciseId: Long): List<ExerciseVariantEntity> =
        variantDao.getForExercise(exerciseId).filterNot(ExerciseVariantEntity::isArchived)

    override suspend fun create(exerciseId: Long, name: String): SaveExerciseVariantResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return SaveExerciseVariantResult.BlankName
        val normalized = normalizedVariantName(trimmed)
        val result = database.withTransaction {
            if (database.exerciseDao().getById(exerciseId) == null) return@withTransaction SaveExerciseVariantResult.NotFound
            if (variantDao.getForExercise(exerciseId).any { it.normalizedName == normalized }) {
                return@withTransaction SaveExerciseVariantResult.DuplicateName
            }
            val saved = ExerciseVariantEntity(exerciseId = exerciseId, name = trimmed, normalizedName = normalized)
            val id = variantDao.insert(saved)
            SaveExerciseVariantResult.Saved(saved.copy(id = id))
        }
        if (result is SaveExerciseVariantResult.Saved) scheduleOwner(exerciseId)
        return result
    }

    override suspend fun rename(syncId: String, name: String): SaveExerciseVariantResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return SaveExerciseVariantResult.BlankName
        val normalized = normalizedVariantName(trimmed)
        val result = database.withTransaction {
            val existing = variantDao.getBySyncId(syncId) ?: return@withTransaction SaveExerciseVariantResult.NotFound
            if (variantDao.getForExercise(existing.exerciseId).any {
                    it.syncId != syncId && it.normalizedName == normalized
                }
            ) return@withTransaction SaveExerciseVariantResult.DuplicateName
            val saved = existing.copy(name = trimmed, normalizedName = normalized).withNextUpdatedAt()
            variantDao.update(saved)
            SaveExerciseVariantResult.Saved(saved)
        }
        if (result is SaveExerciseVariantResult.Saved) scheduleOwner(result.variant.exerciseId)
        return result
    }

    override suspend fun setArchived(syncId: String, archived: Boolean): Boolean {
        val owner = database.withTransaction {
            val existing = variantDao.getBySyncId(syncId) ?: return@withTransaction null
            if (existing.isArchived != archived) variantDao.update(existing.copy(isArchived = archived).withNextUpdatedAt())
            existing.exerciseId
        } ?: return false
        scheduleOwner(owner)
        return true
    }

    private suspend fun scheduleOwner(exerciseId: Long) {
        database.exerciseDao().getById(exerciseId)?.syncId?.let(configurationUploadScheduler::scheduleExercise)
    }
}
