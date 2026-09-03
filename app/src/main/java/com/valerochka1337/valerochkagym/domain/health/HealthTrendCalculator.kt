package com.valerochka1337.valerochkagym.domain.health

/** Builds only honest numeric series: method/source/material changes are separate series. */
object HealthTrendCalculator {
    fun series(observations: List<HealthObservationDraft>): List<HealthTrendSeries> =
        observations.asSequence()
            .filter { it.canonicalKey != null }
            .mapNotNull { observation ->
                val numeric = observation.value as? HealthRawValue.Number ?: return@mapNotNull null
                TrendInput(observation, numeric.value)
            }
            .groupBy { input ->
                TrendKey(
                    input.observation.canonicalKey!!,
                    input.observation.unit,
                    input.observation.material,
                    input.observation.method,
                    input.observation.source,
                )
            }
            .map { (key, inputs) ->
                HealthTrendSeries(
                    key.canonicalKey, key.unit, key.material, key.method, key.source,
                    inputs.sortedBy { it.observation.observedAt }.map {
                        HealthTrendPoint(it.observation.observedAt, it.value, it.observation.referenceRange)
                    },
                )
            }
            .toList()

    /** Relative changes have no meaning for a zero base or anything other than two numeric points. */
    fun relativeChangePercent(base: HealthTrendPoint, current: HealthTrendPoint): Double? =
        if (base.value == 0.0) null else (current.value - base.value) / base.value * 100.0

    private data class TrendInput(val observation: HealthObservationDraft, val value: Double)
    private data class TrendKey(
        val canonicalKey: String,
        val unit: String?,
        val material: String?,
        val method: String?,
        val source: String?,
    )
}
