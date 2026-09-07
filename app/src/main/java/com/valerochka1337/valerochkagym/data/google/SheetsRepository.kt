package com.valerochka1337.valerochkagym.data.google

sealed interface UploadResult {
  data object Success : UploadResult

  data class PermanentFailure(val reason: String) : UploadResult

  data class TransientFailure(val error: String) : UploadResult
}

/** Выгрузка тренировок, замеров и пользовательских программ в выбранную Google-таблицу. */
interface SheetsRepository {
  suspend fun uploadWorkout(workoutId: String): UploadResult

  suspend fun uploadMeasurement(measurementId: String): UploadResult

  suspend fun uploadRoutine(routineSyncId: String): UploadResult

  suspend fun uploadRoutineDeletion(routineSyncId: String, updatedAt: Long): UploadResult
}
