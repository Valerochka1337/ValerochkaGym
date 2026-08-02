package com.valerochka1337.valerochkagym.domain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.valerochka1337.valerochkagym.data.ActiveWorkoutRepositoryImpl
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [CompleteSetUseCase] — общий путь галочки на экране и кнопки «Готово» в
 * уведомлении. Работает поверх настоящей in-memory базы (см. [RoomDaoTest]) и настоящего
 * [RestTimerEngine] над [kotlinx.coroutines.test.TestScope], так что проверяется вся цепочка:
 * отметка в БД → длительность отдыха → запущенный таймер.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompleteSetUseCaseTest : RoomDaoTest() {

    private lateinit var repository: ActiveWorkoutRepositoryImpl
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        repository = ActiveWorkoutRepositoryImpl(db, db.workoutDao(), db.routineDao())
    }

    @Test
    fun `completing a set marks it done and starts rest from settings`() = runTest {
        val setId = seedActiveWorkout()
        settingsRepository = settingsWithRest(90)
        val engine = RestTimerEngine(backgroundScope) { 0L }
        val useCase = CompleteSetUseCase(repository, restDurationResolver(), engine, settingsRepository)

        useCase(setId)

        assertTrue(repository.getSet(setId)!!.isCompleted)
        assertEquals(90, engine.state.value?.totalSec)
        assertEquals(90, engine.state.value?.remainingSec)
        // Дедлайн по стенным часам — его же уведомление отдаёт системе под обратный хронометр.
        assertEquals(90_000L, engine.state.value?.endsAtMillis)
    }

    @Test
    fun `completing a set that is not in the active workout leaves the timer alone`() = runTest {
        seedActiveWorkout()
        // Подход из уже завершённой тренировки: отметку поставить можно, отдых начинать нечему.
        val staleSetId = seedFinishedWorkoutSet()
        settingsRepository = settingsWithRest(90)
        val engine = RestTimerEngine(backgroundScope) { 0L }
        val useCase = CompleteSetUseCase(repository, restDurationResolver(), engine, settingsRepository)

        useCase(staleSetId)

        assertTrue(repository.getSet(staleSetId)!!.isCompleted)
        assertNull(engine.state.value)
    }

    @Test
    fun `with autostart disabled the set is marked but rest does not start`() = runTest {
        val setId = seedActiveWorkout()
        settingsRepository = SettingsRepository(
            FakeDataStore(
                mutablePreferencesOf(
                    intPreferencesKey("default_rest_seconds") to 90,
                    booleanPreferencesKey("rest_autostart") to false,
                ),
            ),
        )
        val engine = RestTimerEngine(backgroundScope) { 0L }
        val useCase = CompleteSetUseCase(repository, restDurationResolver(), engine, settingsRepository)

        useCase(setId)

        assertTrue(repository.getSet(setId)!!.isCompleted)
        assertNull(engine.state.value)
    }

    private fun restDurationResolver() = RestDurationResolver(db.routineDao(), settingsRepository)

    /** Активная тренировка с одним силовым упражнением и одним подходом; возвращает id подхода. */
    private suspend fun seedActiveWorkout(): Long {
        val exerciseId = db.exerciseDao().insert(
            ExerciseEntity(
                name = "Жим лёжа",
                muscleGroup = MuscleGroup.CHEST,
                type = ExerciseType.STRENGTH,
            ),
        )
        insertWorkout("active", startedAt = 1_000, finishedAt = null)
        val workoutExerciseId = insertWorkoutExercise("active", exerciseId)
        return insertSet(workoutExerciseId, setIndex = 0, weightKg = 60.0, reps = 10)
    }

    private suspend fun seedFinishedWorkoutSet(): Long {
        val exerciseId = db.exerciseDao().insert(
            ExerciseEntity(
                name = "Тяга",
                muscleGroup = MuscleGroup.BACK,
                type = ExerciseType.STRENGTH,
            ),
        )
        insertWorkout("done", startedAt = 1, finishedAt = 2)
        val workoutExerciseId = insertWorkoutExercise("done", exerciseId)
        return insertSet(workoutExerciseId, setIndex = 0, weightKg = 40.0, reps = 12)
    }

    private fun settingsWithRest(seconds: Int) = SettingsRepository(
        FakeDataStore(mutablePreferencesOf(intPreferencesKey("default_rest_seconds") to seconds)),
    )

    private class FakeDataStore(prefs: Preferences = emptyPreferences()) : DataStore<Preferences> {

        private val state = MutableStateFlow(prefs)

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            state.value = transform(state.value)
            return state.value
        }
    }
}
