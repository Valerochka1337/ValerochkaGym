package com.valerochka1337.valerochkagym.data.ai

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad

/** Editable result from the authenticated backend draft endpoint. */
sealed interface ExerciseAiGenerationResult {
  /**
   * A local id is only meaningful while the backend readiness snapshot that resolved it remains
   * current. The consumer checks this at its editor boundary, after any suspension it performs.
   */
  data class Existing(
      val exerciseId: Long,
      val isCurrent: suspend () -> Boolean = { true },
  ) : ExerciseAiGenerationResult

  data class New(
      val name: String,
      val type: ExerciseType,
      val loads: List<MuscleLoad>,
  ) : ExerciseAiGenerationResult

  data class Failure(val message: String) : ExerciseAiGenerationResult
}

interface ExerciseAiGenerator {
  suspend fun generate(description: String): ExerciseAiGenerationResult
}
