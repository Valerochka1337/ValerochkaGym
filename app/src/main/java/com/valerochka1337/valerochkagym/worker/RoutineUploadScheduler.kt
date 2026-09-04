package com.valerochka1337.valerochkagym.worker

import androidx.work.WorkManager
import com.valerochka1337.valerochkagym.data.db.dao.ConfigurationTombstoneDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.ConfigurationTombstoneKind
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Очередь снимков пользовательских программ. В отличие от завершённых тренировок, программа
 * меняется много раз, поэтому ключ очереди — стабильный syncId, а не локальный auto-ID.
 */
interface RoutineUploadScheduler {
  fun schedule(syncId: String)

  fun scheduleDeletion(syncId: String, updatedAt: Long)

  suspend fun scheduleAll(): Int
}

/** Безопасная заглушка для прямых ViewModel/use-case unit-тестов без WorkManager. */
object NoOpRoutineUploadScheduler : RoutineUploadScheduler {
  override fun schedule(syncId: String) = Unit

  override fun scheduleDeletion(syncId: String, updatedAt: Long) = Unit

  override suspend fun scheduleAll(): Int = 0
}

class WorkManagerRoutineUploadScheduler
@Inject
constructor(
    private val workManager: WorkManager,
    private val routineDao: RoutineDao,
    private val tombstoneDao: ConfigurationTombstoneDao? = null,
) : RoutineUploadScheduler {

  override fun schedule(syncId: String) {
    UploadRoutineWorker.enqueue(workManager, syncId)
  }

  override fun scheduleDeletion(syncId: String, updatedAt: Long) {
    UploadRoutineWorker.enqueueDeletion(workManager, syncId, updatedAt)
  }

  /** `Выгрузить всё` повторно ставит актуальный снимок каждой существующей программы. */
  override suspend fun scheduleAll(): Int {
    val syncIds = routineDao.observeRoutinesFull().first().map { it.routine.syncId }
    val deletions = tombstoneDao?.getByKind(ConfigurationTombstoneKind.ROUTINE).orEmpty()
    syncIds.forEach(::schedule)
    deletions.forEach { scheduleDeletion(it.syncId, it.updatedAt) }
    return syncIds.size + deletions.size
  }
}
