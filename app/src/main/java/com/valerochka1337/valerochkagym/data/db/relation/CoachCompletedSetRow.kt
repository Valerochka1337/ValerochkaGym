package com.valerochka1337.valerochkagym.data.db.relation

/** Flat completed-set history used only by the bounded Live Coach initiative policy. */
data class CoachCompletedSetRow(
    val setId: String,
    val workoutId: String,
    val exerciseId: String,
    val exerciseName: String,
    val setIndex: Int,
    val weightKg: Double?,
    val reps: Int?,
    val completedAtMillis: Long,
    val workoutFinishedAtMillis: Long?,
    val setType: String,
    val reportedFeelingsJson: String,
)
