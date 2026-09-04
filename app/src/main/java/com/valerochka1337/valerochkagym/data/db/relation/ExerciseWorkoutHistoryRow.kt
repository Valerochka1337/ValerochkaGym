package com.valerochka1337.valerochkagym.data.db.relation

/** One base exercise occurring in one finished workout. This is deliberately a read model. */
data class ExerciseWorkoutHistoryRow(
    val exerciseId: Long,
    val workoutId: String,
    val finishedAt: Long,
)
