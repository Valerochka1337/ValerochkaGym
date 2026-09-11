package com.valerochka1337.valerochkagym.service

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.ai.CoachAgent
import com.valerochka1337.valerochkagym.data.ai.CoachHistoryMessage
import com.valerochka1337.valerochkagym.data.ai.CoachRunStatus
import com.valerochka1337.valerochkagym.data.ai.CoachToolCodec
import com.valerochka1337.valerochkagym.data.ai.CoachToolOutcome
import com.valerochka1337.valerochkagym.data.ai.CoachToolRequest
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.CoachJournalEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachMessageEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachSessionContextEntity
import com.valerochka1337.valerochkagym.domain.CoachInitiativeDecision
import com.valerochka1337.valerochkagym.domain.CoachInitiativePolicy
import com.valerochka1337.valerochkagym.domain.CoachInitiativeState
import com.valerochka1337.valerochkagym.domain.CoachPerformanceSet
import com.valerochka1337.valerochkagym.domain.CoachReply
import com.valerochka1337.valerochkagym.domain.CoachWorkoutReader
import com.valerochka1337.valerochkagym.domain.CommandAuthority
import com.valerochka1337.valerochkagym.domain.LocalWorkoutCommandParser
import com.valerochka1337.valerochkagym.domain.ModelProposalSaveResult
import com.valerochka1337.valerochkagym.domain.WorkoutEditor
import com.valerochka1337.valerochkagym.domain.WorkoutSnapshot
import com.valerochka1337.valerochkagym.domain.WorkoutWriteQueue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
    private val reader: CoachWorkoutReader,
    private val editor: WorkoutEditor,
    private val database: GymDatabase,
    private val sessions: BackendSessionStore,
    private val writes: WorkoutWriteQueue = WorkoutWriteQueue(),
) {
  private val json = Json { explicitNulls = false }
  /** One pending request is back-pressured instead of silently dropped during service startup. */
  private val requests = Channel<PendingRequest>(capacity = 1)
  private val running = MutableStateFlow<Set<String>>(emptySet())
  val runningWorkouts: StateFlow<Set<String>> = running
  private val stages = MutableStateFlow<Map<String, String>>(emptyMap())
  /** Ephemeral detail of the currently running request; Room remains the transcript source. */
  val runningStages: StateFlow<Map<String, String>> = stages
  private val coachAlerts = MutableSharedFlow<String>(extraBufferCapacity = 4)
  val alerts = coachAlerts.asSharedFlow()
  private val lifecycle = Any()
  @Volatile private var generation = 0L
  private val latestRequests = ConcurrentHashMap<String, String>()
  private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()
  private val stoppedWorkouts = ConcurrentHashMap.newKeySet<String>()
  private var consumer: Job? = null
  private var sessionGuard: Job? = null
  private var ownerScope: CoroutineScope? = null

  fun attach(scope: CoroutineScope) {
    val interrupted =
        synchronized(lifecycle) {
          generation++
          stoppedWorkouts.clear()
          latestRequests.clear()
          pendingRequests.values.toList()
        }
    interrupted.forEach(::markInterrupted)
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
            if (attachedEpoch != null && sessions.snapshot()?.epoch != attachedEpoch) detach()
          }
        }
  }

  fun detach() {
    val interrupted =
        synchronized(lifecycle) {
          generation++
          latestRequests.clear()
          pendingRequests.values.toList()
        }
    interrupted.forEach(::markInterrupted)
    consumer?.cancel()
    sessionGuard?.cancel()
    drainQueuedRequests()
    consumer = null
    sessionGuard = null
    ownerScope = null
  }

  /** Finishing a workout makes an in-flight answer ineligible to write its transcript. */
  fun stopWorkout(workoutId: String) {
    val interrupted =
        synchronized(lifecycle) {
          stoppedWorkouts.add(workoutId)
          latestRequests.remove(workoutId)
          pendingRequests.values.filter { it.workoutId == workoutId }
        }
    interrupted.forEach(::markInterrupted)
    if (workoutId in running.value) consumer?.cancel()
    val pending = requests.tryReceive().getOrNull()
    if (pending != null) {
      if (pending.workoutId == workoutId) markInterrupted(pending) else requests.trySend(pending)
    }
  }

  suspend fun send(workoutId: String, text: String): Boolean {
    if (text.isBlank() || text.length > MAX_USER_MESSAGE_CHARS || workoutId in stoppedWorkouts)
        return false
    if (consumer?.isActive != true) return false
    val requestScope = ownerScope ?: return false
    val sentGeneration = generation
    val session = sessions.snapshot() ?: return false
    val accountId = session.tokens.userId
    val snapshot = reader.snapshot(accountId, workoutId, session.epoch) ?: return false
    val id = UUID.randomUUID().toString()
    if (
        !appendMessage(
            id,
            accountId,
            workoutId,
            "user",
            text,
            expectedSessionEpoch = session.epoch,
            status = "PENDING",
            isCurrent = {
              generation == sentGeneration &&
                  consumer?.isActive == true &&
                  workoutId !in stoppedWorkouts
            },
        )
    )
        return false
    val request =
        PendingRequest(
            id,
            accountId,
            session.epoch,
            workoutId,
            text,
            snapshot,
            sentGeneration,
            requestScope,
        )
    return enqueue(request)
  }

  /**
   * Retry the original turn without inserting a second user message or replaying local commands.
   */
  suspend fun retry(workoutId: String, errorMessageId: String): Boolean {
    if (consumer?.isActive != true || workoutId in stoppedWorkouts) return false
    val requestScope = ownerScope ?: return false
    val sentGeneration = generation
    val session = sessions.snapshot() ?: return false
    val accountId = session.tokens.userId
    val snapshot = reader.snapshot(accountId, workoutId, session.epoch) ?: return false
    val request =
        writes.write {
          database.withTransaction {
            if (
                generation != sentGeneration ||
                    consumer?.isActive != true ||
                    workoutId in stoppedWorkouts ||
                    !belongsToLiveAccount(accountId, session.epoch) ||
                    workoutId in running.value ||
                    pendingRequests.values.any { it.workoutId == workoutId }
            )
                return@withTransaction null
            val workout = database.workoutDao().getWorkoutFull(workoutId)?.workout
            if (
                workout == null ||
                    workout.finishedAt != null ||
                    database.coachDao().pendingProposal(workoutId) != null
            )
                return@withTransaction null
            val messages = database.coachDao().messages(workoutId)
            val failure = messages.lastOrNull() ?: return@withTransaction null
            if (
                failure.id != errorMessageId ||
                    failure.accountId != accountId ||
                    failure.role != "assistant" ||
                    (failure.status != "ERROR" &&
                        !failure.text.startsWith("Не удалось обработать запрос тренера."))
            )
                return@withTransaction null
            val original =
                messages.dropLast(1).lastOrNull { it.role == "user" } ?: return@withTransaction null
            if (
                original.accountId != accountId || original.status in setOf("PENDING", "PROCESSING")
            )
                return@withTransaction null
            database.coachDao().setMessageStatus(original.id, "PENDING")
            PendingRequest(
                original.id,
                accountId,
                session.epoch,
                workoutId,
                original.text,
                snapshot,
                sentGeneration,
                requestScope,
                retry = true,
            )
          }
        } ?: return false
    return enqueue(request)
  }

  private suspend fun enqueue(request: PendingRequest): Boolean {
    val accepted =
        synchronized(lifecycle) {
          if (
              request.generation != generation ||
                  consumer?.isActive != true ||
                  request.workoutId in stoppedWorkouts
          )
              false
          else {
            latestRequests[request.workoutId] = request.messageId
            pendingRequests[request.messageId] = request
            true
          }
        }
    if (!accepted) {
      markInterrupted(request)
      return false
    }
    setStage(request.workoutId, "Отправляем сообщение…")
    try {
      requests.send(request)
    } catch (error: CancellationException) {
      markInterrupted(request)
      throw error
    }
    return true
  }

  suspend fun confirm(workoutId: String, proposalId: String): Boolean {
    val session = sessions.snapshot() ?: return false
    val accountId = session.tokens.userId
    val proposal = database.coachDao().pendingProposalForId(proposalId)
    if (proposal?.accountId != accountId || proposal.workoutId != workoutId) return false
    val actionKind =
        com.valerochka1337.valerochkagym.domain.WorkoutApprovalPreview.decode(proposal.previewJson)
            ?.actions
            ?.firstOrNull()
            ?.kind ?: "change"
    val receipt =
        editor.confirmProposal(accountId, proposalId, UUID.randomUUID().toString(), session.epoch)
    val text =
        when (receipt.result) {
          com.valerochka1337.valerochkagym.domain.CommandResult.APPLIED ->
              "APPLIED|$actionKind|${proposal.afterSummary}"
          com.valerochka1337.valerochkagym.domain.CommandResult.REPLAYED ->
              "Предложение уже было применено."
          else -> "Предложение устарело: состояние тренировки изменилось."
        }
    appendMessage(
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
    val proposal = database.coachDao().pendingProposalForId(proposalId)
    if (proposal?.accountId != accountId || proposal.workoutId != workoutId) return false
    val actionKind =
        com.valerochka1337.valerochkagym.domain.WorkoutApprovalPreview.decode(proposal.previewJson)
            ?.actions
            ?.firstOrNull()
            ?.kind ?: "change"
    val cancelled = editor.cancelProposal(accountId, proposalId, session.epoch)
    if (cancelled)
        appendMessage(
            UUID.randomUUID().toString(),
            accountId,
            workoutId,
            "system",
            "REJECTED|$actionKind|${proposal.afterSummary}",
            expectedSessionEpoch = session.epoch,
        )
    return cancelled
  }

  suspend fun undo(workoutId: String): Boolean {
    val session = sessions.snapshot() ?: return false
    val accountId = session.tokens.userId
    val snapshot = reader.snapshot(accountId, workoutId, session.epoch) ?: return false
    val command =
        LocalWorkoutCommandParser.parse("отмени последнее изменение", snapshot) ?: return false
    return editor
        .submit(
            accountId,
            workoutId,
            UUID.randomUUID().toString(),
            snapshot.revision,
            command.packet,
            command.authority,
            CommandAuthority.Anchors(
                snapshot.currentSetId,
                snapshot.previousSetId,
                snapshot.nextSetId,
            ),
            session.epoch,
        )
        .result == com.valerochka1337.valerochkagym.domain.CommandResult.APPLIED
  }

  suspend fun disableInitiative(workoutId: String): Boolean =
      sessions.snapshot()?.let {
        setInitiativeEnabled(it.tokens.userId, workoutId, false, it.epoch)
      } ?: false

  suspend fun considerInitiative(workoutId: String): Boolean {
    val session = sessions.snapshot() ?: return false
    return (considerInitiative(session.tokens.userId, workoutId, session.epoch) != null).also {
      if (it) coachAlerts.emit(workoutId)
    }
  }

  private suspend fun runRequest(request: PendingRequest) {
    running.value = running.value + request.workoutId
    setStage(request.workoutId, "Проверяем тренировку…")
    var interrupted = false
    try {
      if (!isCurrent(request)) {
        interrupted = true
        return
      }
      database.coachDao().setMessageStatus(request.messageId, "PROCESSING")
      val snapshot = request.snapshot
      (if (request.retry) null else LocalWorkoutCommandParser.parse(request.text, snapshot))?.let {
          local ->
        val receipt =
            editor.submit(
                request.accountId,
                request.workoutId,
                UUID.randomUUID().toString(),
                snapshot.revision,
                local.packet,
                local.authority,
                CommandAuthority.Anchors(
                    snapshot.currentSetId,
                    snapshot.previousSetId,
                    snapshot.nextSetId,
                ),
                request.sessionEpoch,
                isCurrent = { isCurrent(request) },
            )
        appendMessage(
            UUID.randomUUID().toString(),
            request.accountId,
            request.workoutId,
            "assistant",
            if (receipt.result == com.valerochka1337.valerochkagym.domain.CommandResult.APPLIED)
                "Готово: изменение применено."
            else "Не удалось применить изменение: состояние тренировки изменилось.",
            expectedSessionEpoch = request.sessionEpoch,
            isCurrent = { isCurrent(request) },
        )
        coachAlerts.emit(request.workoutId)
        return
      }
      val history =
          database
              .coachDao()
              .messages(request.workoutId)
              .asSequence()
              .takeWhile { it.id != request.messageId }
              .filter { it.role in setOf("user", "assistant") && it.status != "ERROR" }
              .map { CoachHistoryMessage(it.role, it.text) }
              .toList()
      setStage(request.workoutId, "Формируем ответ…")
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
        appendMessage(
            UUID.randomUUID().toString(),
            request.accountId,
            request.workoutId,
            "assistant",
            result.text,
            quickRepliesJson = CoachReply.encodeQuickReplies(result.quickReplies),
            status = if (result.status == CoachRunStatus.ERROR) "ERROR" else "DELIVERED",
            expectedSessionEpoch = request.sessionEpoch,
            isCurrent = { isCurrent(request) },
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
        pendingRequests.remove(request.messageId)
        running.value = running.value - request.workoutId
        stages.value = stages.value - request.workoutId
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
    pendingRequests.remove(request.messageId)
    request.scope.launch(start = CoroutineStart.UNDISPATCHED) {
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
        setStage(request.workoutId, "Проверяем данные тренировки…")
        if (!isCurrent(request))
            return CoachToolOutcome(
                "Аккаунт изменился. Не выполняй действие.",
                CoachRunStatus.ERROR,
            )
        when (val decoded = CoachToolCodec.decode(call)) {
          CoachToolRequest.State -> {
            setStage(request.workoutId, "Проверяем текущий подход…")
            val fresh = freshSnapshot(request)
            if (fresh == null)
                CoachToolOutcome(
                    "Тренировка больше недоступна для изменений.",
                    CoachRunStatus.ERROR,
                )
            else CoachToolOutcome(CoachToolCodec.snapshotJson(fresh))
          }
          is CoachToolRequest.Find ->
              setStage(request.workoutId, "Подбираем упражнение…").let {
                CoachToolOutcome(
                    CoachToolCodec.foundJson(
                        reader.find(
                            snapshot,
                            decoded.query,
                            decoded.equipmentIds,
                            decoded.muscleIds,
                        )
                    )
                )
              }
          is CoachToolRequest.History ->
              setStage(request.workoutId, "Сверяем историю…").let {
                CoachToolOutcome(CoachToolCodec.historyJson(reader.history(decoded.exerciseId)))
              }
          is CoachToolRequest.Submit -> {
            setStage(request.workoutId, "Готовим изменения…")
            when (
                val result =
                    editor.saveModelProposalResult(
                        request.accountId,
                        request.workoutId,
                        decoded.baseRevision,
                        decoded.operations,
                        expiresAt = System.currentTimeMillis() + PROPOSAL_TTL_MILLIS,
                        expectedSessionEpoch = request.sessionEpoch,
                        isCurrent = { isCurrent(request) },
                    )
            ) {
              is ModelProposalSaveResult.Saved -> {
                coachAlerts.emit(request.workoutId)
                CoachToolOutcome(
                    "Предложение сохранено для подтверждения: ${result.proposal.afterSummary}",
                    CoachRunStatus.PROPOSAL,
                )
              }
              ModelProposalSaveResult.Stale,
              ModelProposalSaveResult.InvalidOrder -> {
                val fresh = freshSnapshot(request)
                if (fresh == null)
                    CoachToolOutcome(
                        "Тренировка больше недоступна для изменений.",
                        CoachRunStatus.ERROR,
                    )
                else
                    CoachToolOutcome(
                        recoveryJson(
                            fresh,
                            invalidOrder = result == ModelProposalSaveResult.InvalidOrder,
                        ),
                        kind =
                            com.valerochka1337.valerochkagym.data.ai.CoachToolOutcomeKind.RECOVERY,
                    )
              }
              ModelProposalSaveResult.Invalid ->
                  CoachToolOutcome(
                      "Не удалось подготовить предложенное изменение. Уточните упражнение или параметры.",
                      CoachRunStatus.ERROR,
                  )
              ModelProposalSaveResult.Unavailable ->
                  CoachToolOutcome(
                      "Тренировка больше недоступна для изменений.",
                      CoachRunStatus.ERROR,
                  )
            }
          }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        CoachToolOutcome(
            "Не удалось обработать запрос",
            CoachRunStatus.ERROR,
        )
      }

  private suspend fun freshSnapshot(request: PendingRequest): WorkoutSnapshot? {
    if (!isCurrent(request) || !belongsToLiveAccount(request.accountId, request.sessionEpoch)) {
      return null
    }
    val snapshot =
        reader.snapshot(request.accountId, request.workoutId, request.sessionEpoch) ?: return null
    return snapshot.takeIf {
      isCurrent(request) && belongsToLiveAccount(request.accountId, request.sessionEpoch)
    }
  }

  private fun recoveryJson(snapshot: WorkoutSnapshot, invalidOrder: Boolean): String =
      buildJsonObject {
            put("error", if (invalidOrder) "invalid_exercise_order" else "revision_conflict")
            put(
                "instruction",
                if (invalidOrder)
                    "Пакет не сохранён и не применён. reorder_exercises должен содержать каждый section_id из current_state ровно один раз, включая полностью выполненные упражнения и разминку. Исправь полный порядок, сохрани место выполненной разминки и используй revision из current_state."
                else "Создай новое предложение только по current_state с новой revision.",
            )
            put("current_state", Json.parseToJsonElement(CoachToolCodec.snapshotJson(snapshot)))
          }
          .toString()

  suspend fun setInitiativeEnabled(
      accountId: String,
      workoutId: String,
      enabled: Boolean,
      expectedSessionEpoch: Long? = null,
  ): Boolean {
    return writes.write {
      database.withTransaction {
        if (!belongsToLiveAccount(accountId, expectedSessionEpoch)) return@withTransaction false
        val active = database.workoutDao().getWorkoutFull(workoutId)?.workout
        if (active?.finishedAt != null || active == null) return@withTransaction false
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

  suspend fun appendMessage(
      id: String,
      accountId: String,
      workoutId: String,
      role: String,
      text: String,
      createdAt: Long = System.currentTimeMillis(),
      expectedSessionEpoch: Long? = null,
      status: String = "DELIVERED",
      quickRepliesJson: String? = null,
      isCurrent: () -> Boolean = { true },
  ): Boolean {
    return writes.write {
      database.withTransaction {
        if (!belongsToLiveAccount(accountId, expectedSessionEpoch)) return@withTransaction false
        val workout = database.workoutDao().getWorkoutFull(workoutId)?.workout
        if (workout?.finishedAt != null || workout == null) return@withTransaction false
        val context = database.coachDao().context(workoutId)
        if (context != null && context.accountId != accountId) return@withTransaction false
        val currentContext = context ?: CoachSessionContextEntity(workoutId, accountId)
        if (!isCurrent() || !belongsToLiveAccount(accountId, expectedSessionEpoch))
            return@withTransaction false
        // A textual follow-up replaces the unanswered proposal in the same transaction as the
        // message.
        if (role == "user") database.coachDao().supersedePendingProposals(accountId, workoutId)
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
                CoachMessageEntity(
                    id,
                    accountId,
                    workoutId,
                    role,
                    text,
                    createdAt,
                    status,
                    quickRepliesJson,
                    readAt = if (role == "assistant") null else null,
                )
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
        check(isCurrent() && belongsToLiveAccount(accountId, expectedSessionEpoch)) {
          "Request changed"
        }
        true
      }
    }
  }

  private fun setStage(workoutId: String, stage: String) {
    stages.value = stages.value + (workoutId to stage)
  }

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
        val active = database.workoutDao().getWorkoutFull(workoutId)?.workout
        if (
            active?.finishedAt != null ||
                active == null ||
                active.coachRevision != full.workout.coachRevision
        )
            return@withTransaction null
        val latest =
            database.coachDao().context(workoutId)
                ?: CoachSessionContextEntity(workoutId, accountId)
        if (!belongsToLiveAccount(accountId, expectedSessionEpoch) || latest != context)
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

  private fun isCurrent(request: PendingRequest): Boolean =
      sessions.snapshot()?.let {
        generation == request.generation &&
            request.workoutId !in stoppedWorkouts &&
            latestRequests[request.workoutId] == request.messageId &&
            it.epoch == request.sessionEpoch &&
            it.tokens.userId == request.accountId
      } == true

  private data class PendingRequest(
      val messageId: String,
      val accountId: String,
      val sessionEpoch: Long,
      val workoutId: String,
      val text: String,
      val snapshot: WorkoutSnapshot,
      val generation: Long,
      val scope: CoroutineScope,
      val retry: Boolean = false,
  )

  companion object {
    private const val PROPOSAL_TTL_MILLIS = 15 * 60_000L
    private const val MAX_USER_MESSAGE_CHARS = 4_000
  }
}
