package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import com.valerochka1337.valerochkagym.service.RestTimerState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateMonitor
import com.valerochka1337.valerochkagym.service.heartrate.freshAt
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class CoachWorkoutReader
@Inject
constructor(
    private val database: GymDatabase,
    private val restTimer: RestTimerEngine,
    private val sessions: BackendSessionStore,
    private val heartRateMonitor: HeartRateMonitor? = null,
) {
  private val json = Json { ignoreUnknownKeys = false }

  suspend fun snapshot(
      accountId: String,
      workoutId: String,
      expectedSessionEpoch: Long? = null,
  ): WorkoutSnapshot? {
    if (!belongsToLiveAccount(accountId, expectedSessionEpoch)) return null
    val full = database.workoutDao().getWorkoutFull(workoutId) ?: return null
    if (full.workout.finishedAt != null) return null
    val context = database.coachDao().context(workoutId)
    if (context != null && context.accountId != accountId) return null
    val exercises =
        full.exercises
            .sortedBy { it.workoutExercise.position }
            .map { row ->
              val exercise =
                  database.exerciseDao().getById(row.workoutExercise.exerciseId) ?: return@map null
              SnapshotExercise(
                  sectionId = row.workoutExercise.sectionId,
                  exerciseId = exercise.id,
                  exerciseSyncId = exercise.syncId,
                  name = exercise.name,
                  type = exercise.type,
                  position = row.workoutExercise.position,
                  muscleIds =
                      database
                          .exerciseMuscleDao()
                          .getForExercise(exercise.id)
                          .map { it.muscle.name }
                          .toSet(),
                  equipmentIds = database.exerciseDao().getRequirementIds(exercise.id).toSet(),
                  sets =
                      row.sets
                          .sortedBy { it.setIndex }
                          .map { set ->
                            SnapshotSet(
                                syncId = set.syncId,
                                setIndex = set.setIndex,
                                completed = set.isCompleted,
                                weightKg = set.weightKg,
                                reps = set.reps,
                                durationSec = set.durationSec,
                                completedAt = set.completedAt,
                                speedKmh = set.speedKmh,
                                inclinePct = set.inclinePct,
                                setType = set.setType,
                                originalWeightKg = set.originalWeightKg,
                                originalReps = set.originalReps,
                                originalDurationSec = set.originalDurationSec,
                                originalSpeedKmh = set.originalSpeedKmh,
                                originalInclinePct = set.originalInclinePct,
                                targetWeightKg = set.targetWeightKg,
                                targetReps = set.targetReps,
                                targetDurationSec = set.targetDurationSec,
                                targetSpeedKmh = set.targetSpeedKmh,
                                targetInclinePct = set.targetInclinePct,
                                actualWeightKg = set.actualWeightKg,
                                actualReps = set.actualReps,
                                actualDurationSec = set.actualDurationSec,
                                actualSpeedKmh = set.actualSpeedKmh,
                                actualInclinePct = set.actualInclinePct,
                                reportedFeelings = set.reportedFeelingsJson.decodeStrings(),
                            )
                          },
                  history =
                      database.workoutDao().lastCompletedSetsForExercise(exercise.id).mapNotNull {
                          set ->
                        set.completedAt?.let { completedAt ->
                          SnapshotHistory(
                              completedAt,
                              set.setIndex,
                              set.weightKg,
                              set.reps,
                              set.durationSec,
                              set.speedKmh,
                              set.inclinePct,
                              set.setType,
                          )
                        }
                      },
              )
            }
            .filterNotNull()
    val allSets = exercises.flatMap { it.sets }
    val current = allSets.firstOrNull { !it.completed }?.syncId
    val currentIndex = allSets.indexOfFirst { it.syncId == current }
    val previous =
        allSets
            .withIndex()
            .filter { it.value.completed }
            .maxWithOrNull(
                compareBy<IndexedValue<SnapshotSet>> { it.value.completedAt ?: Long.MIN_VALUE }
                    .thenBy { it.index }
            )
            ?.value
            ?.syncId
    val next =
        currentIndex
            .takeIf { it >= 0 }
            ?.let { index -> allSets.drop(index + 1).firstOrNull { !it.completed }?.syncId }
    return WorkoutSnapshot(
        accountId = accountId,
        workoutId = workoutId,
        revision = full.workout.coachRevision,
        exercises = exercises,
        currentSetId = current,
        previousSetId = previous,
        nextSetId = next,
        rest = restSnapshot(),
        elapsedSeconds =
            ((System.currentTimeMillis() - full.workout.startedAt) / 1_000).coerceAtLeast(0),
        availableTimeMinutes =
            context?.availableTimeEndsAtMillis?.let { endsAt ->
              ((endsAt - System.currentTimeMillis()).coerceAtLeast(0) + 59_999L)
                  .div(60_000L)
                  .toInt()
            } ?: context?.availableTimeMinutes,
        occupiedEquipment = context?.occupiedEquipmentJson?.decodeStrings().orEmpty(),
        excludedExerciseIds = context?.excludedExerciseIdsJson?.decodeLongs().orEmpty(),
        feelings = allSets.flatMap { it.reportedFeelings }.toSet(),
        pulse =
            heartRateMonitor?.reading?.value?.freshAt(System.currentTimeMillis())?.let {
              SnapshotPulse(it.bpm, it.updatedAtMillis)
            },
    )
  }

  suspend fun find(
      snapshot: WorkoutSnapshot,
      query: String?,
      equipment: Set<String>?,
      muscles: Set<String>?,
  ): List<FoundCoachExercise> {
    val normalized = query?.trim()?.lowercase().orEmpty()
    val exercises = mutableListOf<FoundCoachExercise>()
    for (exercise in database.exerciseDao().getAllOnce()) {
      if (exercise.archived || exercise.id in snapshot.excludedExerciseIds) continue
      val muscleNames =
          database.exerciseMuscleDao().getForExercise(exercise.id).map { it.muscle.name }.toSet()
      val requirements = database.exerciseDao().getRequirementIds(exercise.id).toSet()
      if (
          (normalized.isBlank() || exercise.name.lowercase().contains(normalized)) &&
              (muscles.isNullOrEmpty() || muscleNames.containsAll(muscles)) &&
              (equipment.isNullOrEmpty() || requirements.containsAll(equipment))
      ) {
        exercises += FoundCoachExercise(exercise.syncId, exercise.name, muscleNames, requirements)
        if (exercises.size == 50) break
      }
    }
    return exercises
  }

  suspend fun history(exerciseId: String): List<WorkoutSetEntity> {
    val exercise =
        database.exerciseDao().getAllOnce().singleOrNull { it.syncId == exerciseId }
            ?: return emptyList()
    val history = database.workoutDao().lastCompletedSetsForExercise(exercise.id).take(30)
    return history
  }

  private fun restSnapshot(): SnapshotRest? =
      when (val rest = restTimer.state.value) {
        is RestTimerState.Timed ->
            SnapshotRest(
                restTimer.currentStartId() ?: return null,
                rest.totalSec,
                rest.remainingSec,
                rest.endsAtMillis - rest.totalSec * 1_000L,
                rest.endsAtMillis,
            )
        is RestTimerState.HeartRate ->
            SnapshotRest(
                restTimer.currentStartId() ?: return null,
                null,
                null,
                rest.startedAtMillis,
            )
        null -> null
      }

  private fun belongsToLiveAccount(accountId: String, expectedSessionEpoch: Long? = null): Boolean {
    val session = sessions.snapshot() ?: return false
    if (
        session.tokens.userId != accountId ||
            (expectedSessionEpoch != null && session.epoch != expectedSessionEpoch)
    )
        return false
    return database.openHelper.writableDatabase
        .query("SELECT owner FROM backend_state WHERE id=1")
        .use { row -> row.moveToFirst() && !row.isNull(0) && row.getString(0) == accountId }
  }

  private fun String.decodeStrings(): Set<String> =
      runCatching { json.decodeFromString<List<String>>(this).toSet() }.getOrDefault(emptySet())

  private fun String.decodeLongs(): Set<Long> =
      runCatching { json.decodeFromString<List<Long>>(this).toSet() }.getOrDefault(emptySet())
}

data class FoundCoachExercise(
    val id: String,
    val name: String,
    val muscles: Set<String>,
    val equipment: Set<String>,
)
