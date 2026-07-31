package com.valerochka1337.valerochkagym.data.db

import kotlinx.serialization.Serializable

@Serializable
data class PlannedSet(
    val weightKg: Double? = null,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val speedKmh: Double? = null,
    val inclinePct: Double? = null,
)
