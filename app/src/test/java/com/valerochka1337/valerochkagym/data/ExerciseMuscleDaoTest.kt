package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.defaultMuscleLoads
import com.valerochka1337.valerochkagym.data.db.CanonicalExerciseRegistry
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.seedExerciseMuscles
import com.valerochka1337.valerochkagym.data.db.seedExercises
import com.valerochka1337.valerochkagym.data.db.seedMissingExerciseMuscles
import com.valerochka1337.valerochkagym.data.db.reconcileCanonicalExerciseCatalog
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
import com.valerochka1337.valerochkagym.data.db.entity.GymExerciseEntity
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
    fun `explicit stabilizer and multiple primaries round trip without becoming absence`() = runTest {
        val exerciseId = insertExercise("Сложное", MuscleGroup.FULL_BODY)
        muscleDao.replaceForExercise(exerciseId, listOf(
            ExerciseMuscleEntity(exerciseId, Muscle.UPPER_CHEST, 100),
            ExerciseMuscleEntity(exerciseId, Muscle.LOWER_CHEST, 100),
            ExerciseMuscleEntity(exerciseId, Muscle.LOWER_BACK, 0),
        ))

        assertEquals(
            mapOf(Muscle.UPPER_CHEST to 100, Muscle.LOWER_CHEST to 100, Muscle.LOWER_BACK to 0),
            muscleDao.getForExercise(exerciseId).associate { it.muscle to it.contribution },
        )
    }

    @Test
    fun `registry reconcile preserves legacy id links and leaves custom maps authoritative`() = runTest {
        val legacy = ExerciseEntity(name = "Жим штанги лёжа", muscleGroup = MuscleGroup.CHEST, type = ExerciseType.STRENGTH)
        val legacyId = exerciseDao.insert(legacy)
        val customId = exerciseDao.insert(ExerciseEntity(name = "Личное", muscleGroup = MuscleGroup.CORE, type = ExerciseType.STRENGTH, isCustom = true))
        muscleDao.upsertAll(listOf(ExerciseMuscleEntity(customId, Muscle.LOWER_BACK, 0)))
        val emptyCustomId = exerciseDao.insert(ExerciseEntity(name = "Без карты", muscleGroup = MuscleGroup.CORE, type = ExerciseType.STRENGTH, isCustom = true))
        val gymId = db.gymDao().insertGym(GymEntity(name = "Зал"))
        db.gymDao().insertGymExercises(listOf(GymExerciseEntity(gymId, legacyId)))
        insertWorkout("history", startedAt = 1, finishedAt = 2)
        insertWorkoutExercise("history", legacyId)

        reconcileCanonicalExerciseCatalog(db)
        reconcileCanonicalExerciseCatalog(db)

        assertEquals(legacyId, exerciseDao.getAllOnce().single { it.name == "Жим штанги лёжа" }.id)
        assertEquals(listOf(legacyId), db.gymDao().getGymExerciseIds(gymId))
        assertEquals(legacyId, workoutFull("history").exercises.single().exercise.id)
        assertEquals(0, muscleDao.getForExercise(emptyCustomId).size)
        assertEquals(listOf(0), muscleDao.getForExercise(customId).map { it.contribution })
        assertEquals(CanonicalExerciseRegistry.entries.size + 2, exerciseDao.count())
    }

    @Test
    fun `reconcile leaves custom exercise with built in display name untouched`() = runTest {
        val id = exerciseDao.insert(
            ExerciseEntity(
                name = "Жим штанги лёжа",
                muscleGroup = MuscleGroup.CHEST,
                type = ExerciseType.STRENGTH,
                isCustom = true,
            ),
        )
        muscleDao.upsertAll(listOf(ExerciseMuscleEntity(id, Muscle.LOWER_BACK, 0)))

        reconcileCanonicalExerciseCatalog(db)

        assertEquals(listOf(Muscle.LOWER_BACK to 0), muscleDao.getForExercise(id).map { it.muscle to it.contribution })
        assertTrue(exerciseDao.getById(id)!!.isCustom)
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

        val expected = requireNotNull(CanonicalExerciseRegistry.loadsFor(
            ExerciseEntity(id = exerciseId, name = "Жим штанги лёжа", muscleGroup = MuscleGroup.CHEST, type = ExerciseType.STRENGTH),
        )).associate { it.muscle to it.contribution }
        val actual = muscleDao.getForExercise(exerciseId).associate { it.muscle to it.contribution }
        assertEquals(expected, actual)
    }

    @Test
    fun `seeding leaves an unmapped custom exercise absent`() = runTest {
        val exerciseId = insertExercise("Жим одной левой", MuscleGroup.CHEST)

        seedMissingExerciseMuscles(exerciseDao, muscleDao)

        val actual = muscleDao.getForExercise(exerciseId).associate { it.muscle to it.contribution }
        assertEquals(emptyMap<Muscle, Int>(), actual)
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
    fun `catalogue uses canonical role loads across exercises`() {
        val squatQuads = seedExerciseMuscles.getValue("приседания со штангой")
            .single { it.muscle == Muscle.QUADS }
            .contribution
        val treadmillQuads = seedExerciseMuscles.getValue("беговая дорожка")
            .single { it.muscle == Muscle.QUADS }
            .contribution

        assertEquals(100, squatQuads)
        assertEquals(50, treadmillQuads)
        assertTrue(treadmillQuads < squatQuads)
    }

    // endregion

    private suspend fun insertExercise(name: String, group: MuscleGroup): Long =
        exerciseDao.insert(
            ExerciseEntity(name = name, muscleGroup = group, type = ExerciseType.STRENGTH),
        )
}
