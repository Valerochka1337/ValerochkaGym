package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.defaultMuscleLoads
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.seedExerciseMuscles
import com.valerochka1337.valerochkagym.data.db.seedExercises
import com.valerochka1337.valerochkagym.data.db.seedMissingExerciseMuscles
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Карта вовлечения мышц: хранение, замена, каскад и досев отсутствующих карт. */
class ExerciseMuscleDaoTest : RoomDaoTest() {

    private lateinit var exerciseDao: ExerciseDao
    private lateinit var muscleDao: ExerciseMuscleDao

    @Before
    fun setUp() {
        exerciseDao = db.exerciseDao()
        muscleDao = db.exerciseMuscleDao()
    }

    // region storage

    @Test
    fun `getForExercise returns muscles from the strongest involvement down`() = runTest {
        val exerciseId = insertExercise("Жим штанги лёжа", MuscleGroup.CHEST)
        muscleDao.upsertAll(
            listOf(
                ExerciseMuscleEntity(exerciseId, Muscle.TRICEPS, 65),
                ExerciseMuscleEntity(exerciseId, Muscle.CHEST, 100),
                ExerciseMuscleEntity(exerciseId, Muscle.FRONT_DELTS, 60),
            ),
        )

        val muscles = muscleDao.getForExercise(exerciseId).map { it.muscle }

        assertEquals(listOf(Muscle.CHEST, Muscle.TRICEPS, Muscle.FRONT_DELTS), muscles)
    }

    @Test
    fun `upsert overwrites the contribution of an already stored muscle`() = runTest {
        val exerciseId = insertExercise("Жим штанги лёжа", MuscleGroup.CHEST)
        muscleDao.upsertAll(listOf(ExerciseMuscleEntity(exerciseId, Muscle.CHEST, 80)))

        muscleDao.upsertAll(listOf(ExerciseMuscleEntity(exerciseId, Muscle.CHEST, 100)))

        val rows = muscleDao.getForExercise(exerciseId)
        assertEquals(1, rows.size)
        assertEquals(100, rows.single().contribution)
    }

    @Test
    fun `replaceForExercise drops muscles that are no longer selected`() = runTest {
        val exerciseId = insertExercise("Тяга", MuscleGroup.BACK)
        muscleDao.upsertAll(
            listOf(
                ExerciseMuscleEntity(exerciseId, Muscle.LATS, 100),
                ExerciseMuscleEntity(exerciseId, Muscle.BICEPS, 50),
            ),
        )

        muscleDao.replaceForExercise(exerciseId, listOf(ExerciseMuscleEntity(exerciseId, Muscle.LATS, 90)))

        val rows = muscleDao.getForExercise(exerciseId)
        assertEquals(1, rows.size)
        assertEquals(Muscle.LATS, rows.single().muscle)
        assertEquals(90, rows.single().contribution)
    }

    @Test
    fun `deleting an exercise cascades to its muscle map`() = runTest {
        val exerciseId = insertExercise("Тяга", MuscleGroup.BACK)
        muscleDao.upsertAll(listOf(ExerciseMuscleEntity(exerciseId, Muscle.LATS, 100)))

        db.openHelper.writableDatabase.execSQL("DELETE FROM exercises WHERE id = $exerciseId")

        assertEquals(0, tableCount("exercise_muscles"))
    }

    // endregion

    // region seeding

    @Test
    fun `seeding uses the curated map for a catalogue exercise`() = runTest {
        val exerciseId = insertExercise("Жим штанги лёжа", MuscleGroup.CHEST)

        seedMissingExerciseMuscles(exerciseDao, muscleDao)

        val expected = seedExerciseMuscles.getValue("жим штанги лёжа")
            .associate { it.muscle to it.contribution }
        val actual = muscleDao.getForExercise(exerciseId).associate { it.muscle to it.contribution }
        assertEquals(expected, actual)
    }

    @Test
    fun `seeding falls back to the muscle group for an unknown exercise`() = runTest {
        val exerciseId = insertExercise("Жим одной левой", MuscleGroup.CHEST)

        seedMissingExerciseMuscles(exerciseDao, muscleDao)

        val expected = MuscleGroup.CHEST.defaultMuscleLoads().associate { it.muscle to it.contribution }
        val actual = muscleDao.getForExercise(exerciseId).associate { it.muscle to it.contribution }
        assertEquals(expected, actual)
    }

    @Test
    fun `seeding keeps a map that already exists`() = runTest {
        val exerciseId = insertExercise("Жим штанги лёжа", MuscleGroup.CHEST)
        muscleDao.upsertAll(listOf(ExerciseMuscleEntity(exerciseId, Muscle.CALVES, 42)))

        seedMissingExerciseMuscles(exerciseDao, muscleDao)

        val rows = muscleDao.getForExercise(exerciseId)
        assertEquals(1, rows.size) // ручная разметка не перезатирается каталогом
        assertEquals(Muscle.CALVES, rows.single().muscle)
    }

    @Test
    fun `seeding maps every catalogue exercise`() = runTest {
        val ids = seedExercises.map { exerciseDao.insert(it) }

        seedMissingExerciseMuscles(exerciseDao, muscleDao)

        assertEquals(ids.size, muscleDao.getMappedExerciseIds().size)
        ids.forEach { id ->
            val rows = muscleDao.getForExercise(id)
            assertTrue("упражнение $id осталось без мышц", rows.isNotEmpty())
        }
    }

    @Test
    fun `catalogue uses one global load scale across exercises`() {
        val squatQuads = seedExerciseMuscles.getValue("приседания со штангой")
            .single { it.muscle == Muscle.QUADS }
            .contribution
        val treadmillQuads = seedExerciseMuscles.getValue("беговая дорожка")
            .single { it.muscle == Muscle.QUADS }
            .contribution

        assertEquals(100, squatQuads)
        assertEquals(20, treadmillQuads)
        assertTrue(treadmillQuads < squatQuads)
    }

    // endregion

    private suspend fun insertExercise(name: String, group: MuscleGroup): Long =
        exerciseDao.insert(
            ExerciseEntity(name = name, muscleGroup = group, type = ExerciseType.STRENGTH),
        )
}
