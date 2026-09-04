package com.valerochka1337.valerochkagym.worker

import androidx.work.WorkManager
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import javax.inject.Inject

/** Шов между экраном замеров и WorkManager — аналог [UploadScheduler] для тренировок. */
interface MeasurementUploadScheduler {

  /** Новая либо ещё не выгруженная запись: статус уже PENDING, достаточно поставить воркер. */
  fun schedule(measurementId: String)

  /** Явный повтор ошибки: очищает видимую причину и возвращает запись в PENDING. */
  suspend fun retry(measurementId: String)

  /** Ставит в очередь все PENDING/FAILED замеры для «Выгрузить всё». */
  suspend fun scheduleAllPending(): Int
}

class WorkManagerMeasurementUploadScheduler
@Inject
constructor(
    private val workManager: WorkManager,
    private val bodyMeasurementDao: BodyMeasurementDao,
) : MeasurementUploadScheduler {

  override fun schedule(measurementId: String) {
    UploadMeasurementWorker.enqueue(workManager, measurementId)
  }

  override suspend fun retry(measurementId: String) {
    bodyMeasurementDao.setUploadStatus(measurementId, UploadStatus.PENDING, null)
    UploadMeasurementWorker.enqueue(workManager, measurementId)
  }

  override suspend fun scheduleAllPending(): Int {
    val ids = bodyMeasurementDao.getNotUploaded()
    ids.forEach { id ->
      bodyMeasurementDao.setUploadStatus(id, UploadStatus.PENDING, null)
      UploadMeasurementWorker.enqueue(workManager, id)
    }
    return ids.size
  }
}
