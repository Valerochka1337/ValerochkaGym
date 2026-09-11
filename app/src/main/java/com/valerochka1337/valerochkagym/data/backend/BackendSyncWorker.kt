package com.valerochka1337.valerochkagym.data.backend

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.InvalidationTracker
import androidx.work.*
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.health.HealthLedgerSync
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@HiltWorker
class BackendSyncWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: BackendSync,
    private val health: HealthLedgerSync,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result =
      try {
        sync.run()
        health.replayPending()
        Result.success()
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        if (
            e is BackendException &&
                e.code in
                    setOf(
                        "workout_not_acknowledged",
                        "journal_workout_missing",
                        "journal_page_limit",
                    )
        )
            Result.retry()
        else if (e is BackendException && e.status in setOf(400, 401, 403, 409, 413))
            Result.failure()
        else Result.retry()
      }
}

@Singleton
class BackendSyncScheduler
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val database: GymDatabase,
) {
  private var started = false

  fun enqueue() {
    val work =
        OneTimeWorkRequestBuilder<BackendSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(2, TimeUnit.SECONDS)
            .build()
    WorkManager.getInstance(context)
        .enqueueUniqueWork("backend_sync", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
  }

  @Synchronized
  fun start() {
    if (started) return
    started = true
    // Room writes and the trigger's durable dirty marker commit together. The periodic job also
    // discovers committed writes if the process died before an invalidation callback was delivered.
    database.invalidationTracker.addObserver(
        object : InvalidationTracker.Observer(SyncSchema.trackedTables + arrayOf("coach_journal")) {
          override fun onInvalidated(tables: Set<String>) {
            enqueue()
          }
        }
    )
    val periodic =
        PeriodicWorkRequestBuilder<BackendSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
    WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork("backend_periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
    enqueue()
  }
}
