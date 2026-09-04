package com.valerochka1337.valerochkagym.worker

import androidx.work.WorkManager
import com.valerochka1337.valerochkagym.data.db.dao.ConfigurationTombstoneDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.GymDao
import com.valerochka1337.valerochkagym.data.db.entity.ConfigurationTombstoneKind
import javax.inject.Inject

interface ConfigurationUploadScheduler {
  fun scheduleExercise(syncId: String)

  fun scheduleGym(syncId: String)

  fun scheduleGymDeletion(syncId: String, updatedAt: Long)

  suspend fun scheduleAll(): Int
}

object NoOpConfigurationUploadScheduler : ConfigurationUploadScheduler {
  override fun scheduleExercise(syncId: String) = Unit

  override fun scheduleGym(syncId: String) = Unit

  override fun scheduleGymDeletion(syncId: String, updatedAt: Long) = Unit

  override suspend fun scheduleAll(): Int = 0
}

class WorkManagerConfigurationUploadScheduler
@Inject
constructor(
    private val workManager: WorkManager,
    private val exerciseDao: ExerciseDao,
    private val gymDao: GymDao,
    private val tombstoneDao: ConfigurationTombstoneDao,
) : ConfigurationUploadScheduler {
  override fun scheduleExercise(syncId: String) {
    UploadConfigurationWorker.enqueueExercise(workManager, syncId)
  }

  override fun scheduleGym(syncId: String) {
    UploadConfigurationWorker.enqueueGym(workManager, syncId)
  }

  override fun scheduleGymDeletion(syncId: String, updatedAt: Long) {
    UploadConfigurationWorker.enqueueGymDeletion(workManager, syncId, updatedAt)
  }

  override suspend fun scheduleAll(): Int {
    val exercises = exerciseDao.getAllOnce()
    val gyms = gymDao.getGyms()
    val deletions = tombstoneDao.getByKind(ConfigurationTombstoneKind.GYM)
    exercises.forEach { scheduleExercise(it.syncId) }
    gyms.forEach { scheduleGym(it.syncId) }
    deletions.forEach { scheduleGymDeletion(it.syncId, it.updatedAt) }
    return exercises.size + gyms.size + deletions.size
  }
}
