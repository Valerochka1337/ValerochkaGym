package com.valerochka1337.valerochkagym.domain

/** The only key allowed for performance comparison and previous-set lookup. */
data class ExerciseExecutionKey(
    val exerciseId: Long,
    /** null deliberately represents the independent "Без варианта" group. */
    val variantSyncId: String? = null,
)
