package com.valerochka1337.valerochkagym.service

import com.valerochka1337.valerochkagym.data.ai.CoachAgent
import com.valerochka1337.valerochkagym.data.ai.CoachHistoryMessage
import com.valerochka1337.valerochkagym.data.ai.CoachRunStatus
import com.valerochka1337.valerochkagym.data.ai.CoachToolCodec
import com.valerochka1337.valerochkagym.data.ai.CoachToolOutcome
import com.valerochka1337.valerochkagym.data.ai.CoachToolRequest
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.domain.LocalWorkoutCommandParser
import com.valerochka1337.valerochkagym.domain.WorkoutControlService
import com.valerochka1337.valerochkagym.domain.WorkoutSnapshot
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The foreground workout service attaches a scope here. UI only persists/enqueues a user message;
 * model work, cancellation and all subsequent transcript writes belong to that service scope.
 */
@Singleton
class CoachConversationService
@Inject
constructor(
    private val agent: CoachAgent,
    private val control: WorkoutControlService,
    private val database: GymDatabase,
    private val sessions: BackendSessionStore,
) {
  private val json = Json { explicitNulls = false }
  /** One pending request is back-pressured instead of silently dropped during service startup. */
  private val requests = Channel<PendingRequest>(capacity = 1)
  private val running = MutableStateFlow<Set<String>>(emptySet())
  val runningWorkouts: StateFlow<Set<String>> = running
  private val coachAlerts = MutableSharedFlow<String>(extraBufferCapacity = 4)
  val alerts = coachAlerts.asSharedFlow()
  private var consumer: Job? = null
  private var sessionGuard: Job? = null
  private var ownerScope: CoroutineScope? = null

  fun attach(scope: CoroutineScope) {
    consumer?.cancel()
    sessionGuard?.cancel()
    drainQueuedRequests()
    ownerScope = scope
    val attachAt = System.currentTimeMillis()
    val attachedEpoch = sessions.snapshot()?.epoch
    // Subscribe before attach returns so a user message immediately after service attachment is
    // retained by the receiver rather than relying on scheduler timing.
    consumer =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
          database.coachDao().markInterruptedMessages(attachAt)
          requests.receiveAsFlow().collectLatest { request -> runRequest(request) }
        }
    sessionGuard =
        scope.launch {
          sessions.session.collect {
            if (attachedEpoch != null && sessions.snapshot()?.epoch != attachedEpoch)
                consumer?.cancel()
          }
        }
  }

  fun detach() {
    consumer?.cancel()
    sessionGuard?.cancel()
    drainQueuedRequests()
    consumer = null
    sessionGuard = null
    ownerScope = null
  }

  /** Finishing a workout makes an in-flight answer ineligible to write its transcript. */
  fun stopWorkout(workoutId: String) {
    if (workoutId in running.value) consumer?.cancel()
    val pending = requests.tryReceive().getOrNull()
    if (pending != null) {
      if (pending.workoutId == workoutId) markInterrupted(pending) else requests.trySend(pending)
    }
  }

  suspend fun send(workoutId: String, text: String): Boolean {
    if (text.isBlank() || text.length > MAX_USER_MESSAGE_CHARS) return false
    if (consumer?.isActive != true) return false
    val session = sessions.snapshot() ?: return false
    val accountId = session.tokens.userId
    val snapshot = control.snapshot(accountId, workoutId, session.epoch) ?: return false
    val id = UUID.randomUUID().toString()
    if (
        !control.appendMessage(
            id,
            accountId,
            workoutId,
            "user",
            text,
            expectedSessionEpoch = session.epoch,
            status = "PENDING",
        )
    )
        return false
    requests.send(PendingRequest(id, accountId, session.epoch, workoutId, text, snapshot))
    return true
  }

  suspend fun confirm(workoutId: String, proposalId: String): Boolean {
    val session = sessions.snapshot() ?: return false
    val accountId = session.tokens.userId
    val proposal = database.coachDao().pendingProposalForId(proposalId)
    if (proposal?.accountId != accountId || proposal.workoutId != workoutId) return false
    val receipt =
        control.confirmProposal(accountId, proposalId, UUID.randomUUID().toString(), session.epoch)
    val text =
        when (receipt.result) {
          com.valerochka1337.valerochkagym.domain.CommandResult.APPLIED ->
              "Применено:\n${proposal.afterSummary}"
          com.valerochka1337.valerochkagym.domain.CommandResult.REPLAYED ->
              "Предложение уже было применено."
          else -> "Предложение устарело: состояние тренировки изменилось."
        }
    control.appendMessage(
        UUID.randomUUID().toString(),
        accountId,
        workoutId,
        "system",
        text,
        expectedSessionEpoch = session.epoch,
    )
    return receipt.result == com.valerochka1337.valerochkagym.domain.CommandResult.APPLIED
  }

  suspend fun cancel(workoutId: String, proposalId: String): Boolean {
    val session = sessions.snapshot() ?: return false
    val accountId = session.tokens.userId
    val cancelled = control.cancelProposal(accountId, proposalId, session.epoch)
    if (cancelled)
        control.appendMessage(
            UUID.randomUUID().toString(),
            accountId,
            workoutId,
            "system",
            "Предложение отменено.",
            expectedSessionEpoch = session.epoch,
        )
    return cancelled
  }

  suspend fun undo(workoutId: String): Boolean {
    val session = sessions.snapshot() ?: return false
    val accountId = session.tokens.userId
    val snapshot = control.snapshot(accountId, workoutId, session.epoch) ?: return false
    val command =
        LocalWorkoutCommandParser.parse("отмени последнее изменение", snapshot) ?: return false
    return control
        .submitLocal(
            accountId,
            workoutId,
            UUID.randomUUID().toString(),
            snapshot,
            command,
            session.epoch,
        )
        .result == com.valerochka1337.valerochkagym.domain.CommandResult.APPLIED
  }

  suspend fun disableInitiative(workoutId: String): Boolean =
      sessions.snapshot()?.let {
        control.setInitiativeEnabled(it.tokens.userId, workoutId, false, it.epoch)
      } ?: false

  suspend fun considerInitiative(workoutId: String): Boolean {
    val session = sessions.snapshot() ?: return false
    return (control.considerInitiative(session.tokens.userId, workoutId, session.epoch) != null)
        .also { if (it) coachAlerts.emit(workoutId) }
  }

  private suspend fun runRequest(request: PendingRequest) {
    running.value = running.value + request.workoutId
    var interrupted = false
    try {
      if (!isCurrent(request)) {
        interrupted = true
        return
      }
      database.coachDao().setMessageStatus(request.messageId, "PROCESSING")
      val snapshot = request.snapshot
      LocalWorkoutCommandParser.parse(request.text, snapshot)?.let { local ->
        val receipt =
            control.submitLocal(
                request.accountId,
                request.workoutId,
                UUID.randomUUID().toString(),
                snapshot,
                local,
                request.sessionEpoch,
            )
        control.appendMessage(
            UUID.randomUUID().toString(),
            request.accountId,
            request.workoutId,
            "assistant",
            if (receipt.result == com.valerochka1337.valerochkagym.domain.CommandResult.APPLIED)
                "Готово: изменение применено."
            else "Не удалось применить изменение: состояние тренировки изменилось.",
            expectedSessionEpoch = request.sessionEpoch,
        )
        coachAlerts.emit(request.workoutId)
        return
      }
      val history =
          database
              .coachDao()
              .messages(request.workoutId)
              .asSequence()
              .filter { it.id != request.messageId && it.role in setOf("user", "assistant") }
              .map { CoachHistoryMessage(it.role, it.text) }
              .toList()
      val result =
          agent.reply(
              snapshot = snapshot,
              userText = request.text,
              tools = CoachToolCodec.tools,
              history = history,
              expectedSessionEpoch = request.sessionEpoch,
          ) { call ->
            dispatch(request, snapshot, call)
          }
      if (!isCurrent(request)) {
        interrupted = true
        return
      }
      if (
          result.status == CoachRunStatus.ANSWER ||
              result.status == CoachRunStatus.ERROR ||
              result.status == CoachRunStatus.LIMIT
      ) {
        control.appendMessage(
            UUID.randomUUID().toString(),
            request.accountId,
            request.workoutId,
            "assistant",
            result.text,
            expectedSessionEpoch = request.sessionEpoch,
        )
        coachAlerts.emit(request.workoutId)
      }
    } catch (cancellation: CancellationException) {
      interrupted = true
      throw cancellation
    } finally {
      try {
        withContext(NonCancellable) {
          database
              .coachDao()
              .finishPendingMessage(
                  request.messageId,
                  request.accountId,
                  request.workoutId,
                  if (interrupted) "INTERRUPTED" else "DELIVERED",
              )
        }
      } finally {
        running.value = running.value - request.workoutId
      }
    }
  }

  /** Synchronously remove singleton-buffered requests before a later attach can consume them. */
  private fun drainQueuedRequests() {
    while (true) {
      val request = requests.tryReceive().getOrNull() ?: return
      markInterrupted(request)
    }
  }

  private fun markInterrupted(request: PendingRequest) {
    ownerScope?.launch {
      withContext(NonCancellable) {
        database
            .coachDao()
            .finishPendingMessage(
                request.messageId,
                request.accountId,
                request.workoutId,
                "INTERRUPTED",
            )
      }
    }
  }

  private suspend fun dispatch(
      request: PendingRequest,
      snapshot: WorkoutSnapshot,
      call: com.valerochka1337.valerochkagym.data.ai.AiApiToolCall,
  ): CoachToolOutcome =
      try {
        if (!isCurrent(request))
            return CoachToolOutcome(
                "Аккаунт изменился. Не выполняй действие.",
                CoachRunStatus.ERROR,
            )
        when (val decoded = CoachToolCodec.decode(call)) {
          CoachToolRequest.State -> CoachToolOutcome(snapshotJson(snapshot))
          is CoachToolRequest.Find ->
              CoachToolOutcome(
                  findJson(snapshot, decoded.query, decoded.equipmentIds, decoded.muscleIds)
              )
          is CoachToolRequest.History -> CoachToolOutcome(historyJson(decoded.exerciseId))
          is CoachToolRequest.Submit -> {
            val proposal =
                control.saveModelProposal(
                    request.accountId,
                    request.workoutId,
                    decoded.baseRevision,
                    decoded.operations,
                    expiresAt = System.currentTimeMillis() + PROPOSAL_TTL_MILLIS,
                    expectedSessionEpoch = request.sessionEpoch,
                )
            if (proposal == null)
                CoachToolOutcome(
                    "Состояние тренировки изменилось. Получи актуальные данные и предложи изменение снова.",
                    CoachRunStatus.ERROR,
                )
            else {
              coachAlerts.emit(request.workoutId)
              CoachToolOutcome(
                  "Предложение сохранено для подтверждения: ${proposal.afterSummary}",
                  CoachRunStatus.PROPOSAL,
              )
            }
          }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        CoachToolOutcome("Инструмент не принял аргументы. Уточни данные и не выполняй изменение.")
      }

  private fun snapshotJson(snapshot: WorkoutSnapshot): String =
      json.encodeToString(
          buildJsonObject {
            put("workout_id", snapshot.workoutId)
            put("revision", snapshot.revision)
            put("elapsed_seconds", snapshot.elapsedSeconds)
            snapshot.availableTimeMinutes?.let { put("available_time_minutes", it) }
            put("occupied_equipment", stringArray(snapshot.occupiedEquipment))
            put(
                "excluded_exercise_ids",
                buildJsonArray {
                  snapshot.excludedExerciseIds.sorted().forEach { add(JsonPrimitive(it)) }
                },
            )
            put("feelings", stringArray(snapshot.feelings))
            snapshot.pulse?.let { pulse ->
              put(
                  "pulse",
                  buildJsonObject {
                    put("bpm", pulse.bpm)
                    put("measured_at_millis", pulse.measuredAtMillis)
                  },
              )
            }
            snapshot.currentSetId?.let { put("current_set_id", it) }
            snapshot.previousSetId?.let { put("previous_set_id", it) }
            snapshot.nextSetId?.let { put("next_set_id", it) }
            snapshot.rest?.let { rest ->
              put(
                  "rest",
                  buildJsonObject {
                    put("start_id", rest.startId)
                    rest.plannedSeconds?.let { put("planned_seconds", it) }
                    rest.remainingSeconds?.let { put("remaining_seconds", it) }
                  },
              )
            }
            put(
                "exercises",
                buildJsonArray {
                  snapshot.exercises.forEach { exercise ->
                    add(
                        buildJsonObject {
                          put("section_id", exercise.sectionId)
                          put("exercise_id", exercise.exerciseSyncId)
                          put("name", exercise.name)
                          put("position", exercise.position)
                          put("muscles", stringArray(exercise.muscleIds))
                          put("equipment", stringArray(exercise.equipmentIds))
                          put(
                              "sets",
                              buildJsonArray {
                                exercise.sets.forEach { set ->
                                  add(
                                      buildJsonObject {
                                        put("set_id", set.syncId)
                                        put("index", set.setIndex)
                                        put("completed", set.completed)
                                        set.completedAt?.let { put("completed_at", it) }
                                        set.weightKg?.let { put("weight_kg", it) }
                                        set.reps?.let { put("reps", it) }
                                        set.durationSec?.let { put("duration_sec", it) }
                                        set.speedKmh?.let { put("speed_kmh", it) }
                                        set.inclinePct?.let { put("incline_pct", it) }
                                        put("set_type", set.setType)
                                        set.originalWeightKg?.let { put("original_weight_kg", it) }
                                        set.originalReps?.let { put("original_reps", it) }
                                        set.originalDurationSec?.let {
                                          put("original_duration_sec", it)
                                        }
                                        set.originalSpeedKmh?.let { put("original_speed_kmh", it) }
                                        set.originalInclinePct?.let {
                                          put("original_incline_pct", it)
                                        }
                                        set.targetWeightKg?.let { put("target_weight_kg", it) }
                                        set.targetReps?.let { put("target_reps", it) }
                                        set.targetDurationSec?.let {
                                          put("target_duration_sec", it)
                                        }
                                        set.targetSpeedKmh?.let { put("target_speed_kmh", it) }
                                        set.targetInclinePct?.let { put("target_incline_pct", it) }
                                        set.actualWeightKg?.let { put("actual_weight_kg", it) }
                                        set.actualReps?.let { put("actual_reps", it) }
                                        set.actualDurationSec?.let {
                                          put("actual_duration_sec", it)
                                        }
                                        set.actualSpeedKmh?.let { put("actual_speed_kmh", it) }
                                        set.actualInclinePct?.let { put("actual_incline_pct", it) }
                                        put("reported_feelings", stringArray(set.reportedFeelings))
                                      }
                                  )
                                }
                              },
                          )
                          put(
                              "history",
                              buildJsonArray {
                                exercise.history.forEach { row ->
                                  add(
                                      buildJsonObject {
                                        put("completed_at", row.completedAt)
                                        put("set_index", row.setIndex)
                                        row.weightKg?.let { put("weight_kg", it) }
                                        row.reps?.let { put("reps", it) }
                                        row.durationSec?.let { put("duration_sec", it) }
                                        row.speedKmh?.let { put("speed_kmh", it) }
                                        row.inclinePct?.let { put("incline_pct", it) }
                                        put("set_type", row.setType)
                                      }
                                  )
                                }
                              },
                          )
                        }
                    )
                  }
                },
            )
          },
      )

  private fun stringArray(values: Set<String>) = buildJsonArray {
    values.sorted().forEach { add(JsonPrimitive(it)) }
  }

  private suspend fun findJson(
      snapshot: WorkoutSnapshot,
      query: String?,
      equipment: Set<String>?,
      muscles: Set<String>?,
  ): String {
    val normalized = query?.trim()?.lowercase().orEmpty()
    val exercises = mutableListOf<FoundExercise>()
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
        exercises += FoundExercise(exercise.syncId, exercise.name, muscleNames, requirements)
        if (exercises.size == MAX_FOUND_EXERCISES) break
      }
    }
    return json.encodeToString(
        buildJsonObject {
          put(
              "exercises",
              buildJsonArray {
                exercises.forEach { exercise ->
                  add(
                      buildJsonObject {
                        put("exercise_id", exercise.id)
                        put("name", exercise.name)
                        put("muscles", stringArray(exercise.muscles))
                        put("equipment", stringArray(exercise.equipment))
                      }
                  )
                }
              },
          )
        }
    )
  }

  private suspend fun historyJson(exerciseId: String): String {
    val exercise =
        database.exerciseDao().getAllOnce().singleOrNull { it.syncId == exerciseId }
            ?: return json.encodeToString(buildJsonObject { put("history", buildJsonArray {}) })
    val history = database.workoutDao().lastCompletedSetsForExercise(exercise.id).take(30)
    return json.encodeToString(
        buildJsonObject {
          put(
              "history",
              buildJsonArray {
                history.forEach { set ->
                  set.completedAt?.let { at ->
                    add(
                        buildJsonObject {
                          put("completed_at", at)
                          put("set_index", set.setIndex)
                          set.weightKg?.let { put("weight_kg", it) }
                          set.reps?.let { put("reps", it) }
                          set.durationSec?.let { put("duration_sec", it) }
                          set.speedKmh?.let { put("speed_kmh", it) }
                          set.inclinePct?.let { put("incline_pct", it) }
                        }
                    )
                  }
                }
              },
          )
        }
    )
  }

  private fun isCurrent(request: PendingRequest): Boolean =
      sessions.snapshot()?.let {
        it.epoch == request.sessionEpoch && it.tokens.userId == request.accountId
      } == true

  private data class PendingRequest(
      val messageId: String,
      val accountId: String,
      val sessionEpoch: Long,
      val workoutId: String,
      val text: String,
      val snapshot: WorkoutSnapshot,
  )

  private data class FoundExercise(
      val id: String,
      val name: String,
      val muscles: Set<String>,
      val equipment: Set<String>,
  )

  companion object {
    private const val PROPOSAL_TTL_MILLIS = 15 * 60_000L
    private const val MAX_FOUND_EXERCISES = 50
    private const val MAX_USER_MESSAGE_CHARS = 4_000
  }
}
