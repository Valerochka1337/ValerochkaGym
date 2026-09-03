package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.group
import com.valerochka1337.valerochkagym.data.db.relation.ExerciseWorkoutHistoryRow

enum class ExerciseCatalogSort { ALPHABETICAL, RECENT, FREQUENT }
enum class ExerciseCatalogOrigin { ALL, BUILT_IN, CUSTOM }
enum class ExerciseCatalogTypeFilter { ALL, STRENGTH, CARDIO_OR_TIMED }

data class ExerciseCatalogFilters(
    val group: MuscleGroup? = null,
    val type: ExerciseCatalogTypeFilter = ExerciseCatalogTypeFilter.ALL,
    val origin: ExerciseCatalogOrigin = ExerciseCatalogOrigin.ALL,
)

data class ExerciseCatalogSnapshot(
    val exercises: List<ExerciseEntity>,
    val muscles: List<ExerciseMuscleEntity>,
    val history: List<ExerciseWorkoutHistoryRow>,
)

data class ExerciseCatalogHistory(
    val lastFinishedAt: Long,
    val completedWorkoutCount: Int,
)

data class ExerciseCatalogResults(val exercises: List<ExerciseEntity>)

data class ExerciseCatalogFacetCounts(
    val types: Map<ExerciseCatalogTypeFilter, Int>,
    val origins: Map<ExerciseCatalogOrigin, Int>,
    val groups: Map<MuscleGroup?, Int>,
    val sortCount: Int,
)

/** Immutable projection. It never assigns an identity or mutates a catalog row. */
data class ExerciseCatalogProjection(
    val snapshot: ExerciseCatalogSnapshot,
    val historyByExercise: Map<Long, ExerciseCatalogHistory>,
) {
    fun results(
        query: String,
        filters: ExerciseCatalogFilters,
        sort: ExerciseCatalogSort,
    ): ExerciseCatalogResults {
        val candidates = snapshot.exercises.filter { exercise ->
            matchesQuery(exercise, query) && matchesFilters(exercise, filters)
        }
        return ExerciseCatalogResults(sort(candidates, sort))
    }

    /** Counts use all other dimensions, but intentionally ignore the chip's own dimension. */
    fun facetCounts(
        query: String,
        filters: ExerciseCatalogFilters,
        sort: ExerciseCatalogSort,
    ): ExerciseCatalogFacetCounts {
        fun count(candidate: ExerciseCatalogFilters) = results(query, candidate, sort).exercises.size
        return ExerciseCatalogFacetCounts(
            types = ExerciseCatalogTypeFilter.entries.associateWith { type -> count(filters.copy(type = type)) },
            origins = ExerciseCatalogOrigin.entries.associateWith { origin -> count(filters.copy(origin = origin)) },
            groups = (listOf(null) + MuscleGroup.entries).associateWith { group -> count(filters.copy(group = group)) },
            sortCount = results(query, filters, sort).exercises.size,
        )
    }

    private fun matchesFilters(exercise: ExerciseEntity, filters: ExerciseCatalogFilters): Boolean {
        if (filters.group != null && exercise.muscleGroup != filters.group) return false
        if (!filters.type.matches(exercise.type)) return false
        if (filters.origin == ExerciseCatalogOrigin.BUILT_IN && exercise.isCustom) return false
        if (filters.origin == ExerciseCatalogOrigin.CUSTOM && !exercise.isCustom) return false
        return true
    }

    private fun matchesQuery(exercise: ExerciseEntity, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        val labels = buildList {
            add(exercise.name)
            add(exercise.muscleGroup.displayName())
            add(exercise.type.displayName())
            snapshot.muscles.filter {
                it.exerciseId == exercise.id &&
                    it.contribution >= ExerciseCatalogProjector.SECONDARY_THRESHOLD &&
                    it.muscle.group() == exercise.muscleGroup
            }
                .forEach { add(it.muscle.displayName()) }
        }
        return labels.any { it.contains(needle, ignoreCase = true) }
    }

    private fun sort(exercises: List<ExerciseEntity>, sort: ExerciseCatalogSort): List<ExerciseEntity> =
        when (sort) {
            ExerciseCatalogSort.ALPHABETICAL -> exercises.sortedBy { it.name.lowercase() }
            ExerciseCatalogSort.RECENT -> exercises.sortedWith(
                compareByDescending<ExerciseEntity> { historyByExercise[it.id]?.lastFinishedAt ?: Long.MIN_VALUE }
                    .thenBy { it.name.lowercase() },
            )
            ExerciseCatalogSort.FREQUENT -> exercises.sortedWith(
                compareByDescending<ExerciseEntity> { historyByExercise[it.id]?.completedWorkoutCount ?: 0 }
                    .thenByDescending { historyByExercise[it.id]?.lastFinishedAt ?: Long.MIN_VALUE }
                    .thenBy { it.name.lowercase() },
            )
        }
}

fun ExerciseCatalogTypeFilter.matches(type: ExerciseType): Boolean = when (this) {
    ExerciseCatalogTypeFilter.ALL -> true
    ExerciseCatalogTypeFilter.STRENGTH -> type == ExerciseType.STRENGTH
    ExerciseCatalogTypeFilter.CARDIO_OR_TIMED -> type == ExerciseType.CARDIO || type == ExerciseType.TIMED
}

object ExerciseCatalogProjector {
    const val SECONDARY_THRESHOLD = 25

    fun project(snapshot: ExerciseCatalogSnapshot): ExerciseCatalogProjection {
        val availableIds = snapshot.exercises.mapTo(hashSetOf()) { it.id }
        val history = snapshot.history.filter { it.exerciseId in availableIds }
            .groupBy { it.exerciseId }
            .mapValues { (_, rows) ->
                ExerciseCatalogHistory(rows.maxOf { it.finishedAt }, rows.map { it.workoutId }.distinct().size)
            }
        return ExerciseCatalogProjection(snapshot, history)
    }
}
