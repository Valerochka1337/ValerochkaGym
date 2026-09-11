package com.valerochka1337.valerochkagym.domain

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.ai.CoachChangeIntent
import com.valerochka1337.valerochkagym.data.ai.CoachRestAction
import com.valerochka1337.valerochkagym.data.ai.CoachSetValues
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.CoachJournalEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachMessageEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachSessionContextEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import com.valerochka1337.valerochkagym.service.RestTimerState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateMonitor
import com.valerochka1337.valerochkagym.service.heartrate.freshAt
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Host-side bridge from validated portable tool IDs to local rows and durable coach decisions. */
@Singleton
class WorkoutControlService
@Inject
constructor(
    private val database: GymDatabase,
    private val coordinator: WorkoutMutationCoordinator,
    private val restTimer: RestTimerEngine,
    private val sessions: BackendSessionStore,
    private val heartRateMonitor: HeartRateMonitor? = null,
    private val writes: WorkoutWriteQueue = WorkoutWriteQueue(),
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

  /**
   * Model changes are always saved for explicit confirmation; local commands use
   * coordinator.submit.
   */
  suspend fun saveModelProposal(
      accountId: String,
      workoutId: String,
      baseRevision: Long,
      intents: List<CoachChangeIntent>,
      expiresAt: Long,
      expectedSessionEpoch: Long? = null,
  ): WorkoutProposal? {
    val snapshot = snapshot(accountId, workoutId, expectedSessionEpoch) ?: return null
    if (snapshot.revision != baseRevision) return null
    val packet = mapIntents(snapshot, intents) ?: return null
    val catalogue = database.exerciseDao().getAllOnce()
    val exerciseNames = catalogue.associate { it.id to it.name }
    val weightOptionalExerciseIds =
        catalogue.filter { it.type != ExerciseType.STRENGTH }.map { it.id }.toSet()
    val summary =
        runCatching {
              WorkoutChangeSummary.describe(
                  snapshot,
                  packet,
                  exerciseNames = exerciseNames,
                  weightOptionalExerciseIds = weightOptionalExerciseIds,
              )
            }
            .getOrNull() ?: return null
    return coordinator.saveProposal(
        accountId = accountId,
        workoutId = workoutId,
        packet = packet,
        expectedRevision = baseRevision,
        beforeSummary = summary.before,
        afterSummary = summary.after,
        expiresAt = expiresAt,
        expectedSessionEpoch = expectedSessionEpoch,
    )
  }

  suspend fun confirmProposal(
      accountId: String,
      proposalId: String,
      operationId: String,
      expectedSessionEpoch: Long? = null,
  ) = coordinator.confirmProposal(accountId, proposalId, operationId, expectedSessionEpoch)

  suspend fun cancelProposal(
      accountId: String,
      proposalId: String,
      expectedSessionEpoch: Long? = null,
  ) = coordinator.cancelProposal(accountId, proposalId, expectedSessionEpoch)

  suspend fun setInitiativeEnabled(
      accountId: String,
      workoutId: String,
      enabled: Boolean,
      expectedSessionEpoch: Long? = null,
  ): Boolean {
    return writes.write {
      database.withTransaction {
        if (!belongsToLiveAccount(accountId, expectedSessionEpoch)) return@withTransaction false
        val existing =
            database.coachDao().context(workoutId)
                ?: CoachSessionContextEntity(workoutId, accountId)
        if (existing.accountId != accountId) return@withTransaction false
        database
            .coachDao()
            .saveContext(
                existing.copy(initiativeEnabled = enabled, initiativePendingInteraction = false)
            )
        true
      }
    }
  }

  /** Appends an immutable transcript event and its upload journal entry in one Room transaction. */
  suspend fun appendMessage(
      id: String,
      accountId: String,
      workoutId: String,
      role: String,
      text: String,
      createdAt: Long = System.currentTimeMillis(),
      expectedSessionEpoch: Long? = null,
      status: String = "DELIVERED",
  ): Boolean {
    return writes.write {
      database.withTransaction {
        if (!belongsToLiveAccount(accountId, expectedSessionEpoch)) return@withTransaction false
        val context = database.coachDao().context(workoutId)
        if (context != null && context.accountId != accountId) return@withTransaction false
        val currentContext = context ?: CoachSessionContextEntity(workoutId, accountId)
        val nextContext =
            if (role == "user" && database.coachDao().pendingProposal(workoutId) == null) {
              currentContext.copy(initiativePendingInteraction = false)
            } else {
              currentContext
            }
        database.coachDao().saveContext(nextContext)
        database
            .coachDao()
            .saveMessage(
                CoachMessageEntity(id, accountId, workoutId, role, text, createdAt, status)
            )
        database
            .coachDao()
            .saveJournal(
                CoachJournalEntity(
                    id = id,
                    accountId = accountId,
                    workoutId = workoutId,
                    createdAt = createdAt,
                    payload =
                        json.encodeToString(
                            buildJsonObject {
                              put("kind", "message")
                              put("role", role)
                              put("text", text)
                            }
                        ),
                )
            )
        true
      }
    }
  }

  /** Emits at most one app-authored initiative and commits its durable quota with that message. */
  suspend fun considerInitiative(
      accountId: String,
      workoutId: String,
      expectedSessionEpoch: Long? = null,
      nowMillis: Long = System.currentTimeMillis(),
  ): CoachInitiativeDecision? {
    if (!belongsToLiveAccount(accountId, expectedSessionEpoch)) return null
    val full = database.workoutDao().getWorkoutFull(workoutId) ?: return null
    if (full.workout.finishedAt != null) return null
    val context =
        database.coachDao().context(workoutId) ?: CoachSessionContextEntity(workoutId, accountId)
    if (context.accountId != accountId) return null
    val state =
        CoachInitiativeState(
            enabled = context.initiativeEnabled,
            welcomed = context.initiativeWelcomed,
            automaticCount = context.initiativeAutomaticCount,
            lastAutomaticAtMillis = context.initiativeLastAutomaticAtMillis,
            askedExerciseIds = context.initiativeAskedExerciseIdsJson.decodeStrings(),
            endReminderSent = context.initiativeEndReminderSent,
            pendingInteraction = context.initiativePendingInteraction,
        )
    val rows = mutableListOf<CoachPerformanceSet>()
    for (section in full.exercises) {
      val exercise = database.exerciseDao().getById(section.workoutExercise.exerciseId) ?: continue
      rows +=
          database.workoutDao().coachCompletedSetsForExercise(exercise.id).map { row ->
            CoachPerformanceSet(
                setId = row.setId,
                workoutId = row.workoutId,
                exerciseId = row.exerciseId,
                exerciseName = row.exerciseName,
                setIndex = row.setIndex,
                weightKg = row.weightKg,
                reps = row.reps,
                completedAtMillis = row.completedAtMillis,
                workoutFinishedAtMillis = row.workoutFinishedAtMillis,
                setType = row.setType,
                interrupted = "INTERRUPTED" in row.reportedFeelingsJson.decodeStrings(),
            )
          }
    }
    val decision =
        CoachInitiativePolicy.next(
            state = state,
            nowMillis = nowMillis,
            completedSets = rows.filter { it.workoutId == workoutId },
            history = rows.filter { it.workoutId != workoutId },
            endsAtMillis = context.availableTimeEndsAtMillis,
        ) ?: return null
    val messageId = UUID.randomUUID().toString()
    return writes.write {
      database.withTransaction {
        if (!belongsToLiveAccount(accountId, expectedSessionEpoch)) return@withTransaction null
        val latest =
            database.coachDao().context(workoutId)
                ?: CoachSessionContextEntity(workoutId, accountId)
        if (latest.accountId != accountId || latest.initiativeState() != state)
            return@withTransaction null
        database.coachDao().saveContext(latest.withInitiativeState(decision.nextState))
        database
            .coachDao()
            .saveMessage(
                CoachMessageEntity(
                    messageId,
                    accountId,
                    workoutId,
                    "assistant",
                    decision.text,
                    nowMillis,
                ),
            )
        database
            .coachDao()
            .saveJournal(
                CoachJournalEntity(
                    id = messageId,
                    accountId = accountId,
                    workoutId = workoutId,
                    createdAt = nowMillis,
                    payload =
                        json.encodeToString(
                            buildJsonObject {
                              put("kind", "message")
                              put("role", "assistant")
                              put("text", decision.text)
                            }
                        ),
                )
            )
        decision
      }
    }
  }

  suspend fun submitLocal(
      accountId: String,
      workoutId: String,
      operationId: String,
      snapshot: WorkoutSnapshot,
      command: LocalWorkoutCommandParser.ParsedCommand,
      expectedSessionEpoch: Long? = null,
  ): CommandReceipt =
      coordinator.submit(
          accountId = accountId,
          workoutId = workoutId,
          operationId = operationId,
          expectedRevision = snapshot.revision,
          packet = command.packet,
          authority = command.authority,
          anchors =
              CommandAuthority.Anchors(
                  snapshot.currentSetId,
                  snapshot.previousSetId,
                  snapshot.nextSetId,
              ),
          expectedSessionEpoch = expectedSessionEpoch,
      )

  private suspend fun mapIntents(
      snapshot: WorkoutSnapshot,
      intents: List<CoachChangeIntent>,
  ): WorkoutChangeSet.Packet? =
      runCatching {
            require(intents.isNotEmpty())
            val byExerciseSync = database.exerciseDao().getAllOnce().associateBy { it.syncId }
            val replacementWeights = mutableMapOf<CoachChangeIntent.Replace, Double?>()
            for (intent in intents.filterIsInstance<CoachChangeIntent.Replace>()) {
              val replacement = byExerciseSync.getValue(intent.exerciseId)
              replacementWeights[intent] =
                  intent.weightKg
                      ?: if (replacement.type == ExerciseType.STRENGTH) {
                        database
                            .workoutDao()
                            .latestComparableCompletedWeight(replacement.id, snapshot.workoutId)
                            ?: throw IllegalArgumentException(
                                "Replacement weight has no completed history"
                            )
                      } else {
                        null
                      }
            }
            val typeBySetId =
                snapshot.exercises
                    .flatMap { exercise ->
                      exercise.sets.map { set -> set.syncId to requireNotNull(exercise.type) }
                    }
                    .toMap()
            intents.filterIsInstance<CoachChangeIntent.EditSet>().forEach { intent ->
              validateIntentSetValues(typeBySetId.getValue(intent.setId), intent.values)
            }
            val operations =
                intents.map { intent ->
                  when (intent) {
                    is CoachChangeIntent.AddExercise ->
                        WorkoutChangeSet.Operation.AddExercise(
                            byExerciseSync.getValue(intent.exerciseId).id
                        )
                    is CoachChangeIntent.RemoveRemaining ->
                        WorkoutChangeSet.Operation.RemoveRemaining(intent.sectionId)
                    is CoachChangeIntent.Move ->
                        WorkoutChangeSet.Operation.MoveExercise(intent.sectionId, intent.position)
                    is CoachChangeIntent.Swap -> {
                      val ids =
                          snapshot.exercises
                              .sortedBy { it.position }
                              .map { it.sectionId }
                              .toMutableList()
                      val first = ids.indexOf(intent.first)
                      val second = ids.indexOf(intent.second)
                      require(first >= 0 && second >= 0)
                      ids[first] = intent.second
                      ids[second] = intent.first
                      WorkoutChangeSet.Operation.ReorderExercises(ids)
                    }
                    is CoachChangeIntent.Reorder ->
                        WorkoutChangeSet.Operation.ReorderExercises(intent.sectionIds)
                    is CoachChangeIntent.Replace ->
                        WorkoutChangeSet.Operation.ReplaceRemaining(
                            intent.sectionId,
                            UUID.randomUUID().toString(),
                            byExerciseSync.getValue(intent.exerciseId).id,
                            intent.remainingSetIds,
                            replacementWeights.getValue(intent),
                        )
                    is CoachChangeIntent.AddSet ->
                        WorkoutChangeSet.Operation.AddSet(intent.sectionId)
                    is CoachChangeIntent.DeleteSet ->
                        WorkoutChangeSet.Operation.DeleteSet(intent.setId)
                    is CoachChangeIntent.EditSet ->
                        editOperation(intent.setId, intent.values, intent.recordResult)
                    is CoachChangeIntent.Complete ->
                        WorkoutChangeSet.Operation.EditSet(
                            intent.setId,
                            completed = intent.completed,
                        )
                    is CoachChangeIntent.Rest ->
                        WorkoutChangeSet.Operation.Rest(
                            intent.action.toDomain(),
                            intent.startId,
                            intent.seconds,
                        )
                    is CoachChangeIntent.AvailableTime ->
                        WorkoutChangeSet.Operation.SetAvailableTime(intent.minutes)
                    is CoachChangeIntent.OccupiedEquipment ->
                        WorkoutChangeSet.Operation.SetOccupiedEquipment(intent.ids)
                    is CoachChangeIntent.ExcludedExercises ->
                        WorkoutChangeSet.Operation.SetExcludedExercises(
                            intent.ids.map { byExerciseSync.getValue(it).id }.toSet()
                        )
                    is CoachChangeIntent.Feelings ->
                        WorkoutChangeSet.Operation.ReportFeelings(intent.setId, intent.feelings)
                    CoachChangeIntent.Undo -> WorkoutChangeSet.Operation.UndoLast
                  }
                }
            WorkoutChangeSet.Packet(operations)
          }
          .getOrNull()

  private fun validateIntentSetValues(type: ExerciseType, values: CoachSetValues) {
    val allowed =
        when (type) {
          ExerciseType.STRENGTH -> setOf("weight_kg", "reps")
          ExerciseType.TIMED -> setOf("duration_sec")
          ExerciseType.CARDIO -> setOf("duration_sec", "speed_kmh", "incline_pct")
        }
    require(values.supplied.all { it in allowed })
    require(values.weightKg == null || values.weightKg > 0)
    require(values.reps == null || values.reps > 0)
    require(values.durationSec == null || values.durationSec > 0)
    require(values.speedKmh == null || values.speedKmh > 0)
    require(values.inclinePct == null || values.inclinePct >= 0)
  }

  private fun editOperation(setId: String, values: CoachSetValues, recordResult: Boolean) =
      WorkoutChangeSet.Operation.EditSet(
          setSyncId = setId,
          weightKg = values.weightKg,
          reps = values.reps,
          durationSec = values.durationSec,
          speedKmh = values.speedKmh,
          inclinePct = values.inclinePct,
          clearFields =
              values.supplied
                  .filter { field ->
                    when (field) {
                      "weight_kg" -> values.weightKg == null
                      "reps" -> values.reps == null
                      "duration_sec" -> values.durationSec == null
                      "speed_kmh" -> values.speedKmh == null
                      "incline_pct" -> values.inclinePct == null
                      else -> false
                    }
                  }
                  .toSet(),
          recordResult = recordResult,
      )

  private fun CoachRestAction.toDomain() =
      when (this) {
        CoachRestAction.START -> RestAction.START
        CoachRestAction.EXTEND -> RestAction.EXTEND
        CoachRestAction.SKIP -> RestAction.SKIP
        CoachRestAction.FUTURE_DURATION -> RestAction.FUTURE_DURATION
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

  private fun CoachSessionContextEntity.initiativeState() =
      CoachInitiativeState(
          enabled = initiativeEnabled,
          welcomed = initiativeWelcomed,
          automaticCount = initiativeAutomaticCount,
          lastAutomaticAtMillis = initiativeLastAutomaticAtMillis,
          askedExerciseIds = initiativeAskedExerciseIdsJson.decodeStrings(),
          endReminderSent = initiativeEndReminderSent,
          pendingInteraction = initiativePendingInteraction,
      )

  private fun CoachSessionContextEntity.withInitiativeState(state: CoachInitiativeState) =
      copy(
          initiativeEnabled = state.enabled,
          initiativeWelcomed = state.welcomed,
          initiativeAutomaticCount = state.automaticCount,
          initiativeLastAutomaticAtMillis = state.lastAutomaticAtMillis,
          initiativeAskedExerciseIdsJson = json.encodeToString(state.askedExerciseIds.sorted()),
          initiativeEndReminderSent = state.endReminderSent,
          initiativePendingInteraction = state.pendingInteraction,
      )
}
