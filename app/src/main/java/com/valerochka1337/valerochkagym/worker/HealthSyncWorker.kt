package com.valerochka1337.valerochkagym.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.valerochka1337.valerochkagym.data.db.dao.HealthSyncOutboxDao
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.google.HealthSheetsRepository
import com.valerochka1337.valerochkagym.data.google.UploadResult
import com.valerochka1337.valerochkagym.data.settings.HealthSyncCategory as SettingCategory
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** One exact outbox row per job; category consent is checked before repository/auth/network. */
@HiltWorker
class HealthSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val outboxDao: HealthSyncOutboxDao,
    private val repository: HealthSheetsRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val category = inputData.getString(KEY_CATEGORY)?.toSettingCategory() ?: return Result.failure()
        if (!settingsRepository.settings.first().healthSync.isEnabled(category)) return Result.success()
        val id = inputData.getString(KEY_SYNC_ID) ?: return Result.failure()
        val version = inputData.getLong(KEY_VERSION, -1).takeIf { it > 0 } ?: return Result.failure()
        val entry = outboxDao.entry(category.outboxCategory(), id, version) ?: return Result.success()
        return when (repository.upload(entry)) {
            UploadResult.Success -> { outboxDao.acknowledge(entry.category, entry.syncId, entry.version); Result.success() }
            // The repository re-read consent after worker preflight. Keep the durable item for a
            // later re-enable/startup reconciliation; this worker did not deliver anything.
            UploadResult.NotAttemptedDisabled -> Result.success()
            is UploadResult.PermanentFailure -> Result.failure()
            is UploadResult.TransientFailure ->
                if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
        }
    }
    companion object {
        const val TAG_REPORTS = "health_sync_reports"
        const val TAG_RESTRICTIONS = "health_sync_restrictions"
        private const val KEY_CATEGORY = "category"
        private const val KEY_SYNC_ID = "syncId"
        private const val KEY_VERSION = "version"
        fun enqueue(manager: WorkManager, category: SettingCategory, id: String, version: Long) {
            val tag = if (category == SettingCategory.HEALTH_RESTRICTIONS) TAG_RESTRICTIONS else TAG_REPORTS
            manager.enqueueUniqueWork(
                workName(category, id, version),
                ExistingWorkPolicy.REPLACE,
                request(category, id, version, tag),
            )
        }
        internal fun request(category: SettingCategory, id: String, version: Long): OneTimeWorkRequest =
            request(category, id, version, categoryTag(category))
        private fun request(category: SettingCategory, id: String, version: Long, tag: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<HealthSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(tag)
                .setInputData(workDataOf(KEY_CATEGORY to category.name, KEY_SYNC_ID to id, KEY_VERSION to version))
                .build()
        internal fun workName(category: SettingCategory, id: String, version: Long) =
            "${category.name}:$id:$version"
        private fun categoryTag(category: SettingCategory) =
            if (category == SettingCategory.HEALTH_RESTRICTIONS) TAG_RESTRICTIONS else TAG_REPORTS
        const val MAX_ATTEMPTS = 5
        private const val BACKOFF_SECONDS = 30L
        private fun String.toSettingCategory() = runCatching { SettingCategory.valueOf(this) }.getOrNull()
        private fun SettingCategory.outboxCategory() = when (this) {
            SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS -> HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS
            SettingCategory.HEALTH_RESTRICTIONS -> HealthSyncCategory.HEALTH_RESTRICTIONS
            else -> ""
        }
    }
}

interface HealthSyncScheduler {
    /** Schedules exactly the outbox item committed by a repository transaction. */
    suspend fun schedule(entry: com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity)
    suspend fun schedulePending(category: SettingCategory): Int
    suspend fun onCategoryChanged(category: SettingCategory, enabled: Boolean)
}

object NoOpHealthSyncScheduler : HealthSyncScheduler {
    override suspend fun schedule(entry: com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity) = Unit
    override suspend fun schedulePending(category: SettingCategory) = 0
    override suspend fun onCategoryChanged(category: SettingCategory, enabled: Boolean) = Unit
}

class WorkManagerHealthSyncScheduler @Inject constructor(
    private val manager: WorkManager,
    private val outboxDao: HealthSyncOutboxDao,
    private val settingsRepository: SettingsRepository? = null,
) : HealthSyncScheduler {
    override suspend fun schedule(entry: com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity) {
        entry.category.toSettingCategory()?.let { category ->
            if (settingsRepository != null && !settingsRepository.settings.first().healthSync.isEnabled(category)) return
            HealthSyncWorker.enqueue(manager, category, entry.syncId, entry.version)
        }
    }
    override suspend fun schedulePending(category: SettingCategory): Int {
        if (settingsRepository != null && !settingsRepository.settings.first().healthSync.isEnabled(category)) return 0
        val outbox = when (category) {
            SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS -> HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS
            SettingCategory.HEALTH_RESTRICTIONS -> HealthSyncCategory.HEALTH_RESTRICTIONS
            else -> return 0
        }
        return outboxDao.pending(outbox).onEach { HealthSyncWorker.enqueue(manager, category, it.syncId, it.version) }.size
    }
    override suspend fun onCategoryChanged(category: SettingCategory, enabled: Boolean) {
        if (category !in setOf(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS, SettingCategory.HEALTH_RESTRICTIONS)) return
        val tag = if (category == SettingCategory.HEALTH_RESTRICTIONS) HealthSyncWorker.TAG_RESTRICTIONS else HealthSyncWorker.TAG_REPORTS
        if (enabled) schedulePending(category) else manager.cancelAllWorkByTag(tag)
    }

    private fun String.toSettingCategory() = when (this) {
        HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS -> SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS
        HealthSyncCategory.HEALTH_RESTRICTIONS -> SettingCategory.HEALTH_RESTRICTIONS
        else -> null
    }
}
