package com.valerochka1337.valerochkagym.domain

import kotlinx.coroutines.flow.Flow

data class ExercisePersonalHint(val text: String, val updatedAt: Long)

/** Captured at editor-open so a reused local id cannot cross an account/cache boundary. */
data class HintEditTarget(
    val exerciseId: Long,
    val exerciseSyncId: String,
    val ownerScope: String?,
    val sessionEpoch: Long,
)

interface ExercisePersonalHintRepository {
  fun observe(exerciseId: Long): Flow<ExercisePersonalHint?>

  suspend fun editTarget(exerciseId: Long): HintEditTarget?

  suspend fun save(target: HintEditTarget, text: String): NoteSaveResult

  suspend fun unpin(target: HintEditTarget): NoteSaveResult
}
