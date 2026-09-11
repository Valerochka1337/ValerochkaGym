package com.valerochka1337.valerochkagym.domain

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.CoachDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.CoachCommandReceiptEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachJournalEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachProposalEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachSessionContextEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private val SET_VALUE_FIELDS =
    setOf("weight_kg", "reps", "duration_sec", "speed_kmh", "incline_pct")

/** Serialized, transactional packet writer. Packet helpers never call [submit]. */
@Singleton
class WorkoutMutationCoordinator
@Inject
constructor(
    private val database: GymDatabase,
    private val workoutDao: WorkoutDao,
    private val coachDao: CoachDao,
    private val restTimer: RestTimerEngine,
    private val sessions: BackendSessionStore,
    private val writes: WorkoutWriteQueue,
) {
  private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
  }

  suspend fun submit(
      accountId: String,
      workoutId: String,
      operationId: String,
      expectedRevision: Long,
      packet: WorkoutChangeSet.Packet,
      authority: CommandAuthority? = null,
      anchors: CommandAuthority.Anchors = CommandAuthority.Anchors(null, null, null),
      expectedSessionEpoch: Long? = null,
  ): CommandReceipt =
      writes.write {
        val applied =
            runCatching {
                  database.withTransaction {
                    if (!belongsToLiveAccount(accountId, expectedSessionEpoch))
                        return@withTransaction Applied(stale(operationId, workoutId), null)
                    coachDao.receipt(operationId)?.let { saved ->
                      return@withTransaction Applied(
                          if (saved.accountId == accountId && saved.workoutId == workoutId)
                              CommandReceipt(
                                  operationId,
                                  workoutId,
                                  saved.revision,
                                  CommandResult.REPLAYED,
                              )
                          else stale(operationId, workoutId),
                          null,
                      )
                    }
                    val workout =
                        workoutDao.getWorkoutFull(workoutId)?.workout
                            ?: return@withTransaction Applied(stale(operationId, workoutId), null)
                    if (workout.finishedAt != null || workout.coachRevision != expectedRevision) {
                      return@withTransaction Applied(
                          saveReceipt(
                              accountId,
                              workoutId,
                              operationId,
                              workout.coachRevision,
                              CommandResult.STALE,
                          ),
                          null,
                      )
                    }
                    if (authority == null || !authority.authorizes(packet, anchors)) {
                      return@withTransaction Applied(
                          saveReceipt(
                              accountId,
                              workoutId,
                              operationId,
                              workout.coachRevision,
                              CommandResult.CONFIRMATION,
                          ),
                          null,
                      )
                    }
                    applyAccepted(accountId, workoutId, operationId, workout.coachRevision, packet)
                  }
                }
                .getOrElse { error ->
                  if (error is CancellationException) throw error
                  failureReceipt(accountId, workoutId, operationId, error)
                }
        applied.applyRestAfterCommit()
        applied.receipt
      }

  /** Saved proposals are app-owned data; confirmation never restores model-supplied authority. */
  suspend fun saveProposal(
      accountId: String,
      workoutId: String,
      packet: WorkoutChangeSet.Packet,
      expectedRevision: Long,
      beforeSummary: String,
      afterSummary: String,
      expiresAt: Long,
      expectedSessionEpoch: Long? = null,
  ): WorkoutProposal? =
      writes.write {
        database.withTransaction {
          if (!belongsToLiveAccount(accountId, expectedSessionEpoch)) return@withTransaction null
          val workout = workoutDao.getWorkoutFull(workoutId)?.workout ?: return@withTransaction null
          if (workout.finishedAt != null || workout.coachRevision != expectedRevision)
              return@withTransaction null
          WorkoutProposal(
                  accountId = accountId,
                  workoutId = workoutId,
                  baseRevision = workout.coachRevision,
                  beforeSummary = beforeSummary,
                  afterSummary = afterSummary,
                  packet = packet,
                  expiresAt = expiresAt,
              )
              .also { proposal ->
                coachDao.saveProposal(
                    CoachProposalEntity(
                        id = proposal.id,
                        accountId = accountId,
                        workoutId = workoutId,
                        baseRevision = proposal.baseRevision,
                        beforeSummary = beforeSummary,
                        afterSummary = afterSummary,
                        packetJson =
                            json.encodeToString(WorkoutChangeSet.Packet.serializer(), packet),
                        expiresAt = expiresAt,
                    )
                )
                saveProposalJournal(proposal, packet)
              }
        }
      }

  suspend fun confirmProposal(
      accountId: String,
      proposalId: String,
      operationId: String,
      expectedSessionEpoch: Long? = null,
  ): CommandReceipt =
      writes.write {
        val applied =
            runCatching {
                  database.withTransaction {
                    if (!belongsToLiveAccount(accountId, expectedSessionEpoch))
                        return@withTransaction Applied(stale(operationId, ""), null)
                    val proposal =
                        coachDao.pendingProposalForId(proposalId)
                            ?: return@withTransaction Applied(stale(operationId, ""), null)
                    if (
                        proposal.accountId != accountId ||
                            proposal.expiresAt < System.currentTimeMillis()
                    ) {
                      coachDao.setProposalState(proposalId, "EXPIRED")
                      return@withTransaction Applied(stale(operationId, proposal.workoutId), null)
                    }
                    coachDao.receipt(operationId)?.let { saved ->
                      return@withTransaction Applied(
                          CommandReceipt(
                              operationId,
                              saved.workoutId,
                              saved.revision,
                              CommandResult.REPLAYED,
                          ),
                          null,
                      )
                    }
                    val workout =
                        workoutDao.getWorkoutFull(proposal.workoutId)?.workout
                            ?: return@withTransaction Applied(
                                stale(operationId, proposal.workoutId),
                                null,
                            )
                    if (
                        workout.finishedAt != null || workout.coachRevision != proposal.baseRevision
                    ) {
                      coachDao.setProposalState(proposalId, "STALE")
                      return@withTransaction Applied(
                          saveReceipt(
                              accountId,
                              proposal.workoutId,
                              operationId,
                              workout.coachRevision,
                              CommandResult.STALE,
                          ),
                          null,
                      )
                    }
                    val packet =
                        json.decodeFromString(
                            WorkoutChangeSet.Packet.serializer(),
                            proposal.packetJson,
                        )
                    val accepted =
                        applyAccepted(
                            accountId,
                            proposal.workoutId,
                            operationId,
                            workout.coachRevision,
                            packet,
                        )
                    coachDao.setProposalState(proposalId, "CONFIRMED")
                    saveJournal(
                        journalId("$operationId:confirmed"),
                        accountId,
                        proposal.workoutId,
                        "decision",
                        "system",
                        "Предложение подтверждено",
                    )
                    accepted
                  }
                }
                .getOrElse { error ->
                  if (error is CancellationException) throw error
                  failureReceipt(accountId, null, operationId, error)
                }
        applied.applyRestAfterCommit()
        applied.receipt
      }

  suspend fun cancelProposal(
      accountId: String,
      proposalId: String,
      expectedSessionEpoch: Long? = null,
  ): Boolean =
      writes.write {
        database.withTransaction {
          if (!belongsToLiveAccount(accountId, expectedSessionEpoch)) return@withTransaction false
          val proposal = coachDao.pendingProposalForId(proposalId) ?: return@withTransaction false
          if (proposal.accountId != accountId) return@withTransaction false
          coachDao.setProposalState(proposalId, "CANCELLED")
          saveJournal(
              journalId("$proposalId:cancelled"),
              accountId,
              proposal.workoutId,
              "decision",
              "system",
              "Предложение отменено",
          )
          true
        }
      }

  private suspend fun applyAccepted(
      accountId: String,
      workoutId: String,
      operationId: String,
      priorRevision: Long,
      packet: WorkoutChangeSet.Packet,
  ): Applied {
    val undo = captureRestorePacket(workoutId)
    val contextBefore = coachDao.context(workoutId)
    val stagedRest = applyPacket(accountId, workoutId, packet)
    val revision = priorRevision + 1
    markChangedSets(workoutId, undo, revision)
    database.openHelper.writableDatabase.execSQL(
        "UPDATE workouts SET coachRevision=? WHERE id=?",
        arrayOf<Any?>(revision, workoutId),
    )
    updateContext(accountId, workoutId) {
      if (
          packet.operations.singleOrNull() is WorkoutChangeSet.Operation.UndoLast ||
              packet.hasIrreversibleRest()
      ) {
        it.copy(lastUndoPacketJson = null, lastUndoRevision = null)
      } else {
        it.copy(
            lastUndoPacketJson =
                json.encodeToString(
                    UndoEntry.serializer(),
                    UndoEntry(
                        undo,
                        (contextBefore ?: CoachSessionContextEntity(workoutId, accountId))
                            .undoContext(),
                    ),
                ),
            lastUndoRevision = revision,
        )
      }
    }
    val receipt = saveReceipt(accountId, workoutId, operationId, revision, CommandResult.APPLIED)
    saveCommandJournal(operationId, accountId, workoutId, packet, receipt)
    return Applied(receipt, stagedRest)
  }

  private suspend fun applyPacket(
      accountId: String,
      workoutId: String,
      packet: WorkoutChangeSet.Packet,
  ): WorkoutChangeSet.Operation.Rest? {
    var stagedRest: WorkoutChangeSet.Operation.Rest? = null
    packet.operations.forEach { op ->
      when (op) {
        is WorkoutChangeSet.Operation.EditSet -> editSet(workoutId, op)
        is WorkoutChangeSet.Operation.AddSet -> addSet(workoutId, op.sectionId)
        is WorkoutChangeSet.Operation.DeleteSet -> deleteSet(workoutId, op.setSyncId)
        is WorkoutChangeSet.Operation.MoveExercise ->
            moveExercise(workoutId, op.sectionId, op.targetPosition)
        is WorkoutChangeSet.Operation.ReorderExercises -> reorder(workoutId, op.sectionIds)
        is WorkoutChangeSet.Operation.AddExercise -> addExercise(workoutId, op.exerciseId)
        is WorkoutChangeSet.Operation.DeleteExercise -> deleteExercise(workoutId, op.sectionId)
        is WorkoutChangeSet.Operation.RemoveRemaining -> removeRemaining(workoutId, op.sectionId)
        is WorkoutChangeSet.Operation.ReplaceRemaining -> replaceRemaining(workoutId, op)
        is WorkoutChangeSet.Operation.Rest -> {
          validateRest(op)
          if (op.action == RestAction.FUTURE_DURATION)
              updateContext(accountId, workoutId) {
                it.copy(futureRestSeconds = requireNotNull(op.seconds))
              }
          else {
            check(stagedRest == null) { "A packet may change rest once" }
            stagedRest = op
          }
        }
        is WorkoutChangeSet.Operation.SetAvailableTime ->
            updateContext(accountId, workoutId) {
              it.copy(
                  availableTimeMinutes = op.minutes,
                  availableTimeEndsAtMillis =
                      op.minutes?.let { minutes -> System.currentTimeMillis() + minutes * 60_000L },
              )
            }
        is WorkoutChangeSet.Operation.SetOccupiedEquipment ->
            updateContext(accountId, workoutId) {
              it.copy(occupiedEquipmentJson = json.encodeToString(op.equipmentIds.sorted()))
            }
        is WorkoutChangeSet.Operation.SetExcludedExercises ->
            updateContext(accountId, workoutId) {
              it.copy(excludedExerciseIdsJson = json.encodeToString(op.exerciseIds.sorted()))
            }
        is WorkoutChangeSet.Operation.ReportFeelings -> reportFeelings(workoutId, op)
        WorkoutChangeSet.Operation.UndoLast -> restoreLastUndo(accountId, workoutId)
        is WorkoutChangeSet.Operation.RestoreWorkout -> restoreWorkout(workoutId, op)
      }
    }
    return stagedRest
  }

  private suspend fun editSet(workoutId: String, op: WorkoutChangeSet.Operation.EditSet) {
    val set = requireNotNull(setBySyncId(workoutId, op.setSyncId))
    validateSetValues(workoutId, op.setSyncId, op)
    val completed = op.completed ?: set.isCompleted
    val becameCompleted = (op.completed == true || op.recordResult) && !set.isCompleted
    val changesTarget = !set.isCompleted && !op.recordResult
    val edited =
        set.copy(
            weightKg = if ("weight_kg" in op.clearFields) null else op.weightKg ?: set.weightKg,
            reps = if ("reps" in op.clearFields) null else op.reps ?: set.reps,
            durationSec =
                if ("duration_sec" in op.clearFields) null else op.durationSec ?: set.durationSec,
            speedKmh = if ("speed_kmh" in op.clearFields) null else op.speedKmh ?: set.speedKmh,
            inclinePct =
                if ("incline_pct" in op.clearFields) null else op.inclinePct ?: set.inclinePct,
            isCompleted = if (op.recordResult) true else completed,
            completedAt =
                if (becameCompleted) System.currentTimeMillis()
                else if (!completed) null else set.completedAt,
            targetWeightKg =
                if (changesTarget && (op.weightKg != null || "weight_kg" in op.clearFields))
                    if ("weight_kg" in op.clearFields) null else op.weightKg
                else set.targetWeightKg,
            targetReps =
                if (changesTarget && (op.reps != null || "reps" in op.clearFields))
                    if ("reps" in op.clearFields) null else op.reps
                else set.targetReps,
            targetDurationSec =
                if (changesTarget && (op.durationSec != null || "duration_sec" in op.clearFields))
                    if ("duration_sec" in op.clearFields) null else op.durationSec
                else set.targetDurationSec,
            targetSpeedKmh =
                if (changesTarget && (op.speedKmh != null || "speed_kmh" in op.clearFields))
                    if ("speed_kmh" in op.clearFields) null else op.speedKmh
                else set.targetSpeedKmh,
            targetInclinePct =
                if (changesTarget && (op.inclinePct != null || "incline_pct" in op.clearFields))
                    if ("incline_pct" in op.clearFields) null else op.inclinePct
                else set.targetInclinePct,
        )
    val withResult =
        when {
          !edited.isCompleted ->
              edited.copy(
                  actualWeightKg = null,
                  actualReps = null,
                  actualDurationSec = null,
                  actualSpeedKmh = null,
                  actualInclinePct = null,
              )
          becameCompleted || op.recordResult ->
              edited.copy(
                  actualWeightKg = edited.weightKg,
                  actualReps = edited.reps,
                  actualDurationSec = edited.durationSec,
                  actualSpeedKmh = edited.speedKmh,
                  actualInclinePct = edited.inclinePct,
              )
          else -> edited
        }
    workoutDao.updateSet(withResult)
  }

  /** Reject values which would bypass the type-specific completed-set DAO contracts. */
  private suspend fun validateSetValues(
      workoutId: String,
      setSyncId: String,
      op: WorkoutChangeSet.Operation.EditSet,
  ) {
    val supplied = buildSet {
      if (op.weightKg != null) add("weight_kg")
      if (op.reps != null) add("reps")
      if (op.durationSec != null) add("duration_sec")
      if (op.speedKmh != null) add("speed_kmh")
      if (op.inclinePct != null) add("incline_pct")
      addAll(op.clearFields)
    }
    require(op.clearFields.all { it in SET_VALUE_FIELDS })
    val type = exerciseTypeForSet(workoutId, setSyncId)
    val allowed =
        when (type) {
          ExerciseType.STRENGTH -> setOf("weight_kg", "reps")
          ExerciseType.TIMED -> setOf("duration_sec")
          ExerciseType.CARDIO -> setOf("duration_sec", "speed_kmh", "incline_pct")
        }
    require(supplied.all { it in allowed }) { "Values do not match $type exercise" }
    require(op.weightKg == null || op.weightKg > 0)
    require(op.reps == null || op.reps > 0)
    require(op.durationSec == null || op.durationSec > 0)
    require(op.speedKmh == null || op.speedKmh > 0)
    // A flat treadmill is a valid cardio result; only negative incline is impossible.
    require(op.inclinePct == null || op.inclinePct >= 0)
  }

  private suspend fun exerciseTypeForSet(workoutId: String, setSyncId: String): ExerciseType {
    for (section in workoutDao.getWorkoutExercises(workoutId)) {
      if (workoutDao.getSetsForWorkoutExercise(section.id).any { it.syncId == setSyncId }) {
        return requireNotNull(database.exerciseDao().getById(section.exerciseId)).type
      }
    }
    throw NoSuchElementException("Unknown set")
  }

  private suspend fun addSet(workoutId: String, sectionId: String) {
    val section = section(workoutId, sectionId)
    val sets = workoutDao.getSetsForWorkoutExercise(section.id)
    val last = sets.lastOrNull()
    workoutDao.insertSet(
        WorkoutSetEntity(
            workoutExerciseId = section.id,
            setIndex = (sets.maxOfOrNull { it.setIndex } ?: -1) + 1,
            weightKg = last?.weightKg,
            reps = last?.reps,
            durationSec = last?.durationSec,
            speedKmh = last?.speedKmh,
            inclinePct = last?.inclinePct,
            originalWeightKg = last?.weightKg,
            originalReps = last?.reps,
            originalDurationSec = last?.durationSec,
            originalSpeedKmh = last?.speedKmh,
            originalInclinePct = last?.inclinePct,
            targetWeightKg = last?.weightKg,
            targetReps = last?.reps,
            targetDurationSec = last?.durationSec,
            targetSpeedKmh = last?.speedKmh,
            targetInclinePct = last?.inclinePct,
        )
    )
  }

  private suspend fun deleteSet(workoutId: String, syncId: String) {
    val set = requireNotNull(setBySyncId(workoutId, syncId))
    require(!set.isCompleted) { "Completed result cannot be deleted" }
    workoutDao.deleteSet(set.id)
  }

  private suspend fun moveExercise(workoutId: String, sectionId: String, targetPosition: Int) {
    val order =
        workoutDao
            .getWorkoutExercises(workoutId)
            .sortedBy { it.position }
            .map { it.sectionId }
            .toMutableList()
    require(order.remove(sectionId)) { "Unknown section" }
    order.add(targetPosition.coerceIn(0, order.size), sectionId)
    reorder(workoutId, order)
  }

  private suspend fun reorder(workoutId: String, sectionIds: List<String>) {
    val rows = workoutDao.getWorkoutExercises(workoutId)
    require(rows.size == sectionIds.size && rows.map { it.sectionId }.toSet() == sectionIds.toSet())
    val by = rows.associateBy { it.sectionId }
    workoutDao.updateWorkoutExercises(
        sectionIds.mapIndexed { position, id -> by.getValue(id).copy(position = position) }
    )
  }

  private suspend fun addExercise(workoutId: String, exerciseId: Long) {
    require(database.exerciseDao().getById(exerciseId) != null) { "Unknown exercise" }
    val id =
        workoutDao.insertWorkoutExercise(
            WorkoutExerciseEntity(
                workoutId = workoutId,
                exerciseId = exerciseId,
                position =
                    (workoutDao.getWorkoutExercises(workoutId).maxOfOrNull { it.position } ?: -1) +
                        1,
            )
        )
    workoutDao.insertSet(WorkoutSetEntity(workoutExerciseId = id, setIndex = 0))
  }

  private suspend fun deleteExercise(workoutId: String, sectionId: String) {
    val row = section(workoutId, sectionId)
    require(workoutDao.getSetsForWorkoutExercise(row.id).none { it.isCompleted }) {
      "Completed exercise cannot be deleted"
    }
    workoutDao.deleteWorkoutExercise(row.id)
    reorder(
        workoutId,
        workoutDao.getWorkoutExercises(workoutId).sortedBy { it.position }.map { it.sectionId },
    )
  }

  private suspend fun removeRemaining(workoutId: String, sectionId: String) {
    val row = section(workoutId, sectionId)
    workoutDao
        .getSetsForWorkoutExercise(row.id)
        .filterNot { it.isCompleted }
        .forEach { workoutDao.deleteSet(it.id) }
  }

  private suspend fun replaceRemaining(
      workoutId: String,
      op: WorkoutChangeSet.Operation.ReplaceRemaining,
  ) {
    val rows = workoutDao.getWorkoutExercises(workoutId)
    val source =
        rows.firstOrNull { it.sectionId == op.sourceSectionId } ?: error("Unknown source section")
    require(rows.none { it.sectionId == op.destinationSectionId }) {
      "Destination section already exists"
    }
    val replacement =
        requireNotNull(database.exerciseDao().getById(op.replacementExerciseId)) {
          "Unknown replacement"
        }
    val sourceExercise =
        requireNotNull(database.exerciseDao().getById(source.exerciseId)) {
          "Unknown source exercise"
        }
    val unfinished = workoutDao.getSetsForWorkoutExercise(source.id).filterNot { it.isCompleted }
    require(unfinished.map { it.syncId }.toSet() == op.unfinishedSetSyncIds.toSet()) {
      "Remaining set anchors changed"
    }
    val weight = op.replacementWeightKg
    require(weight != null || replacement.type != ExerciseType.STRENGTH) {
      "Replacement weight must be frozen before confirmation"
    }
    workoutDao.updateWorkoutExercises(
        rows.filter { it.position > source.position }.map { it.copy(position = it.position + 1) }
    )
    val destinationId =
        workoutDao.insertWorkoutExercise(
            WorkoutExerciseEntity(
                workoutId = workoutId,
                exerciseId = op.replacementExerciseId,
                sectionId = op.destinationSectionId,
                position = source.position + 1,
            )
        )
    unfinished.forEachIndexed { index, set ->
      workoutDao.updateSet(
          set.forReplacement(destinationId, index, sourceExercise.type, replacement.type, weight)
      )
    }
  }

  private fun WorkoutSetEntity.forReplacement(
      destinationId: Long,
      index: Int,
      sourceType: ExerciseType,
      replacementType: ExerciseType,
      frozenWeightKg: Double?,
  ): WorkoutSetEntity {
    val compatible = sourceType == replacementType
    val weight = if (replacementType == ExerciseType.STRENGTH) frozenWeightKg else null
    val reps = if (compatible && replacementType == ExerciseType.STRENGTH) reps else null
    val duration = if (compatible && replacementType != ExerciseType.STRENGTH) durationSec else null
    val speed = if (compatible && replacementType == ExerciseType.CARDIO) speedKmh else null
    val incline = if (compatible && replacementType == ExerciseType.CARDIO) inclinePct else null
    return copy(
        workoutExerciseId = destinationId,
        setIndex = index,
        weightKg = weight,
        reps = reps,
        durationSec = duration,
        speedKmh = speed,
        inclinePct = incline,
        originalWeightKg = weight,
        originalReps = reps,
        originalDurationSec = duration,
        originalSpeedKmh = speed,
        originalInclinePct = incline,
        targetWeightKg = weight,
        targetReps = reps,
        targetDurationSec = duration,
        targetSpeedKmh = speed,
        targetInclinePct = incline,
        actualWeightKg = null,
        actualReps = null,
        actualDurationSec = null,
        actualSpeedKmh = null,
        actualInclinePct = null,
    )
  }

  private suspend fun reportFeelings(
      workoutId: String,
      op: WorkoutChangeSet.Operation.ReportFeelings,
  ) {
    require(
        op.feelings.all { it in setOf("PAIN", "FATIGUE", "TECHNIQUE_BREAKDOWN", "INTERRUPTED") }
    )
    val set = requireNotNull(setBySyncId(workoutId, op.setSyncId))
    workoutDao.updateSet(set.copy(reportedFeelingsJson = json.encodeToString(op.feelings.sorted())))
  }

  private fun validateRest(op: WorkoutChangeSet.Operation.Rest) {
    when (op.action) {
      RestAction.START,
      RestAction.FUTURE_DURATION -> requireNotNull(op.seconds).also { require(it in 1..86_400) }
      RestAction.EXTEND -> {
        requireNotNull(op.seconds).also { require(it in 1..86_400) }
        if (restTimer.currentStartId() != op.expectedRestStartId) throw StaleCommand()
      }
      RestAction.SKIP ->
          if (restTimer.currentStartId() != op.expectedRestStartId) throw StaleCommand()
    }
  }

  private fun Applied.applyRestAfterCommit() {
    when (val op = rest) {
      null -> Unit
      is WorkoutChangeSet.Operation.Rest ->
          when (op.action) {
            RestAction.START -> restTimer.start(requireNotNull(op.seconds))
            RestAction.EXTEND ->
                restTimer.addSeconds(
                    requireNotNull(op.expectedRestStartId),
                    requireNotNull(op.seconds),
                )
            RestAction.SKIP -> restTimer.skip(requireNotNull(op.expectedRestStartId))
            RestAction.FUTURE_DURATION -> Unit
          }
    }
  }

  private suspend fun captureRestorePacket(workoutId: String): WorkoutChangeSet.Packet =
      WorkoutChangeSet.Packet(
          listOf(
              WorkoutChangeSet.Operation.RestoreWorkout(
                  workoutDao.getWorkoutExercises(workoutId).map { row ->
                    RestoreSection(
                        row.sectionId,
                        row.exerciseId,
                        row.position,
                        workoutDao.getSetsForWorkoutExercise(row.id).map(::restoreSet),
                    )
                  }
              )
          )
      )

  private suspend fun markChangedSets(
      workoutId: String,
      before: WorkoutChangeSet.Packet,
      revision: Long,
  ) {
    val prior =
        (before.operations.singleOrNull() as? WorkoutChangeSet.Operation.RestoreWorkout)
            ?.sections
            .orEmpty()
            .flatMap { it.sets }
            .associateBy { it.syncId }
    workoutDao.getWorkoutExercises(workoutId).forEach { section ->
      workoutDao.getSetsForWorkoutExercise(section.id).forEach { set ->
        if (prior[set.syncId] != restoreSet(set)) {
          workoutDao.updateSet(set.copy(coachMutationRevision = revision))
        }
      }
    }
  }

  private fun restoreSet(set: WorkoutSetEntity) =
      RestoreSet(
          set.syncId,
          set.setIndex,
          set.weightKg,
          set.reps,
          set.durationSec,
          set.speedKmh,
          set.inclinePct,
          set.isCompleted,
          set.completedAt,
          set.originalWeightKg,
          set.originalReps,
          set.originalDurationSec,
          set.originalSpeedKmh,
          set.originalInclinePct,
          set.targetWeightKg,
          set.targetReps,
          set.targetDurationSec,
          set.targetSpeedKmh,
          set.targetInclinePct,
          set.actualWeightKg,
          set.actualReps,
          set.actualDurationSec,
          set.actualSpeedKmh,
          set.actualInclinePct,
          set.setType,
          set.reportedFeelingsJson,
          set.restSnapshotJson,
          set.coachMutationRevision,
          set.note,
      )

  private suspend fun restoreLastUndo(accountId: String, workoutId: String) {
    val context = coachDao.context(workoutId) ?: throw StaleCommand()
    require(context.accountId == accountId)
    val revision =
        workoutDao.getWorkoutFull(workoutId)?.workout?.coachRevision ?: throw StaleCommand()
    if (context.lastUndoRevision != revision) throw StaleCommand()
    val entry =
        context.lastUndoPacketJson?.let { json.decodeFromString(UndoEntry.serializer(), it) }
            ?: throw StaleCommand()
    require(entry.packet.operations.singleOrNull() is WorkoutChangeSet.Operation.RestoreWorkout)
    applyPacket(accountId, workoutId, entry.packet)
    entry.context?.let { saved ->
      coachDao.saveContext(
          context.copy(
              availableTimeMinutes = saved.availableTimeMinutes,
              availableTimeEndsAtMillis = saved.availableTimeEndsAtMillis,
              futureRestSeconds = saved.futureRestSeconds,
              occupiedEquipmentJson = saved.occupiedEquipmentJson,
              excludedExerciseIdsJson = saved.excludedExerciseIdsJson,
          )
      )
    }
  }

  private suspend fun restoreWorkout(
      workoutId: String,
      op: WorkoutChangeSet.Operation.RestoreWorkout,
  ) {
    workoutDao.getWorkoutExercises(workoutId).forEach { workoutDao.deleteWorkoutExercise(it.id) }
    op.sections
        .sortedBy { it.position }
        .forEach { section ->
          val localId =
              workoutDao.insertWorkoutExercise(
                  WorkoutExerciseEntity(
                      workoutId = workoutId,
                      exerciseId = section.exerciseId,
                      sectionId = section.sectionId,
                      position = section.position,
                  )
              )
          section.sets.forEach { set ->
            workoutDao.insertSet(
                WorkoutSetEntity(
                    workoutExerciseId = localId,
                    setIndex = set.setIndex,
                    syncId = set.syncId,
                    weightKg = set.weightKg,
                    reps = set.reps,
                    durationSec = set.durationSec,
                    speedKmh = set.speedKmh,
                    inclinePct = set.inclinePct,
                    isCompleted = set.isCompleted,
                    completedAt = set.completedAt,
                    note = set.note,
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
                    setType = set.setType,
                    reportedFeelingsJson = set.reportedFeelingsJson,
                    restSnapshotJson = set.restSnapshotJson,
                    coachMutationRevision = set.coachMutationRevision,
                )
            )
          }
        }
  }

  private suspend fun section(workoutId: String, sectionId: String): WorkoutExerciseEntity =
      workoutDao.getWorkoutExercises(workoutId).firstOrNull { it.sectionId == sectionId }
          ?: throw NoSuchElementException("Unknown section")

  private suspend fun setBySyncId(workoutId: String, syncId: String): WorkoutSetEntity? {
    for (section in workoutDao.getWorkoutExercises(workoutId)) workoutDao
        .getSetsForWorkoutExercise(section.id)
        .firstOrNull { it.syncId == syncId }
        ?.let {
          return it
        }
    return null
  }

  private suspend fun updateContext(
      accountId: String,
      workoutId: String,
      transform: (CoachSessionContextEntity) -> CoachSessionContextEntity,
  ) {
    val existing = coachDao.context(workoutId)
    require(existing == null || existing.accountId == accountId) { "Context account changed" }
    coachDao.saveContext(transform(existing ?: CoachSessionContextEntity(workoutId, accountId)))
  }

  private fun CoachSessionContextEntity.undoContext() =
      UndoContext(
          availableTimeMinutes,
          availableTimeEndsAtMillis,
          futureRestSeconds,
          occupiedEquipmentJson,
          excludedExerciseIdsJson,
      )

  private fun WorkoutChangeSet.Packet.hasIrreversibleRest() =
      operations.any {
        it is WorkoutChangeSet.Operation.Rest && it.action != RestAction.FUTURE_DURATION
      }

  private fun belongsToLiveAccount(accountId: String, expectedSessionEpoch: Long? = null): Boolean {
    val session = sessions.snapshot() ?: return false
    if (
        session.tokens.userId != accountId ||
            (expectedSessionEpoch != null && session.epoch != expectedSessionEpoch)
    )
        return false
    val owner =
        database.openHelper.writableDatabase
            .query("SELECT owner FROM backend_state WHERE id=1")
            .use { row -> if (row.moveToFirst() && !row.isNull(0)) row.getString(0) else null }
    return owner == accountId
  }

  private suspend fun failureReceipt(
      accountId: String,
      workoutId: String?,
      operationId: String,
      error: Throwable,
  ): Applied {
    val workout = workoutId?.let { workoutDao.getWorkoutFull(it)?.workout }
    if (workout == null || !belongsToLiveAccount(accountId))
        return Applied(stale(operationId, workoutId.orEmpty()), null)
    return Applied(
        saveReceipt(
            accountId,
            workout.id,
            operationId,
            workout.coachRevision,
            if (error is StaleCommand) CommandResult.STALE else CommandResult.INVALID,
        ),
        null,
    )
  }

  private suspend fun saveReceipt(
      accountId: String,
      workoutId: String,
      operationId: String,
      revision: Long,
      result: CommandResult,
  ): CommandReceipt {
    coachDao.saveReceipt(
        CoachCommandReceiptEntity(
            operationId,
            accountId,
            workoutId,
            revision,
            result.name,
            System.currentTimeMillis(),
        )
    )
    return CommandReceipt(operationId, workoutId, revision, result)
  }

  private suspend fun saveJournal(
      id: String,
      accountId: String,
      workoutId: String,
      kind: String,
      role: String,
      text: String,
  ) {
    coachDao.saveJournal(
        CoachJournalEntity(
            id = id,
            accountId = accountId,
            workoutId = workoutId,
            createdAt = System.currentTimeMillis(),
            payload =
                json.encodeToString(
                    buildJsonObject {
                      put("kind", kind)
                      put("role", role)
                      put("text", text)
                    }
                ),
        )
    )
  }

  private suspend fun saveProposalJournal(
      proposal: WorkoutProposal,
      packet: WorkoutChangeSet.Packet,
  ) {
    coachDao.saveJournal(
        CoachJournalEntity(
            id = proposal.id,
            accountId = proposal.accountId,
            workoutId = proposal.workoutId,
            createdAt = System.currentTimeMillis(),
            payload =
                json.encodeToString(
                    buildJsonObject {
                      put("kind", "proposal")
                      put("role", "assistant")
                      put("text", proposal.afterSummary)
                      put("before", proposal.beforeSummary)
                      put("after", proposal.afterSummary)
                      put(
                          "packet",
                          json.encodeToJsonElement(WorkoutChangeSet.Packet.serializer(), packet),
                      )
                    }
                ),
        )
    )
  }

  private suspend fun saveCommandJournal(
      operationId: String,
      accountId: String,
      workoutId: String,
      packet: WorkoutChangeSet.Packet,
      receipt: CommandReceipt,
  ) {
    coachDao.saveJournal(
        CoachJournalEntity(
            id = operationId,
            accountId = accountId,
            workoutId = workoutId,
            createdAt = System.currentTimeMillis(),
            payload =
                json.encodeToString(
                    buildJsonObject {
                      put("kind", "command")
                      put("role", "system")
                      put("text", "Изменения тренировки применены")
                      put("result", receipt.result.name)
                      put("revision", receipt.revision)
                      put(
                          "packet",
                          json.encodeToJsonElement(WorkoutChangeSet.Packet.serializer(), packet),
                      )
                    }
                ),
        )
    )
  }

  private fun journalId(seed: String): String =
      UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()

  private fun stale(operationId: String, workoutId: String) =
      CommandReceipt(operationId, workoutId, 0, CommandResult.STALE)

  private data class Applied(
      val receipt: CommandReceipt,
      val rest: WorkoutChangeSet.Operation.Rest?,
  )

  @Serializable
  private data class UndoEntry(val packet: WorkoutChangeSet.Packet, val context: UndoContext?)

  @Serializable
  private data class UndoContext(
      val availableTimeMinutes: Int?,
      val availableTimeEndsAtMillis: Long?,
      val futureRestSeconds: Int?,
      val occupiedEquipmentJson: String,
      val excludedExerciseIdsJson: String,
  )

  private class StaleCommand : IllegalStateException()
}
