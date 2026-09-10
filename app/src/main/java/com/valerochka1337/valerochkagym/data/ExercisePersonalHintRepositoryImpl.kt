package com.valerochka1337.valerochkagym.data

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.BackendSessionSnapshot
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExercisePersonalHintDao
import com.valerochka1337.valerochkagym.data.db.entity.ExercisePersonalHintEntity
import com.valerochka1337.valerochkagym.domain.ExercisePersonalHint
import com.valerochka1337.valerochkagym.domain.ExercisePersonalHintRepository
import com.valerochka1337.valerochkagym.domain.HintEditTarget
import com.valerochka1337.valerochkagym.domain.NoteSaveResult
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val MAX_HINT_CODE_POINTS = 2_000

class ExercisePersonalHintRepositoryImpl
@Inject
constructor(
    private val database: GymDatabase,
    private val exerciseDao: ExerciseDao,
    private val hintDao: ExercisePersonalHintDao,
    private val sync: BackendSync,
    private val sessions: BackendSessionStore,
) : ExercisePersonalHintRepository {
  private val mutationMutex = Mutex()

  override fun observe(exerciseId: Long): Flow<ExercisePersonalHint?> =
      exerciseDao
          .getAll()
          .map { it.firstOrNull { exercise -> exercise.id == exerciseId }?.syncId }
          .flatMapLatest { syncId ->
            if (syncId == null) flowOf(null)
            else hintDao.observe(syncId).map { entity -> entity?.let(::hint) }
          }

  override suspend fun editTarget(exerciseId: Long): HintEditTarget? {
    val scope = scope()
    val exercise = exerciseDao.getById(exerciseId) ?: return null
    if (!scopeStillCurrent(scope) || exerciseDao.getById(exerciseId)?.syncId != exercise.syncId)
        return null
    return HintEditTarget(exerciseId, exercise.syncId, scope.owner, scope.session?.epoch ?: 0L)
  }

  override suspend fun save(target: HintEditTarget, text: String): NoteSaveResult {
    val value = text.trim()
    if (value.codePointCount(0, value.length) > MAX_HINT_CODE_POINTS) return NoteSaveResult.TooLong
    return mutationMutex.withLock {
      database.withTransaction {
        if (!targetStillCurrent(target)) NoteSaveResult.MissingOrInactive
        else {
          if (value.isBlank()) hintDao.delete(target.exerciseSyncId)
          else
              hintDao.upsert(
                  ExercisePersonalHintEntity(
                      target.exerciseSyncId,
                      value,
                      System.currentTimeMillis(),
                  )
              )
          NoteSaveResult.Saved
        }
      }
    }
  }

  override suspend fun unpin(target: HintEditTarget): NoteSaveResult {
    return mutationMutex.withLock {
      database.withTransaction {
        if (!targetStillCurrent(target)) NoteSaveResult.MissingOrInactive
        else {
          hintDao.delete(target.exerciseSyncId)
          NoteSaveResult.Saved
        }
      }
    }
  }

  private fun hint(entity: ExercisePersonalHintEntity) =
      ExercisePersonalHint(entity.text, entity.updatedAt)

  private fun scope() = HintScope(sync.owner(), sessions.snapshot())

  private fun scopeStillCurrent(scope: HintScope): Boolean =
      sync.owner() == scope.owner && sessions.snapshot() == scope.session

  private suspend fun targetStillCurrent(target: HintEditTarget): Boolean =
      sync.owner() == target.ownerScope &&
          (sessions.snapshot()?.epoch ?: 0L) == target.sessionEpoch &&
          exerciseDao.getById(target.exerciseId)?.syncId == target.exerciseSyncId

  private data class HintScope(val owner: String?, val session: BackendSessionSnapshot?)
}
