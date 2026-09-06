package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.relation.ExerciseWorkoutHistoryRow
import com.valerochka1337.valerochkagym.domain.ExerciseEquipmentRequirements.ExplicitNone
import com.valerochka1337.valerochkagym.domain.ExerciseEquipmentRequirements.Required
import com.valerochka1337.valerochkagym.domain.ExerciseEquipmentRequirements.UnknownLegacy
import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseCatalogProjectionTest {

  @Test
  fun `equipment facet uses OR while group and origin remain AND`() {
    val projection =
        ExerciseCatalogProjector.project(
            ExerciseCatalogSnapshot(
                exercises =
                    listOf(
                        exercise(1, "Жим гантелей", MuscleGroup.CHEST, custom = true),
                        exercise(2, "Жим штанги", MuscleGroup.CHEST),
                        exercise(3, "Присед", MuscleGroup.LEGS),
                    ),
                muscles = emptyList(),
                history = emptyList(),
                requirementsByExercise =
                    mapOf(
                        1L to Required(setOf("dumbbells", "flat_bench")),
                        2L to Required(setOf("barbell", "flat_bench")),
                        3L to Required(setOf("barbell")),
                    ),
            ),
        )

    val result =
        projection.results(
            query = "жим",
            filters =
                ExerciseCatalogFilters(
                    group = MuscleGroup.CHEST,
                    origin = ExerciseCatalogOrigin.ALL,
                    equipment = ExerciseCatalogEquipmentFilter(setOf("dumbbells", "barbell")),
                ),
            sort = ExerciseCatalogSort.ALPHABETICAL,
        )

    assertEquals(listOf(1L, 2L), result.exercises.map { it.id })
  }

  @Test
  fun `equipment facet excludes unknown but includes only explicitly declared requirements`() {
    val projection =
        ExerciseCatalogProjector.project(
            ExerciseCatalogSnapshot(
                exercises =
                    listOf(
                        exercise(1, "Старое", MuscleGroup.CHEST),
                        exercise(2, "Без инвентаря", MuscleGroup.CHEST),
                        exercise(3, "С гантелями", MuscleGroup.CHEST),
                    ),
                muscles = emptyList(),
                history = emptyList(),
                requirementsByExercise =
                    mapOf(
                        1L to UnknownLegacy,
                        2L to ExplicitNone,
                        3L to Required(setOf("dumbbells")),
                    ),
            ),
        )

    assertEquals(
        listOf(3L),
        projection
            .results(
                "",
                ExerciseCatalogFilters(
                    equipment = ExerciseCatalogEquipmentFilter(setOf("dumbbells"))
                ),
                ExerciseCatalogSort.ALPHABETICAL,
            )
            .exercises
            .map { it.id },
    )
    assertEquals(
        listOf(2L),
        projection
            .results(
                "",
                ExerciseCatalogFilters(
                    equipment = ExerciseCatalogEquipmentFilter(includeExplicitNone = true),
                ),
                ExerciseCatalogSort.ALPHABETICAL,
            )
            .exercises
            .map { it.id },
    )
  }

  @Test
  fun `equipment facet uses provided capabilities without treating decline as adjustable`() {
    val projection =
        ExerciseCatalogProjector.project(
            ExerciseCatalogSnapshot(
                exercises =
                    listOf(
                        exercise(1, "Горизонтальный", MuscleGroup.CHEST),
                        exercise(2, "Наклонный", MuscleGroup.CHEST),
                        exercise(3, "Отрицательный", MuscleGroup.CHEST),
                        exercise(4, "Нордический", MuscleGroup.LEGS),
                    ),
                muscles = emptyList(),
                history = emptyList(),
                requirementsByExercise =
                    mapOf(
                        1L to Required(setOf("flat_bench")),
                        2L to Required(setOf("incline_bench")),
                        3L to Required(setOf("decline_bench")),
                        4L to Required(setOf("ankle_anchor")),
                    ),
            ),
        )

    assertEquals(
        listOf(1L, 2L),
        projection
            .results(
                "",
                ExerciseCatalogFilters(
                    equipment = ExerciseCatalogEquipmentFilter(setOf("adjustable_bench")),
                ),
                ExerciseCatalogSort.ALPHABETICAL,
            )
            .exercises
            .map { it.id },
    )
    assertEquals(
        listOf(4L),
        projection
            .results(
                "",
                ExerciseCatalogFilters(
                    equipment = ExerciseCatalogEquipmentFilter(setOf("nordic_bench")),
                ),
                ExerciseCatalogSort.ALPHABETICAL,
            )
            .exercises
            .map { it.id },
    )
  }

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
