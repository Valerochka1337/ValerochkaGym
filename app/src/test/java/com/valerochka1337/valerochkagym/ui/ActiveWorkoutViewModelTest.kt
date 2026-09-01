package com.valerochka1337.valerochkagym.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.AnalyticsSetRow
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithCount
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.domain.CompleteSetUseCase
import com.valerochka1337.valerochkagym.domain.PreviousSetsUseCase
import com.valerochka1337.valerochkagym.domain.RestDurationResolver
import com.valerochka1337.valerochkagym.domain.RoutineGymConflictException
import com.valerochka1337.valerochkagym.domain.WorkoutSetMutator
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import com.valerochka1337.valerochkagym.service.RestTimerState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateConnectionState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateDevice
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateMonitor
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateReading
import com.valerochka1337.valerochkagym.ui.active.ActiveWorkoutEvent
import com.valerochka1337.valerochkagym.ui.active.ActiveWorkoutViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import com.valerochka1337.valerochkagym.worker.UploadScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [ActiveWorkoutViewModel]. The repository, DAO and scheduler boundaries are
 * hand-written fakes; [WorkoutSetMutator], [CompleteSetUseCase], [RestDurationResolver] and
 * [RestTimerEngine] are real instances over those fakes, so the tests cover the same single-writer
 * path the notification buttons use. The mutator consumes its channel on [TestScope.backgroundScope]
 * (standard dispatcher), so tests call [runCurrent] after a step to let the write land.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveWorkoutViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // region loading and previous summaries

    @Test
    fun `loading clears once the repository has emitted even without an active workout`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val harness = harness(active = null)
            collectUiState(harness.viewModel)

            assertFalse(harness.viewModel.uiState.value.loading)
            assertNull(harness.viewModel.uiState.value.workout)
        }

    @Test
    fun `active workout is exposed with previous set summaries per exercise`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val harness = harness(
                active = workoutFull(setId = 10L),
                previousSets = listOf(
                    completedSet(id = 1, weightKg = 100.0, reps = 5),
                    completedSet(id = 2, weightKg = 100.0, reps = 4),
                ),
            )
            collectUiState(harness.viewModel)

            val state = harness.viewModel.uiState.value
            assertEquals("w1", state.workout?.workout?.id)
            assertEquals("100×5, 100×4", state.previousByExercise[EXERCISE_ID])
        }

    // endregion

    // region set mutations

    @Test
    fun `stepping the weight goes through the single-writer mutator into the repository`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val harness = harness(active = workoutFull(setId = 10L, weightKg = 60.0))
            collectUiState(harness.viewModel)

            harness.viewModel.stepWeight(10L, +2.5)
            runCurrent()

            assertEquals(62.5, harness.repository.lastUpdatedSet?.weightKg)
        }

    @Test
    fun `completing a set marks it done and starts rest from the default settings`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val harness = harness(active = workoutFull(setId = 10L))
            collectUiState(harness.viewModel)

            harness.viewModel.completeSet(10L)
            runCurrent()

            assertEquals(listOf(10L to true), harness.repository.toggledSets)
            assertEquals(
                DEFAULT_REST_SECONDS,
                (harness.restTimerEngine.state.value as? RestTimerState.Timed)?.totalSec,
            )
        }

    @Test
    fun `completing a set starts heart rate rest with the configured threshold`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val harness = harness(active = workoutFull(setId = 10L), heartRateRestEnabled = true)
            collectUiState(harness.viewModel)

            harness.viewModel.completeSet(10L)
            runCurrent()

            assertEquals(
                RestTimerState.HeartRate(thresholdBpm = 110, holdSeconds = 10, startedAtMillis = 0),
                harness.restTimerEngine.state.value,
            )
        }

    @Test
    fun `uncompleting a set only clears the completion flag`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val harness = harness(active = workoutFull(setId = 10L))
            collectUiState(harness.viewModel)

            harness.viewModel.uncompleteSet(10L)

            assertEquals(listOf(10L to false), harness.repository.toggledSets)
            assertNull(harness.restTimerEngine.state.value)
        }

    @Test
    fun `rest pill actions extend and skip the running timer`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val harness = harness(active = workoutFull(setId = 10L))
            collectUiState(harness.viewModel)
            harness.viewModel.completeSet(10L)
            runCurrent()

            harness.viewModel.addRestSeconds(15)
            assertEquals(
                DEFAULT_REST_SECONDS + 15,
                (harness.viewModel.restTimer.value as? RestTimerState.Timed)?.totalSec,
            )

            harness.viewModel.skipRest()
            runCurrent()
            assertNull(harness.viewModel.restTimer.value)
        }

    @Test
    fun `heart rate actions delegate to the monitor without persisting a reading`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val harness = harness(active = workoutFull(setId = 10L))
            val device = HeartRateDevice(address = "AA:BB:CC:DD:EE:FF", name = "Band 10", rssi = -50)

            harness.viewModel.scanHeartRate()
            harness.viewModel.connectHeartRate(device)
            harness.heartRateMonitor.emit(128)

            assertEquals(1, harness.heartRateMonitor.scanCalls)
            assertEquals(device, harness.heartRateMonitor.connectedTo)
            assertEquals(128, harness.viewModel.heartRateReading.value?.bpm)
        }

    // endregion

    // region structure edits

    @Test
    fun `structure edits are forwarded to the repository`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val harness = harness(active = workoutFull(setId = 10L))
            collectUiState(harness.viewModel)

            harness.viewModel.addSet(WORKOUT_EXERCISE_ID)
            harness.viewModel.deleteSet(10L)
            harness.viewModel.deleteExercise(WORKOUT_EXERCISE_ID)
            harness.viewModel.addExerciseById(7L)

            assertEquals(listOf(WORKOUT_EXERCISE_ID), harness.repository.addedSetTo)
            assertEquals(listOf(10L), harness.repository.deletedSets)
            assertEquals(listOf(WORKOUT_EXERCISE_ID), harness.repository.deletedExercises)
            assertEquals(listOf("w1" to 7L), harness.repository.addedExercises)
        }

    @Test
    fun `reordering exercises forwards the final order for the active workout`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val harness = harness(active = workoutFull(setId = 10L))
            collectUiState(harness.viewModel)

            harness.viewModel.reorderExercises(listOf(30L, WORKOUT_EXERCISE_ID, 10L))

            assertEquals(
                listOf("w1" to listOf(30L, WORKOUT_EXERCISE_ID, 10L)),
                harness.repository.reorderedExercises,
            )
        }

    @Test
    fun `adding an exercise without an active workout is a no-op`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val harness = harness(active = null)
            collectUiState(harness.viewModel)

            harness.viewModel.addExerciseById(7L)

            assertTrue(harness.repository.addedExercises.isEmpty())
        }

    @Test
    fun `an unavailable picker result shows the conflicting exercise`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val harness = harness(active = workoutFull(setId = 10L))
            collectUiState(harness.viewModel)
            val events = collectEvents(harness.viewModel)
            harness.repository.addExerciseFailure = RoutineGymConflictException(listOf("Тяга"))

            harness.viewModel.addExerciseById(7L)

            assertEquals(
                listOf(
                    ActiveWorkoutEvent.ShowMessage(
                        "Упражнение недоступно во всех выбранных залах: Тяга",
                    ),
                ),
                events,
            )
        }

    @Test
    fun `reordering exercises without an active workout is a no-op`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val harness = harness(active = null)
            collectUiState(harness.viewModel)

            harness.viewModel.reorderExercises(listOf(WORKOUT_EXERCISE_ID))

            assertTrue(harness.repository.reorderedExercises.isEmpty())
        }

    // endregion

    // region finish and discard

    @Test
    fun `finish persists schedules the upload and navigates to the summary`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val harness = harness(active = workoutFull(setId = 10L))
            val events = collectEvents(harness.viewModel)
            collectUiState(harness.viewModel)

            harness.viewModel.finish()

            assertEquals(listOf("w1"), harness.repository.finishedIds)
            assertEquals(listOf("w1"), harness.uploadScheduler.scheduledIds)
            assertEquals(listOf<ActiveWorkoutEvent>(ActiveWorkoutEvent.NavigateToSummary("w1")), events)
        }

    @Test
    fun `discard drops the workout and navigates home without scheduling an upload`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val harness = harness(active = workoutFull(setId = 10L))
            val events = collectEvents(harness.viewModel)
            collectUiState(harness.viewModel)

            harness.viewModel.discard()

            assertEquals(listOf("w1"), harness.repository.discardedIds)
            assertTrue(harness.uploadScheduler.scheduledIds.isEmpty())
            assertEquals(listOf<ActiveWorkoutEvent>(ActiveWorkoutEvent.NavigateHome), events)
        }

    @Test
    fun `finish without an active workout does nothing`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val harness = harness(active = null)
            val events = collectEvents(harness.viewModel)
            collectUiState(harness.viewModel)

            harness.viewModel.finish()

            assertTrue(harness.repository.finishedIds.isEmpty())
            assertTrue(events.isEmpty())
        }

    // endregion

    // region harness

    private class Harness(
        val viewModel: ActiveWorkoutViewModel,
        val repository: FakeActiveWorkoutRepository,
        val uploadScheduler: FakeUploadScheduler,
        val restTimerEngine: RestTimerEngine,
        val heartRateMonitor: FakeHeartRateMonitor,
    )

    private fun TestScope.harness(
        active: WorkoutFull?,
        previousSets: List<WorkoutSetEntity> = emptyList(),
        heartRateRestEnabled: Boolean = false,
    ): Harness {
        val repository = FakeActiveWorkoutRepository(active)
        val uploadScheduler = FakeUploadScheduler()
        val restTimerEngine = RestTimerEngine(backgroundScope) { testScheduler.currentTime }
        val settingsRepository = SettingsRepository(
            FakeDataStore(
                mutablePreferencesOf(
                    intPreferencesKey("default_rest_seconds") to DEFAULT_REST_SECONDS,
                    androidx.datastore.preferences.core.booleanPreferencesKey("heart_rate_rest_enabled") to heartRateRestEnabled,
                ),
            ),
        )
        val heartRateMonitor = FakeHeartRateMonitor()
        val viewModel = ActiveWorkoutViewModel(
            repository = repository,
            previousSetsUseCase = PreviousSetsUseCase(FakeWorkoutDao(previousSets)),
            setMutator = WorkoutSetMutator(repository, backgroundScope),
            completeSetUseCase = CompleteSetUseCase(
                repository = repository,
                restDurationResolver = RestDurationResolver(FakeRoutineDao(), settingsRepository),
                restTimerEngine = restTimerEngine,
                settingsRepository = settingsRepository,
            ),
            restTimerEngine = restTimerEngine,
            uploadScheduler = uploadScheduler,
            heartRateMonitor = heartRateMonitor,
        )
        return Harness(viewModel, repository, uploadScheduler, restTimerEngine, heartRateMonitor)
    }

    private fun TestScope.collectUiState(viewModel: ActiveWorkoutViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
    }

    private fun TestScope.collectEvents(viewModel: ActiveWorkoutViewModel): List<ActiveWorkoutEvent> {
        val events = mutableListOf<ActiveWorkoutEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events += it }
        }
        return events
    }

    // endregion

    // region test data

    private fun workoutFull(setId: Long, weightKg: Double = 60.0): WorkoutFull = WorkoutFull(
        workout = WorkoutEntity(id = "w1", name = "Тренировка", startedAt = 0L),
        exercises = listOf(
            WorkoutExerciseWithSets(
                workoutExercise = WorkoutExerciseEntity(
                    id = WORKOUT_EXERCISE_ID,
                    workoutId = "w1",
                    exerciseId = EXERCISE_ID,
                    position = 0,
                ),
                exercise = ExerciseEntity(
                    id = EXERCISE_ID,
                    name = "Жим лёжа",
                    muscleGroup = MuscleGroup.CHEST,
                    type = ExerciseType.STRENGTH,
                ),
                sets = listOf(
                    WorkoutSetEntity(
                        id = setId,
                        workoutExerciseId = WORKOUT_EXERCISE_ID,
                        setIndex = 0,
                        weightKg = weightKg,
                        reps = 10,
                    ),
                ),
            ),
        ),
    )

    private fun completedSet(id: Long, weightKg: Double, reps: Int): WorkoutSetEntity =
        WorkoutSetEntity(
            id = id,
            workoutExerciseId = 99L,
            setIndex = id.toInt(),
            weightKg = weightKg,
            reps = reps,
            isCompleted = true,
        )

    // endregion

    // region fakes

    /** In-memory [ActiveWorkoutRepository]: держит дерево тренировки и записывает каждую операцию. */
    private class FakeActiveWorkoutRepository(initial: WorkoutFull?) : ActiveWorkoutRepository {

        private val active = MutableStateFlow(initial)

        var lastUpdatedSet: WorkoutSetEntity? = null
            private set
        val toggledSets = mutableListOf<Pair<Long, Boolean>>()
        val addedSetTo = mutableListOf<Long>()
        val deletedSets = mutableListOf<Long>()
        val addedExercises = mutableListOf<Pair<String, Long>>()
        val deletedExercises = mutableListOf<Long>()
        val reorderedExercises = mutableListOf<Pair<String, List<Long>>>()
        val finishedIds = mutableListOf<String>()
        val discardedIds = mutableListOf<String>()
        var addExerciseFailure: Exception? = null

        override fun observeActive(): Flow<WorkoutFull?> = active

        override suspend fun getSet(setId: Long): WorkoutSetEntity? =
            active.value?.exercises?.flatMap { it.sets }?.firstOrNull { it.id == setId }

        override suspend fun updateSet(set: WorkoutSetEntity) {
            lastUpdatedSet = set
            active.value = active.value?.let { current ->
                current.copy(
                    exercises = current.exercises.map { exercise ->
                        exercise.copy(sets = exercise.sets.map { if (it.id == set.id) set else it })
                    },
                )
            }
        }

        override suspend fun toggleSetCompleted(setId: Long, completed: Boolean) {
            toggledSets += setId to completed
        }

        override suspend fun addSet(workoutExerciseId: Long) {
            addedSetTo += workoutExerciseId
        }

        override suspend fun deleteSet(setId: Long) {
            deletedSets += setId
        }

        override suspend fun addExercise(workoutId: String, exerciseId: Long): Long {
            addExerciseFailure?.let { throw it }
            addedExercises += workoutId to exerciseId
            return 0
        }

        override suspend fun deleteExercise(workoutExerciseId: Long) {
            deletedExercises += workoutExerciseId
        }

        override suspend fun reorderExercises(workoutId: String, orderedWorkoutExerciseIds: List<Long>) {
            reorderedExercises += workoutId to orderedWorkoutExerciseIds
        }

        override suspend fun finish(workoutId: String) {
            finishedIds += workoutId
            active.value = null
        }

        override suspend fun discard(workoutId: String) {
            discardedIds += workoutId
            active.value = null
        }

        override suspend fun startFromRoutine(routineId: Long): String = "w1"
        override suspend fun startEmpty(): String = "w1"
    }

    /** [WorkoutDao] для [PreviousSetsUseCase]: отдаёт заданные «прошлые» подходы, остальное — заглушки. */
    private class FakeWorkoutDao(private val previousSets: List<WorkoutSetEntity>) : WorkoutDao {
        override suspend fun lastCompletedSetsForExercise(exerciseId: Long): List<WorkoutSetEntity> = previousSets
        override suspend fun insertWorkout(workout: WorkoutEntity) = Unit
        override suspend fun insertWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long = 0
        override suspend fun insertSet(set: WorkoutSetEntity): Long = 0
        override suspend fun insertSets(sets: List<WorkoutSetEntity>): List<Long> = emptyList()
        override suspend fun updateSet(set: WorkoutSetEntity) = Unit
        override suspend fun updateWorkoutExercises(exercises: List<WorkoutExerciseEntity>) = Unit
        override suspend fun setSetCompleted(setId: Long, completed: Boolean, completedAt: Long?) = Unit
        override suspend fun getSet(setId: Long): WorkoutSetEntity? = null
        override suspend fun getSetsForWorkoutExercise(workoutExerciseId: Long): List<WorkoutSetEntity> = emptyList()
        override suspend fun getWorkoutExercises(workoutId: String): List<WorkoutExerciseEntity> = emptyList()
        override suspend fun setFinishedAt(id: String, finishedAt: Long) = Unit
        override fun observeActiveWorkout(): Flow<WorkoutFull?> = flowOf(null)
        override suspend fun getActiveWorkoutId(): String? = null
        override fun observeFinishedWorkouts(): Flow<List<WorkoutEntity>> = flowOf(emptyList())
        override fun observeCompletedSets(): Flow<List<AnalyticsSetRow>> = flowOf(emptyList())
        override suspend fun getWorkoutFull(id: String): WorkoutFull? = null
        override suspend fun maxCompletedWeight(exerciseId: Long, excludeWorkoutId: String): Double? = null
        override suspend fun setUploadStatus(workoutId: String, status: UploadStatus, error: String?) = Unit
        override fun observeWorkout(id: String): Flow<WorkoutEntity?> = flowOf(null)
        override suspend fun getFinishedNotUploaded(): List<String> = emptyList()
        override suspend fun getExistingWorkoutIds(): List<String> = emptyList()
        override suspend fun deleteWorkout(id: String) = Unit
        override suspend fun deleteSet(id: Long) = Unit
        override suspend fun deleteWorkoutExercise(id: Long) = Unit
    }

    /** [RoutineDao]-заглушка: у тестовой тренировки нет программы, отдых берётся из настроек. */
    private class FakeRoutineDao : RoutineDao {
        override fun observeRoutinesWithCount(): Flow<List<RoutineWithCount>> = flowOf(emptyList())
        override fun observeRoutinesFull(): Flow<List<RoutineWithExercises>> = flowOf(emptyList())
        override suspend fun getRoutineWithExercises(id: Long): RoutineWithExercises? = null
        override suspend fun getRoutineName(id: Long): String? = null
        override suspend fun upsertRoutine(routine: RoutineEntity): Long = 0
        override suspend fun deleteRoutine(id: Long) = Unit
        override suspend fun insertRoutineExercises(routineExercises: List<RoutineExerciseEntity>): List<Long> = emptyList()
        override suspend fun deleteRoutineExercises(routineId: Long) = Unit
    }

    private class FakeUploadScheduler : UploadScheduler {
        val scheduledIds = mutableListOf<String>()
        override fun schedule(workoutId: String) {
            scheduledIds += workoutId
        }
        override suspend fun retry(workoutId: String) = Unit
        override suspend fun scheduleAllPending(): Int = 0
    }

    private class FakeHeartRateMonitor : HeartRateMonitor {
        private val mutableState = MutableStateFlow<HeartRateConnectionState>(
            HeartRateConnectionState.Idle,
        )
        private val mutableReading = MutableStateFlow<HeartRateReading?>(null)

        override val state: StateFlow<HeartRateConnectionState> = mutableState
        override val reading: StateFlow<HeartRateReading?> = mutableReading

        var scanCalls = 0
        var connectedTo: HeartRateDevice? = null

        override fun scan() {
            scanCalls += 1
            mutableState.value = HeartRateConnectionState.Searching
        }

        override fun connect(device: HeartRateDevice) {
            connectedTo = device
            mutableState.value = HeartRateConnectionState.Connected(device)
        }

        override fun stop() {
            mutableReading.value = null
            mutableState.value = HeartRateConnectionState.Idle
        }

        override fun reportError(message: String) {
            mutableReading.value = null
            mutableState.value = HeartRateConnectionState.Error(message)
        }

        fun emit(bpm: Int) {
            val reading = HeartRateReading(bpm = bpm, updatedAtMillis = 1L)
            mutableReading.value = reading
            val device = connectedTo ?: return
            mutableState.value = HeartRateConnectionState.Live(device, reading)
        }
    }

    /** Minimal in-memory [DataStore] so a real [SettingsRepository] can read the default rest. */
    private class FakeDataStore(prefs: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(prefs)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            state.value = transform(state.value)
            return state.value
        }
    }

    // endregion

    private companion object {
        const val EXERCISE_ID = 5L
        const val WORKOUT_EXERCISE_ID = 20L
        const val DEFAULT_REST_SECONDS = 90
    }
}
