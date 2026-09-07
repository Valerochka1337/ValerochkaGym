package com.valerochka1337.valerochkagym.data.backend

import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.google.*
import javax.inject.Inject
import javax.inject.Singleton

/** Compatibility for already enqueued workers. All production uploads now use the backend. */
@Singleton
class BackendUploadAdapter
@Inject
constructor(
    private val sync: BackendSync,
    private val database: GymDatabase,
    private val tokens: BackendTokenStore,
) : SheetsRepository, ConfigurationSheetsRepository, WorkoutImportRepository {
  private suspend fun upload(): UploadResult =
      try {
        if (tokens.session.value == null)
            UploadResult.PermanentFailure("Войдите в аккаунт ValerochkaGym")
        else if (sync.hasActiveWorkout())
            UploadResult.TransientFailure("Синхронизация продолжится после тренировки")
        else {
          sync.run()
          UploadResult.Success
        }
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        if (e is BackendException && e.status in setOf(400, 401, 403, 409, 413))
            UploadResult.PermanentFailure(e.message)
        else UploadResult.TransientFailure("Нет соединения с сервером")
      }

  override suspend fun uploadWorkout(workoutId: String) = upload()

  override suspend fun uploadMeasurement(measurementId: String) = upload()

  override suspend fun uploadRoutine(routineSyncId: String) = upload()

  override suspend fun uploadRoutineDeletion(routineSyncId: String, updatedAt: Long) = upload()

  override suspend fun uploadExercise(syncId: String) = upload()

  override suspend fun uploadGym(syncId: String) = upload()

  override suspend fun uploadGymDeletion(syncId: String, updatedAt: Long) = upload()

  override suspend fun uploadRoutineGyms(routineSyncId: String) = upload()

  override suspend fun uploadRoutineGymsDeletion(routineSyncId: String, updatedAt: Long) = upload()

  override suspend fun importAll(): ImportResult =
      when (val result = upload()) {
        UploadResult.Success -> ImportResult.NothingToImport
        is UploadResult.PermanentFailure -> ImportResult.Failure(result.reason)
        is UploadResult.TransientFailure -> ImportResult.Failure(result.error)
      }
}
