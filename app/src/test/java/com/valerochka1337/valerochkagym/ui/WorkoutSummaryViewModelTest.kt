package com.valerochka1337.valerochkagym.ui

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.db.PlannedSet
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
import com.valerochka1337.valerochkagym.data.db.relation.RoutineExerciseWithExercise
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithCount
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.NoOpGymRepository
import com.valerochka1337.valerochkagym.domain.PreviousSetsUseCase
import com.valerochka1337.valerochkagym.domain.RoutineConfigurationDraft
import com.valerochka1337.valerochkagym.domain.RoutineUpdateUseCase
import com.valerochka1337.valerochkagym.domain.SaveCompletedWorkoutAsRoutineUseCase
import com.valerochka1337.valerochkagym.domain.SaveRoutineConfigurationResult
import com.valerochka1337.valerochkagym.domain.WorkoutStatsUseCase
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.ui.summary.WorkoutSummaryViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import com.valerochka1337.valerochkagym.worker.NoOpRoutineUploadScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [WorkoutSummaryViewModel]: сортировка дерева, сводка по выполненным подходам и
 * разовое предложение «обновить программу».
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutSummaryViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun `summary shows duration volume and completed sets in domain order`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = viewModel(fullWorkout())

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals("Грудь", state.workoutName)
        assertEquals(45L * 60, state.durationSeconds)
        // Только выполненные силовые подходы: 80×8 + 80×10 + 20×12.
        assertEquals(1_680.0, state.volumeKg, 1e-9)
        // Упражнения отсортированы по позиции; в сводке только выполненные подходы по setIndex.
        assertEquals(listOf("Жим лёжа", "Разводка"), state.exercises.map { it.name })
        assertEquals("80×8, 80×10", state.exercises.first().setsSummary)
      }

  @Test
  fun `a missing workout only clears the loading flag`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = viewModel(full = null)

        assertFalse(viewModel.uiState.value.loading)
        assertTrue(viewModel.uiState.value.exercises.isEmpty())
      }

  @Test
  fun `unfinished summary does not expose saving as a program`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val complete = fullWorkout()
        val viewModel = viewModel(complete.copy(workout = complete.workout.copy(finishedAt = null)))

        assertFalse(viewModel.uiState.value.canSaveAsProgram)
        viewModel.openSaveAsProgram()
        assertFalse(viewModel.uiState.value.showSaveAsProgramDialog)
      }

  @Test
  fun `finished summary without completed sets does not expose saving as a program`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val complete = fullWorkout()
        val noCompletedSets =
            complete.copy(
                exercises =
                    complete.exercises.map { section ->
                      section.copy(sets = section.sets.map { it.copy(isCompleted = false) })
                    },
            )
        val viewModel = viewModel(noCompletedSets)

        assertFalse(viewModel.uiState.value.canSaveAsProgram)
      }

  @Test
  fun `the update-routine dialog appears when the workout diverged from its routine`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        // Программа без упражнений, тренировка с выполненным подходом — расхождение.
        val viewModel =
            viewModel(
                fullWorkout(routineId = 7L),
                routineDao = FakeRoutineDao(routineWithExercises(7L)),
            )

        assertTrue(viewModel.uiState.value.showUpdateRoutineDialog)
      }

  @Test
  fun `dismissing the dialog hides it without touching the routine`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val routineDao = FakeRoutineDao(routineWithExercises(7L))
        val viewModel = viewModel(fullWorkout(routineId = 7L), routineDao = routineDao)

        viewModel.dismissRoutineUpdate()

        assertFalse(viewModel.uiState.value.showUpdateRoutineDialog)
        assertTrue(routineDao.replacedExercises.isEmpty())
      }

  @Test
  fun `applying the update rewrites the routine with the performed sets`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val routineDao = FakeRoutineDao(routineWithExercises(7L))
        val viewModel = viewModel(fullWorkout(routineId = 7L), routineDao = routineDao)

        viewModel.applyRoutineUpdate()

        assertFalse(viewModel.uiState.value.showUpdateRoutineDialog)
        val replaced = routineDao.replacedExercises
        // Оба упражнения с выполненными подходами, по позиции; plannedSets — фактические.
        assertEquals(listOf(1L, 2L), replaced.map { it.exerciseId })
        assertEquals(
            listOf(PlannedSet(weightKg = 80.0, reps = 8), PlannedSet(weightKg = 80.0, reps = 10)),
            replaced.first().plannedSets,
        )
      }

  @Test
  fun `eligible summary pre-fills save dialog and acknowledges one saved program`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = FakeSaveGymRepository()
        val viewModel =
            viewModel(
                fullWorkout(),
                saveUseCase =
                    SaveCompletedWorkoutAsRoutineUseCase(repository, NoOpRoutineUploadScheduler),
            )
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.saveEvents.collect { events += it }
        }

        assertTrue(viewModel.uiState.value.canSaveAsProgram)
        viewModel.openSaveAsProgram()
        assertTrue(viewModel.uiState.value.showSaveAsProgramDialog)
        assertEquals("Грудь", viewModel.uiState.value.saveAsProgramName)

        viewModel.changeSaveAsProgramName("  Новая грудь  ")
        viewModel.confirmSaveAsProgram()

        assertEquals("Новая грудь", repository.drafts.single().routine.name)
        assertFalse(viewModel.uiState.value.showSaveAsProgramDialog)
        assertEquals(listOf(Unit), events)
      }

  @Test
  fun `summary keeps dialog open with a typed save error and does not write after cancel`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = FakeSaveGymRepository(SaveRoutineConfigurationResult.Failure)
        val viewModel =
            viewModel(
                fullWorkout(),
                saveUseCase =
                    SaveCompletedWorkoutAsRoutineUseCase(repository, NoOpRoutineUploadScheduler),
            )

        viewModel.openSaveAsProgram()
        viewModel.confirmSaveAsProgram()

        assertTrue(viewModel.uiState.value.showSaveAsProgramDialog)
        assertEquals(
            "Не удалось сохранить программу. Попробуйте ещё раз.",
            viewModel.uiState.value.saveAsProgramError,
        )
        viewModel.dismissSaveAsProgram()
        assertFalse(viewModel.uiState.value.showSaveAsProgramDialog)
        assertEquals(1, repository.drafts.size)
      }

  @Test
  fun `concurrent confirmation writes only once while the save is in progress`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = BlockingSaveGymRepository()
        val viewModel =
            viewModel(
                fullWorkout(),
                saveUseCase =
                    SaveCompletedWorkoutAsRoutineUseCase(repository, NoOpRoutineUploadScheduler),
            )

        viewModel.openSaveAsProgram()
        val start = CompletableDeferred<Unit>()
        listOf(
                async(Dispatchers.Default) {
                  start.await()
                  viewModel.confirmSaveAsProgram()
                },
                async(Dispatchers.Default) {
                  start.await()
                  viewModel.confirmSaveAsProgram()
                },
            )
            .also { start.complete(Unit) }
            .awaitAll()

        assertTrue(viewModel.uiState.value.isSavingAsProgram)
        assertEquals(1, repository.drafts.size)
        repository.release.complete(Unit)
      }

  @Test
  fun `recreated summary restores an in-progress draft as editable and dismissible`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val handle = SavedStateHandle(mapOf(GymRoutes.WORKOUT_ID_ARG to "w1"))
        val blockingRepository = BlockingSaveGymRepository()
        val first =
            viewModel(
                fullWorkout(),
                saveUseCase =
                    SaveCompletedWorkoutAsRoutineUseCase(
                        blockingRepository,
                        NoOpRoutineUploadScheduler,
                    ),
                savedStateHandle = handle,
            )
        first.openSaveAsProgram()
        first.changeSaveAsProgramName("Мой снимок")
        first.confirmSaveAsProgram()
        assertTrue(first.uiState.value.isSavingAsProgram)

        val recreated = viewModel(fullWorkout(), savedStateHandle = handle)

        assertTrue(recreated.uiState.value.showSaveAsProgramDialog)
        assertEquals("Мой снимок", recreated.uiState.value.saveAsProgramName)
        assertFalse(recreated.uiState.value.isSavingAsProgram)
        recreated.changeSaveAsProgramName("Повтор")
        recreated.dismissSaveAsProgram()
        assertFalse(recreated.uiState.value.showSaveAsProgramDialog)
      }

  @Test
  fun `recreated summary replays a committed operation once and a new dialog gets a new operation`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val handle = SavedStateHandle(mapOf(GymRoutes.WORKOUT_ID_ARG to "w1"))
        val repository = CommitThenSuspendSaveGymRepository()
        val first =
            viewModel(
                fullWorkout(),
                saveUseCase =
                    SaveCompletedWorkoutAsRoutineUseCase(repository, NoOpRoutineUploadScheduler),
                savedStateHandle = handle,
            )
        first.openSaveAsProgram()
        val operationId = handle.get<String>("save_as_program_operation_sync_id")!!
        first.confirmSaveAsProgram()
        repository.firstCommit.await()

        val recreated =
            viewModel(
                fullWorkout(),
                saveUseCase =
                    SaveCompletedWorkoutAsRoutineUseCase(repository, NoOpRoutineUploadScheduler),
                savedStateHandle = handle,
            )
        recreated.confirmSaveAsProgram()

        assertEquals(1, repository.durableRoutines.size)
        assertEquals(operationId, repository.durableRoutines.values.single().syncId)
        assertFalse(recreated.uiState.value.showSaveAsProgramDialog)

        recreated.openSaveAsProgram()
        val freshOperationId = handle.get<String>("save_as_program_operation_sync_id")!!
        recreated.confirmSaveAsProgram()
        assertTrue(freshOperationId != operationId)
        assertEquals(2, repository.durableRoutines.size)
        repository.release.complete(Unit)
      }

  @Test
  fun `orphaned summary draft closes without writing and a later open creates an operation`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val handle =
            SavedStateHandle(
                mapOf(
                    GymRoutes.WORKOUT_ID_ARG to "w1",
                    "save_as_program_dialog_visible" to true,
                    "save_as_program_name" to "Старый черновик",
                ),
            )
        val repository = FakeSaveGymRepository()
        val viewModel =
            viewModel(
                fullWorkout(),
                saveUseCase =
                    SaveCompletedWorkoutAsRoutineUseCase(repository, NoOpRoutineUploadScheduler),
                savedStateHandle = handle,
            )

        assertFalse(viewModel.uiState.value.showSaveAsProgramDialog)
        viewModel.confirmSaveAsProgram()
        assertTrue(repository.drafts.isEmpty())

        viewModel.openSaveAsProgram()
        assertTrue(handle.get<String>("save_as_program_operation_sync_id")?.isNotBlank() == true)
        viewModel.confirmSaveAsProgram()
        assertEquals(1, repository.durableRoutines.size)
      }

  private fun viewModel(
      full: WorkoutFull?,
      routineDao: RoutineDao = FakeRoutineDao(null),
      saveUseCase: SaveCompletedWorkoutAsRoutineUseCase =
          SaveCompletedWorkoutAsRoutineUseCase(
              NoOpGymRepository,
              NoOpRoutineUploadScheduler,
          ),
      savedStateHandle: SavedStateHandle? = null,
  ): WorkoutSummaryViewModel {
    val workoutDao = FakeWorkoutDao(full)
    return WorkoutSummaryViewModel(
        savedStateHandle =
            savedStateHandle
                ?: SavedStateHandle(
                    if (full != null) mapOf(GymRoutes.WORKOUT_ID_ARG to full.workout.id)
                    else emptyMap(),
                ),
        workoutDao = workoutDao,
        statsUseCase = WorkoutStatsUseCase(workoutDao),
        routineUpdateUseCase = RoutineUpdateUseCase(routineDao),
        previousSetsUseCase = PreviousSetsUseCase(workoutDao),
        saveCompletedWorkoutAsRoutineUseCase = saveUseCase,
    )
  }

  /**
   * Тренировка «Грудь» в обратном порядке позиций/индексов; невыполненный подход в сводку не
   * попадает.
   */
  private fun fullWorkout(routineId: Long? = null): WorkoutFull =
      WorkoutFull(
          workout =
              WorkoutEntity(
                  id = "w1",
                  routineId = routineId,
                  name = "Грудь",
                  startedAt = 0L,
                  finishedAt = 45L * 60_000,
              ),
          exercises =
              listOf(
                  WorkoutExerciseWithSets(
                      workoutExercise =
                          WorkoutExerciseEntity(
                              id = 2,
                              workoutId = "w1",
                              exerciseId = 2,
                              position = 1,
                          ),
                      exercise =
                          ExerciseEntity(
                              id = 2,
                              name = "Разводка",
                              muscleGroup = MuscleGroup.CHEST,
                              type = ExerciseType.STRENGTH,
                          ),
                      sets =
                          listOf(
                              WorkoutSetEntity(
                                  id = 20,
                                  workoutExerciseId = 2,
                                  setIndex = 0,
                                  weightKg = 20.0,
                                  reps = 12,
                                  isCompleted = true,
                              ),
                              WorkoutSetEntity(
                                  id = 21,
                                  workoutExerciseId = 2,
                                  setIndex = 1,
                                  weightKg = 20.0,
                                  reps = 12,
                                  isCompleted = false,
                              ),
                          ),
                  ),
                  WorkoutExerciseWithSets(
                      workoutExercise =
                          WorkoutExerciseEntity(
                              id = 1,
                              workoutId = "w1",
                              exerciseId = 1,
                              position = 0,
                          ),
                      exercise =
                          ExerciseEntity(
                              id = 1,
                              name = "Жим лёжа",
                              muscleGroup = MuscleGroup.CHEST,
                              type = ExerciseType.STRENGTH,
                          ),
                      sets =
                          listOf(
                              WorkoutSetEntity(
                                  id = 11,
                                  workoutExerciseId = 1,
                                  setIndex = 1,
                                  weightKg = 80.0,
                                  reps = 10,
                                  isCompleted = true,
                              ),
                              WorkoutSetEntity(
                                  id = 10,
                                  workoutExerciseId = 1,
                                  setIndex = 0,
                                  weightKg = 80.0,
                                  reps = 8,
                                  isCompleted = true,
                              ),
                          ),
                  ),
              ),
      )

  private fun routineWithExercises(
      id: Long,
      exercises: List<RoutineExerciseWithExercise> = emptyList(),
  ): RoutineWithExercises =
      RoutineWithExercises(
          routine = RoutineEntity(id = id, name = "Программа"),
          exercises = exercises,
      )

  private class FakeWorkoutDao(private val full: WorkoutFull?) : WorkoutDao {
    override fun observeFinishedExerciseHistory() =
        flowOf(
            emptyList<com.valerochka1337.valerochkagym.data.db.relation.ExerciseWorkoutHistoryRow>()
        )

    override suspend fun getWorkoutFull(id: String): WorkoutFull? = full

    override suspend fun maxCompletedWeight(exerciseId: Long, excludeWorkoutId: String): Double? =
        null

    override suspend fun insertWorkout(workout: WorkoutEntity) = Unit

    override suspend fun insertWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long = 0

    override suspend fun insertSet(set: WorkoutSetEntity): Long = 0

    override suspend fun insertSets(sets: List<WorkoutSetEntity>): List<Long> = emptyList()

    override suspend fun updateSet(set: WorkoutSetEntity) = Unit

    override suspend fun updateWorkoutExercises(exercises: List<WorkoutExerciseEntity>) = Unit

    override suspend fun setSetCompleted(setId: Long, completed: Boolean, completedAt: Long?) = Unit

    override suspend fun getSet(setId: Long): WorkoutSetEntity? = null

    override suspend fun getSetsForWorkoutExercise(
        workoutExerciseId: Long
    ): List<WorkoutSetEntity> = emptyList()

    override suspend fun getWorkoutExercises(workoutId: String): List<WorkoutExerciseEntity> =
        emptyList()

    override suspend fun setFinishedAt(id: String, finishedAt: Long) = Unit

    override fun observeActiveWorkout(): Flow<WorkoutFull?> = flowOf(null)

    override suspend fun getActiveWorkoutId(): String? = null

    override fun observeFinishedWorkouts(): Flow<List<WorkoutEntity>> = flowOf(emptyList())

    override fun observeCompletedSets(): Flow<List<AnalyticsSetRow>> = flowOf(emptyList())

    override suspend fun lastCompletedSetsForExercise(exerciseId: Long): List<WorkoutSetEntity> =
        emptyList()

    override suspend fun setUploadStatus(workoutId: String, status: UploadStatus, error: String?) =
        Unit

    override fun observeWorkout(id: String): Flow<WorkoutEntity?> = flowOf(null)

    override suspend fun getFinishedNotUploaded(): List<String> = emptyList()

    override suspend fun getExistingWorkoutIds(): List<String> = emptyList()

    override suspend fun deleteWorkout(id: String) = Unit

    override suspend fun deleteSet(id: Long) = Unit

    override suspend fun deleteWorkoutExercise(id: Long) = Unit
  }

  private class FakeRoutineDao(private val routine: RoutineWithExercises?) : RoutineDao {
    val replacedExercises = mutableListOf<RoutineExerciseEntity>()

    override suspend fun getRoutineWithExercises(id: Long): RoutineWithExercises? = routine

    override suspend fun getRoutineBySyncId(syncId: String): RoutineEntity? = null

    override suspend fun replaceRoutineExercises(
        routineId: Long,
        list: List<RoutineExerciseEntity>,
    ) {
      replacedExercises += list
    }

    override fun observeRoutinesWithCount(): Flow<List<RoutineWithCount>> = flowOf(emptyList())

    override fun observeRoutinesFull(): Flow<List<RoutineWithExercises>> = flowOf(emptyList())

    override suspend fun getRoutineName(id: Long): String? = routine?.routine?.name

    override suspend fun upsertRoutine(routine: RoutineEntity): Long = 0

    override suspend fun deleteRoutine(id: Long) = Unit

    override suspend fun insertRoutineExercises(
        routineExercises: List<RoutineExerciseEntity>
    ): List<Long> = emptyList()

    override suspend fun deleteRoutineExercises(routineId: Long) = Unit
  }

  private class FakeSaveGymRepository(
      private val result: SaveRoutineConfigurationResult? = null,
  ) : GymRepository by NoOpGymRepository {
    val drafts = mutableListOf<RoutineConfigurationDraft>()
    val durableRoutines = linkedMapOf<String, RoutineEntity>()

    override suspend fun saveRoutineConfiguration(
        draft: RoutineConfigurationDraft
    ): SaveRoutineConfigurationResult {
      drafts += draft
      result?.let {
        return it
      }
      val saved =
          durableRoutines.getOrPut(draft.routine.syncId) {
            draft.routine.copy(id = durableRoutines.size + 1L)
          }
      return SaveRoutineConfigurationResult.Saved(saved.id, saved)
    }
  }

  private class BlockingSaveGymRepository : GymRepository by NoOpGymRepository {
    val drafts = mutableListOf<RoutineConfigurationDraft>()
    val release = CompletableDeferred<Unit>()

    override suspend fun saveRoutineConfiguration(
        draft: RoutineConfigurationDraft
    ): SaveRoutineConfigurationResult {
      drafts += draft
      release.await()
      return SaveRoutineConfigurationResult.Saved(1L, draft.routine)
    }
  }

  private class CommitThenSuspendSaveGymRepository : GymRepository by NoOpGymRepository {
    val durableRoutines = linkedMapOf<String, RoutineEntity>()
    val firstCommit = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun saveRoutineConfiguration(
        draft: RoutineConfigurationDraft
    ): SaveRoutineConfigurationResult {
      val existing = durableRoutines[draft.routine.syncId]
      if (existing != null) return SaveRoutineConfigurationResult.Saved(existing.id, existing)
      val saved = draft.routine.copy(id = durableRoutines.size + 1L)
      durableRoutines[saved.syncId] = saved
      firstCommit.complete(Unit)
      release.await()
      return SaveRoutineConfigurationResult.Saved(saved.id, saved)
    }
  }
}
