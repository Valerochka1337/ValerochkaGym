package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.relation.ExerciseWorkoutHistoryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseCatalogProjectionTest {
    @Test
    fun `muscle leaves separate direct and secondary loads without changing exercise ids`() {
        val projection = project()

        val result = projection.results("", ExerciseCatalogFilters(), ExerciseCatalogSort.ALPHABETICAL,
            ExerciseCatalogLevel.MuscleLeaf(MuscleGroup.CHEST, Muscle.CHEST))

        assertEquals(listOf(1L), result.primary.map { it.id })
        assertEquals(listOf(2L), result.secondary.map { it.id })
        assertFalse(result.exercises.any { it.id == 3L })
    }

    @Test
    fun `top group follows stored group and cardio has no fabricated muscle leaves`() {
        val projection = project()

        assertEquals(listOf(Muscle.CHEST), projection.groups.single { it.group == MuscleGroup.CHEST }.muscles)
        assertTrue(projection.groups.single { it.group == MuscleGroup.CARDIO }.muscles.isEmpty())
    }

    @Test
    fun `search and filters combine as and without duplicates`() {
        val projection = project()
        val filters = ExerciseCatalogFilters(type = ExerciseType.STRENGTH, origin = ExerciseCatalogOrigin.BUILT_IN)

        assertEquals(listOf(1L, 2L, 3L), projection.results("груд", filters, ExerciseCatalogSort.ALPHABETICAL).exercises.map { it.id })
        assertEquals(listOf(4L), projection.results("кардио", ExerciseCatalogFilters(), ExerciseCatalogSort.ALPHABETICAL).exercises.map { it.id })
    }

    @Test
    fun `recent and frequent use distinct finished workouts`() {
        val projection = project()

        assertEquals(listOf(2L, 1L), projection.quickSections().recent.map { it.id })
        assertTrue(projection.quickSections().frequent.isEmpty())
    }

    @Test
    fun `quick sections are capped and frequent excludes recent ids`() {
        val exercises = (1L..8L).map { exercise(it, "Упражнение $it", MuscleGroup.CHEST) }
        val projection = ExerciseCatalogProjector.project(
            ExerciseCatalogSnapshot(
                exercises,
                emptyList(),
                exercises.flatMapIndexed { index, exercise ->
                    listOf(ExerciseWorkoutHistoryRow(exercise.id, "w$index", index.toLong()))
                },
            ),
        )

        val quick = projection.quickSections()
        assertEquals(5, quick.recent.size)
        assertEquals(3, quick.frequent.size)
        assertTrue(quick.recent.map { it.id }.intersect(quick.frequent.map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun `under twenty five contribution is excluded from search facets and leaves`() {
        val projection = project()

        assertTrue(projection.results("грудь", ExerciseCatalogFilters(muscle = Muscle.CHEST), ExerciseCatalogSort.ALPHABETICAL)
            .exercises.none { it.id == 3L })
    }

    @Test
    fun `no history hides both quick sections while unmapped and full body stay in all group`() {
        val projection = ExerciseCatalogProjector.project(
            ExerciseCatalogSnapshot(
                exercises = listOf(
                    exercise(10, "Без карты", MuscleGroup.CHEST),
                    exercise(11, "Берпи", MuscleGroup.FULL_BODY),
                ),
                muscles = emptyList(), history = emptyList(),
            ),
        )

        assertTrue(projection.quickSections().recent.isEmpty())
        assertTrue(projection.quickSections().frequent.isEmpty())
        assertEquals(listOf(10L), projection.results("", ExerciseCatalogFilters(), ExerciseCatalogSort.ALPHABETICAL,
            ExerciseCatalogLevel.Group(MuscleGroup.CHEST)).exercises.map { it.id })
        assertTrue(projection.groups.single { it.group == MuscleGroup.FULL_BODY }.muscles.isEmpty())
    }

    private fun project(): ExerciseCatalogProjection = ExerciseCatalogProjector.project(
        ExerciseCatalogSnapshot(
            exercises = listOf(
                exercise(1, "Жим", MuscleGroup.CHEST),
                exercise(2, "Разводка", MuscleGroup.CHEST),
                exercise(3, "Стабилизация", MuscleGroup.CHEST),
                exercise(4, "Бег", MuscleGroup.CARDIO, ExerciseType.CARDIO),
                exercise(5, "Своя тяга", MuscleGroup.CHEST, custom = true),
            ),
            muscles = listOf(
                ExerciseMuscleEntity(1, Muscle.CHEST, 100),
                ExerciseMuscleEntity(1, Muscle.LATS, 100), // mismatched map must not leak to back
                ExerciseMuscleEntity(2, Muscle.CHEST, 40),
                ExerciseMuscleEntity(3, Muscle.CHEST, 24),
            ),
            history = listOf(
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
