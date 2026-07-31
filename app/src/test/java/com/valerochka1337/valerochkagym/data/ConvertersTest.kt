package com.valerochka1337.valerochkagym.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ConvertersTest {

    private lateinit var db: GymDatabase
    private lateinit var routineDao: RoutineDao
    private lateinit var exerciseDao: ExerciseDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        routineDao = db.routineDao()
        exerciseDao = db.exerciseDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `plannedSets survive a round trip through the database`() = runTest {
        val routineId = routineDao.upsertRoutine(RoutineEntity(name = "План"))
        val exerciseId = addExercise()
        val plannedSets = listOf(
            PlannedSet(weightKg = 60.0, reps = 8),
            PlannedSet(reps = 12),
            PlannedSet(durationSec = 45, speedKmh = 8.5, inclinePct = 2.0),
            PlannedSet(),
        )

        routineDao.insertRoutineExercise(
            RoutineExerciseEntity(
                routineId = routineId,
                exerciseId = exerciseId,
                position = 0,
                plannedSets = plannedSets,
            ),
        )

        val stored = routineDao.getRoutineWithExercises(routineId)!!.exercises.single()
        assertEquals(plannedSets, stored.routineExercise.plannedSets)
    }

    @Test
    fun `an empty plannedSets list round trips as empty`() = runTest {
        val routineId = routineDao.upsertRoutine(RoutineEntity(name = "Пусто"))
        val exerciseId = addExercise()

        routineDao.insertRoutineExercise(
            RoutineExerciseEntity(
                routineId = routineId,
                exerciseId = exerciseId,
                position = 0,
                plannedSets = emptyList(),
            ),
        )

        val stored = routineDao.getRoutineWithExercises(routineId)!!.exercises.single()
        assertEquals(emptyList<PlannedSet>(), stored.routineExercise.plannedSets)
    }

    private suspend fun addExercise(name: String = "Жим ногами"): Long =
        exerciseDao.insert(
            ExerciseEntity(
                name = name,
                muscleGroup = MuscleGroup.LEGS,
                type = ExerciseType.STRENGTH,
            ),
        )
}
