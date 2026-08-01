package com.valerochka1337.valerochkagym.worker

import androidx.work.WorkManager
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import javax.inject.Inject

/**
 * Постановка выгрузок тренировок в очередь. Тонкая обёртка над [WorkManager] и [WorkoutDao] —
 * держит их вне ViewModel'ей (их проще тестировать, подменяя этот интерфейс).
 */
interface UploadScheduler {

    /** Ставит выгрузку тренировки в очередь (статус не трогает — используется при завершении). */
    fun schedule(workoutId: String)

    /** Повторная выгрузка: сбрасывает статус в [UploadStatus.PENDING] и ставит воркер в очередь. */
    suspend fun retry(workoutId: String)

    /**
     * Ставит в очередь все завершённые, ещё не выгруженные тренировки (PENDING/FAILED), каждой
     * сбрасывая статус в [UploadStatus.PENDING]. Возвращает число поставленных в очередь.
     */
    suspend fun scheduleAllPending(): Int
}

class WorkManagerUploadScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val workoutDao: WorkoutDao,
) : UploadScheduler {

    override fun schedule(workoutId: String) {
        UploadWorkoutWorker.enqueue(workManager, workoutId)
    }

    override suspend fun retry(workoutId: String) {
        workoutDao.setUploadStatus(workoutId, UploadStatus.PENDING, null)
        UploadWorkoutWorker.enqueue(workManager, workoutId)
    }

    override suspend fun scheduleAllPending(): Int {
        val ids = workoutDao.getFinishedNotUploaded()
        ids.forEach { id ->
            workoutDao.setUploadStatus(id, UploadStatus.PENDING, null)
            UploadWorkoutWorker.enqueue(workManager, id)
        }
        return ids.size
    }
}
