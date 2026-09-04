package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.AnalyticsSetRow
import com.valerochka1337.valerochkagym.data.db.relation.ExerciseWorkoutHistoryRow
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.domain.DeleteGymResult
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogRepository
import com.valerochka1337.valerochkagym.domain.GymConfiguration
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.SaveGymResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseCatalogRepositoryImplTest {
  @Test
  fun `selected gym intersection and labels are applied before the catalog snapshot`() = runTest {
    val bench = exercise(1, "Жим")
    val squat = exercise(2, "Присед")
    val gyms =
        FakeGymRepository(
            available = listOf(bench),
            gyms =
                listOf(
                    GymConfiguration("one", "Первый", listOf(bench, squat)),
                    GymConfiguration("two", "Второй", listOf(bench)),
                    GymConfiguration("other", "Другой", listOf(squat)),
                ),
        )
    val repository = ExerciseCatalogRepositoryImpl(gyms, FakeExerciseMuscleDao(), FakeWorkoutDao())

    val state = repository.observeCatalog(linkedSetOf("one", "two")).firstValue()

    assertEquals(setOf("one", "two"), gyms.requestedGymIds)
    assertEquals(listOf(bench.id), state.snapshot.exercises.map { it.id })
    assertEquals(listOf("Первый", "Второй"), state.gymNames)
  }

  @Test
  fun `local catalog maps and finished history reemit without a network source`() = runTest {
    val gyms = FakeGymRepository()
    val muscles = FakeExerciseMuscleDao()
    val workouts = FakeWorkoutDao()
    val repository: ExerciseCatalogRepository =
        ExerciseCatalogRepositoryImpl(gyms, muscles, workouts)
    val received =
        mutableListOf<com.valerochka1337.valerochkagym.domain.ExerciseCatalogRepositoryState>()
    collect(repository, received)

    val custom = exercise(42, "Своя тяга", custom = true)
    gyms.available.value = listOf(custom)
    muscles.rows.value = listOf(ExerciseMuscleEntity(custom.id, Muscle.LATS, 100))
    workouts.history.value = listOf(ExerciseWorkoutHistoryRow(custom.id, "finished", 900))

    val latest = received.last()
    assertTrue(received.size >= 4)
    assertEquals(listOf(custom.id), latest.snapshot.exercises.map { it.id })
    assertEquals(listOf(Muscle.LATS), latest.snapshot.muscles.map { it.muscle })
    assertEquals(listOf("finished"), latest.snapshot.history.map { it.workoutId })
    assertTrue(gyms.requestedGymIds.isEmpty())
  }

  private fun TestScope.collect(
      repository: ExerciseCatalogRepository,
      received: MutableList<com.valerochka1337.valerochkagym.domain.ExerciseCatalogRepositoryState>,
  ) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      repository.observeCatalog(emptySet()).collect { received += it }
    }
  }

  private suspend fun Flow<com.valerochka1337.valerochkagym.domain.ExerciseCatalogRepositoryState>
      .firstValue() = first()

  private fun exercise(id: Long, name: String, custom: Boolean = false) =
      ExerciseEntity(
          id = id,
          name = name,
          muscleGroup = MuscleGroup.BACK,
          type = ExerciseType.STRENGTH,
          isCustom = custom,
      )

  private class FakeGymRepository(
      available: List<ExerciseEntity> = emptyList(),
      gyms: List<GymConfiguration> = emptyList(),
  ) : GymRepository {
    val available = MutableStateFlow(available)
    private val gyms = MutableStateFlow(gyms)
    var requestedGymIds: Set<String> = emptySet()
      private set

    override fun observeGyms(): Flow<List<GymConfiguration>> = gyms

    override fun observeExerciseCatalog(): Flow<List<ExerciseEntity>> = available

    override fun observeAvailableExercises(gymIds: Set<String>): Flow<List<ExerciseEntity>> {
      requestedGymIds = gymIds
      return available
    }

    override suspend fun getGym(id: String): GymConfiguration? = gyms.value.find { it.id == id }

    override suspend fun saveGym(id: String?, name: String, exerciseIds: Set<Long>) =
        SaveGymResult.Failure

    override suspend fun deleteGym(id: String) = DeleteGymResult.NotFound
  }

  private class FakeExerciseMuscleDao : ExerciseMuscleDao {
    val rows = MutableStateFlow<List<ExerciseMuscleEntity>>(emptyList())

    override fun observeAll(): Flow<List<ExerciseMuscleEntity>> = rows

    override suspend fun getForExercise(exerciseId: Long) =
        rows.value.filter { it.exerciseId == exerciseId }

    override suspend fun getMappedExerciseIds() = rows.value.map { it.exerciseId }.distinct()

    override suspend fun upsertAll(rows: List<ExerciseMuscleEntity>) {
      this.rows.value += rows
    }

    override suspend fun deleteForExercise(exerciseId: Long) {
      rows.value = rows.value.filterNot { it.exerciseId == exerciseId }
    }
  }

  private class FakeWorkoutDao : WorkoutDao {
    val history = MutableStateFlow<List<ExerciseWorkoutHistoryRow>>(emptyList())

    override fun observeFinishedExerciseHistory() = history

    override suspend fun insertWorkout(workout: WorkoutEntity) = Unit

    override suspend fun insertWorkoutExercise(workoutExercise: WorkoutExerciseEntity) = 0L

    override suspend fun insertSet(set: WorkoutSetEntity) = 0L

    override suspend fun insertSets(sets: List<WorkoutSetEntity>) = emptyList<Long>()

    override suspend fun updateSet(set: WorkoutSetEntity) = Unit

    override suspend fun updateWorkoutExercises(exercises: List<WorkoutExerciseEntity>) = Unit

    override suspend fun setSetCompleted(setId: Long, completed: Boolean, completedAt: Long?) = Unit

    override suspend fun getSet(setId: Long): WorkoutSetEntity? = null

    override suspend fun getSetsForWorkoutExercise(workoutExerciseId: Long) =
        emptyList<WorkoutSetEntity>()

    override suspend fun getWorkoutExercises(workoutId: String) = emptyList<WorkoutExerciseEntity>()

    override suspend fun setFinishedAt(id: String, finishedAt: Long) = Unit

    override fun observeActiveWorkout(): Flow<WorkoutFull?> = flowOf(null)

    override suspend fun getActiveWorkoutId(): String? = null

    override fun observeFinishedWorkouts(): Flow<List<WorkoutEntity>> = flowOf(emptyList())

    override fun observeCompletedSets(): Flow<List<AnalyticsSetRow>> = flowOf(emptyList())

    override suspend fun getWorkoutFull(id: String): WorkoutFull? = null

    override suspend fun lastCompletedSetsForExercise(exerciseId: Long) =
        emptyList<WorkoutSetEntity>()

    override suspend fun maxCompletedWeight(exerciseId: Long, excludeWorkoutId: String): Double? =
        null

    override suspend fun setUploadStatus(workoutId: String, status: UploadStatus, error: String?) =
        Unit

    override fun observeWorkout(id: String): Flow<WorkoutEntity?> = flowOf(null)

    override suspend fun getFinishedNotUploaded() = emptyList<String>()

    override suspend fun getExistingWorkoutIds() = emptyList<String>()

    override suspend fun deleteWorkout(id: String) = Unit

    override suspend fun deleteSet(id: Long) = Unit

    override suspend fun deleteWorkoutExercise(id: Long) = Unit
  }
}
