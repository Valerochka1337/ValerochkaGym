package com.valerochka1337.valerochkagym.data.google

interface ConfigurationSheetsRepository {
  suspend fun uploadExercise(syncId: String): UploadResult

  suspend fun uploadGym(syncId: String): UploadResult

  suspend fun uploadGymDeletion(syncId: String, updatedAt: Long): UploadResult

  suspend fun uploadRoutineGyms(routineSyncId: String): UploadResult

  suspend fun uploadRoutineGymsDeletion(routineSyncId: String, updatedAt: Long): UploadResult
}

object NoOpConfigurationSheetsRepository : ConfigurationSheetsRepository {
  override suspend fun uploadExercise(syncId: String) = UploadResult.Success

  override suspend fun uploadGym(syncId: String) = UploadResult.Success

  override suspend fun uploadGymDeletion(syncId: String, updatedAt: Long) = UploadResult.Success

  override suspend fun uploadRoutineGyms(routineSyncId: String) = UploadResult.Success

  override suspend fun uploadRoutineGymsDeletion(routineSyncId: String, updatedAt: Long) =
      UploadResult.Success
}
