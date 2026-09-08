package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.group
import com.valerochka1337.valerochkagym.data.db.relation.ExerciseWorkoutHistoryRow

enum class ExerciseCatalogSort {
  ALPHABETICAL,
  RECENT,
  FREQUENT,
}

enum class ExerciseCatalogOrigin {
  ALL,
  BUILT_IN,
  CUSTOM,
}

enum class ExerciseCatalogTypeFilter {
  ALL,
  STRENGTH,
  CARDIO_OR_TIMED,
}

/** A multi-select facet: equipment values and explicit no-equipment are joined with OR. */
data class ExerciseCatalogEquipmentFilter(
    val equipmentIds: Set<String> = emptySet(),
    val includeExplicitNone: Boolean = false,
)

data class ExerciseCatalogFilters(
    val group: MuscleGroup? = null,
    val type: ExerciseCatalogTypeFilter = ExerciseCatalogTypeFilter.ALL,
    val origin: ExerciseCatalogOrigin = ExerciseCatalogOrigin.ALL,
    val equipment: ExerciseCatalogEquipmentFilter = ExerciseCatalogEquipmentFilter(),
)

data class ExerciseCatalogSnapshot(
    val exercises: List<ExerciseEntity>,
    val muscles: List<ExerciseMuscleEntity>,
    val history: List<ExerciseWorkoutHistoryRow>,
    /** Unknown legacy requirements stay distinct from an intentionally empty requirement set. */
    val requirementsByExercise: Map<Long, ExerciseEquipmentRequirements> = emptyMap(),
    val equipmentRevision: Int = 0,
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
    val equipment: Map<String, Int> = emptyMap(),
    val explicitNoneEquipment: Int = 0,
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
    val search = TextSearch(query)
    val candidates =
        snapshot.exercises.filter { exercise ->
          !exercise.archived && matchesQuery(exercise, search) && matchesFilters(exercise, filters)
        }
    return ExerciseCatalogResults(sort(candidates, sort))
  }

  /** Counts use all other dimensions, but intentionally ignore the chip's own dimension. */
  fun facetCounts(
      query: String,
      filters: ExerciseCatalogFilters,
      sort: ExerciseCatalogSort,
      equipmentIds: Set<String> =
          snapshot.requirementsByExercise.values
              .filterIsInstance<ExerciseEquipmentRequirements.Required>()
              .flatMapTo(linkedSetOf()) { it.equipmentIds },
  ): ExerciseCatalogFacetCounts {
    val search = TextSearch(query)
    val matching = snapshot.exercises.filter { !it.archived && matchesQuery(it, search) }
    fun count(candidate: ExerciseCatalogFilters) = matching.count { matchesFilters(it, candidate) }
    return ExerciseCatalogFacetCounts(
        types =
            ExerciseCatalogTypeFilter.entries.associateWith { type ->
              count(filters.copy(type = type))
            },
        origins =
            ExerciseCatalogOrigin.entries.associateWith { origin ->
              count(filters.copy(origin = origin))
            },
        groups =
            (listOf(null) + MuscleGroup.entries).associateWith { group ->
              count(filters.copy(group = group))
            },
        equipment =
            equipmentIds.associateWith { equipmentId ->
              count(
                  filters.copy(
                      equipment = ExerciseCatalogEquipmentFilter(setOf(equipmentId)),
                  ),
              )
            },
        explicitNoneEquipment =
            count(
                filters.copy(equipment = ExerciseCatalogEquipmentFilter(includeExplicitNone = true))
            ),
        sortCount = count(filters),
    )
  }

  private fun matchesFilters(exercise: ExerciseEntity, filters: ExerciseCatalogFilters): Boolean {
    if (filters.group != null && exercise.muscleGroup != filters.group) return false
    if (!filters.type.matches(exercise.type)) return false
    if (filters.origin == ExerciseCatalogOrigin.BUILT_IN && exercise.origin != "STANDARD")
        return false
    if (filters.origin == ExerciseCatalogOrigin.CUSTOM && exercise.origin != "PERSONAL")
        return false
    val equipmentFilter = filters.equipment
    if (equipmentFilter.equipmentIds.isNotEmpty() || equipmentFilter.includeExplicitNone) {
      when (val requirements = snapshot.requirementsByExercise[exercise.id]) {
        ExerciseEquipmentRequirements.ExplicitNone -> {
          if (!equipmentFilter.includeExplicitNone) return false
        }
        is ExerciseEquipmentRequirements.Required -> {
          if (
              equipmentFilter.equipmentIds.none { selected ->
                requirements.equipmentIds.any { required ->
                  LocalEquipmentCatalog.covers(setOf(selected), required)
                }
              }
          )
              return false
        }
        ExerciseEquipmentRequirements.UnknownLegacy,
        null -> return false
      }
    }
    return true
  }

  private fun matchesQuery(exercise: ExerciseEntity, search: TextSearch): Boolean {
    val labels = buildList {
      add(exercise.name)
      add(exercise.muscleGroup.displayName())
      add(exercise.type.displayName())
      snapshot.muscles
          .filter {
            it.exerciseId == exercise.id &&
                it.contribution in setOf(100, 50) &&
                it.muscle.group() == exercise.muscleGroup
          }
          .forEach { add(it.muscle.displayName()) }
    }
    return search.matches(labels)
  }

  private fun sort(
      exercises: List<ExerciseEntity>,
      sort: ExerciseCatalogSort,
  ): List<ExerciseEntity> =
      when (sort) {
        ExerciseCatalogSort.ALPHABETICAL -> exercises.sortedBy { it.name.lowercase() }
        ExerciseCatalogSort.RECENT ->
            exercises.sortedWith(
                compareByDescending<ExerciseEntity> {
                      historyByExercise[it.id]?.lastFinishedAt ?: Long.MIN_VALUE
                    }
                    .thenBy { it.name.lowercase() },
            )
        ExerciseCatalogSort.FREQUENT ->
            exercises.sortedWith(
                compareByDescending<ExerciseEntity> {
                      historyByExercise[it.id]?.completedWorkoutCount ?: 0
                    }
                    .thenByDescending { historyByExercise[it.id]?.lastFinishedAt ?: Long.MIN_VALUE }
                    .thenBy { it.name.lowercase() },
            )
      }
}

fun ExerciseCatalogTypeFilter.matches(type: ExerciseType): Boolean =
    when (this) {
      ExerciseCatalogTypeFilter.ALL -> true
      ExerciseCatalogTypeFilter.STRENGTH -> type == ExerciseType.STRENGTH
      ExerciseCatalogTypeFilter.CARDIO_OR_TIMED ->
          type == ExerciseType.CARDIO || type == ExerciseType.TIMED
    }

object ExerciseCatalogProjector {
  fun project(snapshot: ExerciseCatalogSnapshot): ExerciseCatalogProjection {
    val availableIds = snapshot.exercises.mapTo(hashSetOf()) { it.id }
    val history =
        snapshot.history
            .filter { it.exerciseId in availableIds }
            .groupBy { it.exerciseId }
            .mapValues { (_, rows) ->
              ExerciseCatalogHistory(
                  rows.maxOf { it.finishedAt },
                  rows.map { it.workoutId }.distinct().size,
              )
            }
    return ExerciseCatalogProjection(snapshot, history)
  }
}
