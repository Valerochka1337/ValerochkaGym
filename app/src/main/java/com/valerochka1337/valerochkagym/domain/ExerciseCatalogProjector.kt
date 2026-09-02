package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.group
import com.valerochka1337.valerochkagym.data.db.relation.ExerciseWorkoutHistoryRow

enum class ExerciseCatalogSort { ALPHABETICAL, RECENT, FREQUENT }
enum class ExerciseCatalogOrigin { ALL, BUILT_IN, CUSTOM }

sealed interface ExerciseCatalogLevel {
    data object Overview : ExerciseCatalogLevel
    data class Group(val group: MuscleGroup) : ExerciseCatalogLevel
    data class MuscleLeaf(val group: MuscleGroup, val muscle: Muscle) : ExerciseCatalogLevel
}

data class ExerciseCatalogFilters(
    val group: MuscleGroup? = null,
    val muscle: Muscle? = null,
    val type: ExerciseType? = null,
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

data class ExerciseCatalogGroup(
    val group: MuscleGroup,
    val count: Int,
    val muscles: List<Muscle>,
)

data class ExerciseCatalogQuickSections(
    val recent: List<ExerciseEntity>,
    val frequent: List<ExerciseEntity>,
)

data class ExerciseCatalogResults(
    val exercises: List<ExerciseEntity>,
    val primary: List<ExerciseEntity> = emptyList(),
    val secondary: List<ExerciseEntity> = emptyList(),
)

/** Immutable projection. It never assigns an identity or mutates a catalog row. */
data class ExerciseCatalogProjection(
    val snapshot: ExerciseCatalogSnapshot,
    val groups: List<ExerciseCatalogGroup>,
    val historyByExercise: Map<Long, ExerciseCatalogHistory>,
) {
    val hasHistory: Boolean get() = historyByExercise.isNotEmpty()

    fun normalise(level: ExerciseCatalogLevel): ExerciseCatalogLevel = when (level) {
        ExerciseCatalogLevel.Overview -> level
        is ExerciseCatalogLevel.Group -> if (groups.any { it.group == level.group }) level else ExerciseCatalogLevel.Overview
        is ExerciseCatalogLevel.MuscleLeaf -> {
            val group = groups.firstOrNull { it.group == level.group }
            when {
                group == null -> ExerciseCatalogLevel.Overview
                level.muscle in group.muscles -> level
                else -> ExerciseCatalogLevel.Group(level.group)
            }
        }
    }

    fun results(
        query: String,
        filters: ExerciseCatalogFilters,
        sort: ExerciseCatalogSort,
        level: ExerciseCatalogLevel = ExerciseCatalogLevel.Overview,
    ): ExerciseCatalogResults {
        val selectedLevel = normalise(level)
        val candidates = snapshot.exercises.filter { exercise ->
            matchesQuery(exercise, query) && matchesFilters(exercise, filters) && matchesLevel(exercise, selectedLevel)
        }
        val ordered = sort(candidates, sort)
        if (selectedLevel !is ExerciseCatalogLevel.MuscleLeaf) return ExerciseCatalogResults(ordered)
        val contributions = snapshot.muscles
            .filter { it.muscle == selectedLevel.muscle && it.contribution >= ExerciseCatalogProjector.SECONDARY_THRESHOLD }
            .associateBy { it.exerciseId }
        val primary = ordered.filter { contributions[it.id]?.contribution ?: 0 >= ExerciseCatalogProjector.PRIMARY_THRESHOLD }
        val secondary = ordered.filter { it !in primary }
        return ExerciseCatalogResults(ordered, primary, secondary)
    }

    fun quickSections(): ExerciseCatalogQuickSections {
        if (!hasHistory) return ExerciseCatalogQuickSections(emptyList(), emptyList())
        val all = snapshot.exercises
        val recent = sort(all.filter { it.id in historyByExercise }, ExerciseCatalogSort.RECENT)
            .take(ExerciseCatalogProjector.QUICK_SECTION_LIMIT)
        return ExerciseCatalogQuickSections(
            recent = recent,
            frequent = sort(
                all.filter { it.id in historyByExercise && it.id !in recent.mapTo(hashSetOf()) { row -> row.id } },
                ExerciseCatalogSort.FREQUENT,
            ).take(ExerciseCatalogProjector.QUICK_SECTION_LIMIT),
        )
    }

    private fun matchesLevel(exercise: ExerciseEntity, level: ExerciseCatalogLevel): Boolean = when (level) {
        ExerciseCatalogLevel.Overview -> true
        is ExerciseCatalogLevel.Group -> exercise.muscleGroup == level.group
        is ExerciseCatalogLevel.MuscleLeaf -> exercise.muscleGroup == level.group && snapshot.muscles.any {
            it.exerciseId == exercise.id && it.muscle == level.muscle &&
                it.contribution >= ExerciseCatalogProjector.SECONDARY_THRESHOLD && it.muscle.group() == level.group
        }
    }

    private fun matchesFilters(exercise: ExerciseEntity, filters: ExerciseCatalogFilters): Boolean {
        if (filters.group != null && exercise.muscleGroup != filters.group) return false
        if (filters.type != null && exercise.type != filters.type) return false
        if (filters.origin == ExerciseCatalogOrigin.BUILT_IN && exercise.isCustom) return false
        if (filters.origin == ExerciseCatalogOrigin.CUSTOM && !exercise.isCustom) return false
        return filters.muscle == null || snapshot.muscles.any {
            it.exerciseId == exercise.id && it.muscle == filters.muscle && it.contribution >= ExerciseCatalogProjector.SECONDARY_THRESHOLD &&
                it.muscle.group() == exercise.muscleGroup
        }
    }

    private fun matchesQuery(exercise: ExerciseEntity, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        val labels = buildList {
            add(exercise.name)
            add(exercise.muscleGroup.displayName())
            add(exercise.type.displayName())
            snapshot.muscles.filter { it.exerciseId == exercise.id && it.contribution >= ExerciseCatalogProjector.SECONDARY_THRESHOLD }
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

object ExerciseCatalogProjector {
    const val PRIMARY_THRESHOLD = 60
    const val SECONDARY_THRESHOLD = 25
    const val QUICK_SECTION_LIMIT = 5

    fun project(snapshot: ExerciseCatalogSnapshot): ExerciseCatalogProjection {
        val availableIds = snapshot.exercises.mapTo(hashSetOf()) { it.id }
        val history = snapshot.history.filter { it.exerciseId in availableIds }
            .groupBy { it.exerciseId }
            .mapValues { (_, rows) ->
                ExerciseCatalogHistory(rows.maxOf { it.finishedAt }, rows.map { it.workoutId }.distinct().size)
            }
        val groups = snapshot.exercises.groupBy { it.muscleGroup }.map { (group, exercises) ->
            val muscles = if (group == MuscleGroup.CARDIO || group == MuscleGroup.FULL_BODY) emptyList() else {
                snapshot.muscles.asSequence()
                    .filter { it.exerciseId in exercises.mapTo(hashSetOf()) { exercise -> exercise.id } }
                    .filter { it.contribution >= SECONDARY_THRESHOLD && it.muscle.group() == group }
                    .map { it.muscle }
                    .distinct()
                    .sortedBy { it.ordinal }
                    .toList()
            }
            ExerciseCatalogGroup(group, exercises.size, muscles)
        }.sortedBy { it.group.ordinal }
        return ExerciseCatalogProjection(snapshot, groups, history)
    }
}
