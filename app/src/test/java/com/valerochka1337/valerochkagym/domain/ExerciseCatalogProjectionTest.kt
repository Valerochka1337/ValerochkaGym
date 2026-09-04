package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.relation.ExerciseWorkoutHistoryRow
import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseCatalogProjectionTest {
  @Test
  fun `search and filters combine as and without duplicates`() {
    val projection = project()
    val filters =
        ExerciseCatalogFilters(
            type = ExerciseCatalogTypeFilter.STRENGTH,
            origin = ExerciseCatalogOrigin.BUILT_IN,
        )

    assertEquals(
        listOf(1L, 2L, 3L),
        projection.results("груд", filters, ExerciseCatalogSort.ALPHABETICAL).exercises.map {
          it.id
        },
    )
    assertEquals(
        listOf(4L),
        projection
            .results("кардио", ExerciseCatalogFilters(), ExerciseCatalogSort.ALPHABETICAL)
            .exercises
            .map { it.id },
    )
  }

  @Test
  fun `recent and frequent use distinct finished workouts for the flat catalog`() {
    val projection = project()

    assertEquals(
        listOf(2L, 1L, 4L, 5L, 3L),
        projection.results("", ExerciseCatalogFilters(), ExerciseCatalogSort.RECENT).exercises.map {
          it.id
        },
    )
    assertEquals(
        listOf(1L, 2L, 4L, 5L, 3L),
        projection
            .results("", ExerciseCatalogFilters(), ExerciseCatalogSort.FREQUENT)
            .exercises
            .map { it.id },
    )
  }

  @Test
  fun `all origin sorting changes the flat result order`() {
    val projection = project()

    assertEquals(
        listOf(4L, 1L, 2L, 5L, 3L),
        projection
            .results("", ExerciseCatalogFilters(), ExerciseCatalogSort.ALPHABETICAL)
            .exercises
            .map { it.id },
    )
    assertEquals(
        listOf(2L, 1L, 4L, 5L, 3L),
        projection.results("", ExerciseCatalogFilters(), ExerciseCatalogSort.RECENT).exercises.map {
          it.id
        },
    )
    assertEquals(
        listOf(1L, 2L, 4L, 5L, 3L),
        projection
            .results("", ExerciseCatalogFilters(), ExerciseCatalogSort.FREQUENT)
            .exercises
            .map { it.id },
    )
  }

  @Test
  fun `unmapped and full body rows remain available in the flat catalog`() {
    val projection =
        ExerciseCatalogProjector.project(
            ExerciseCatalogSnapshot(
                exercises =
                    listOf(
                        exercise(10, "Без карты", MuscleGroup.CHEST),
                        exercise(11, "Берпи", MuscleGroup.FULL_BODY),
                    ),
                muscles = emptyList(),
                history = emptyList(),
            ),
        )

    assertEquals(
        listOf(10L, 11L),
        projection
            .results("", ExerciseCatalogFilters(), ExerciseCatalogSort.ALPHABETICAL)
            .exercises
            .map { it.id },
    )
  }

  @Test
  fun `type family joins cardio and timed and facet counts ignore their own dimension`() {
    val timed = exercise(6, "Планка", MuscleGroup.CORE, ExerciseType.TIMED)
    val projection =
        ExerciseCatalogProjector.project(
            project().snapshot.copy(exercises = project().snapshot.exercises + timed)
        )
    val filters = ExerciseCatalogFilters(group = MuscleGroup.CHEST)

    assertEquals(
        listOf(4L, 6L),
        projection
            .results(
                "",
                ExerciseCatalogFilters(type = ExerciseCatalogTypeFilter.CARDIO_OR_TIMED),
                ExerciseCatalogSort.ALPHABETICAL,
            )
            .exercises
            .map { it.id },
    )
    assertEquals(
        4,
        projection
            .facetCounts("", filters, ExerciseCatalogSort.ALPHABETICAL)
            .groups[MuscleGroup.CHEST],
    )
    assertEquals(
        0,
        projection
            .facetCounts("нет", filters, ExerciseCatalogSort.ALPHABETICAL)
            .groups[MuscleGroup.CHEST],
    )
  }

  private fun project(): ExerciseCatalogProjection =
      ExerciseCatalogProjector.project(
          ExerciseCatalogSnapshot(
              exercises =
                  listOf(
                      exercise(1, "Жим", MuscleGroup.CHEST),
                      exercise(2, "Разводка", MuscleGroup.CHEST),
                      exercise(3, "Стабилизация", MuscleGroup.CHEST),
                      exercise(4, "Бег", MuscleGroup.CARDIO, ExerciseType.CARDIO),
                      exercise(5, "Своя тяга", MuscleGroup.CHEST, custom = true),
                  ),
              muscles =
                  listOf(
                      ExerciseMuscleEntity(1, Muscle.UPPER_CHEST, 100),
                      ExerciseMuscleEntity(
                          1,
                          Muscle.LATS,
                          100,
                      ), // mismatched map must not leak to back
                      ExerciseMuscleEntity(2, Muscle.UPPER_CHEST, 40),
                      ExerciseMuscleEntity(3, Muscle.UPPER_CHEST, 24),
                  ),
              history =
                  listOf(
                      ExerciseWorkoutHistoryRow(1, "a", 100),
                      ExerciseWorkoutHistoryRow(1, "a", 100),
                      ExerciseWorkoutHistoryRow(1, "b", 200),
                      ExerciseWorkoutHistoryRow(2, "c", 300),
                  ),
          ),
      )

  private fun exercise(
      id: Long,
      name: String,
      group: MuscleGroup,
      type: ExerciseType = ExerciseType.STRENGTH,
      custom: Boolean = false,
  ) = ExerciseEntity(id = id, name = name, muscleGroup = group, type = type, isCustom = custom)
}
