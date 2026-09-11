package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.db.entity.CoachSessionContextEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import com.valerochka1337.valerochkagym.service.WallClock
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateConnectionState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateDevice
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateMonitor
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkoutMutationCoordinatorTest : RoomDaoTest() {
  @Test
  fun `invalid later operation rolls back the entire packet and does not start rest`() = runTest {
    val workoutId = insertWorkout("workout")
    val sectionId = insertWorkoutExercise(workoutId, exerciseId = exercise())
    val setId = insertSet(sectionId, 0, reps = 8, isCompleted = true)
    val before = db.workoutDao().getSet(setId)!!
    val timer = RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime })
    val coordinator = coordinator(timer)
    val packet =
        WorkoutChangeSet.Packet(
            listOf(
                WorkoutChangeSet.Operation.Rest(RestAction.START, null, 60),
                WorkoutChangeSet.Operation.EditSet(before.syncId, reps = 10),
                WorkoutChangeSet.Operation.DeleteSet(before.syncId),
            )
        )

    val receipt = coordinator.submit("user", workoutId, "operation", 0, packet, authority(packet))

    assertEquals(CommandResult.INVALID, receipt.result)
    assertEquals(8, db.workoutDao().getSet(setId)!!.reps)
    assertEquals(0, workoutFull(workoutId).workout.coachRevision)
    assertNull(timer.state.value)
  }

  @Test
  fun `set values reject fields outside the exercise type before changing the packet`() = runTest {
    val workoutId = insertWorkout("workout")
    val timedSection = insertWorkoutExercise(workoutId, exercise(type = ExerciseType.TIMED))
    val timedId = insertSet(timedSection, 0, durationSec = 60)
    val timed = db.workoutDao().getSet(timedId)!!
    val foreign =
        WorkoutChangeSet.Packet(
            listOf(WorkoutChangeSet.Operation.EditSet(timed.syncId, weightKg = 30.0))
        )

    assertEquals(
        CommandResult.INVALID,
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
            .submit("user", workoutId, "foreign", 0, foreign, authority(foreign))
            .result,
    )
    assertNull(db.workoutDao().getSet(timedId)!!.weightKg)
    assertEquals(60, db.workoutDao().getSet(timedId)!!.durationSec)

    val cardioSection = insertWorkoutExercise(workoutId, exercise(type = ExerciseType.CARDIO))
    val cardioId = insertSet(cardioSection, 0, durationSec = 60, speedKmh = 8.0)
    val cardio = db.workoutDao().getSet(cardioId)!!
    val invalid =
        WorkoutChangeSet.Packet(
            listOf(WorkoutChangeSet.Operation.EditSet(cardio.syncId, speedKmh = -1.0))
        )

    assertEquals(
        CommandResult.INVALID,
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
            .submit("user", workoutId, "negative", 0, invalid, authority(invalid))
            .result,
    )
    assertEquals(8.0, db.workoutDao().getSet(cardioId)!!.speedKmh!!, 0.0)
  }

  @Test
  fun `oversized proposal journal rolls back its durable proposal without changing the workout`() =
      runTest {
        val workoutId = insertWorkout("workout")
        val coordinator =
            coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
        val packet =
            WorkoutChangeSet.Packet(
                listOf(WorkoutChangeSet.Operation.SetOccupiedEquipment(setOf("rack")))
            )

        try {
          coordinator.saveProposal(
              accountId = "user",
              workoutId = workoutId,
              packet = packet,
              expectedRevision = 0,
              beforeSummary = "x".repeat(70_000),
              afterSummary = "Станет",
              expiresAt = Long.MAX_VALUE,
          )
          fail("Expected the journal byte limit to reject the proposal")
        } catch (_: IllegalArgumentException) {
          // The entity guard runs inside the Room transaction.
        }

        assertNull(db.coachDao().pendingProposal(workoutId))
        assertEquals(0L, workoutFull(workoutId).workout.coachRevision)
        assertNull(db.coachDao().receipt("proposal"))
      }

  @Test
  fun `receipt replay is idempotent and undo restores the prior workout snapshot`() = runTest {
    val workoutId = insertWorkout("workout")
    val section = insertWorkoutExercise(workoutId, exercise())
    insertSet(section, 0, reps = 8)
    val coordinator =
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
    val sectionId = db.workoutDao().getWorkoutExercises(workoutId).single().sectionId
    val add = WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.AddSet(sectionId)))

    assertEquals(
        CommandResult.APPLIED,
        coordinator.submit("user", workoutId, "add", 0, add, authority(add)).result,
    )
    assertEquals(2, workoutFull(workoutId).exercises.single().sets.size)
    val added = workoutFull(workoutId).exercises.single().sets.maxBy { it.setIndex }
    assertEquals(8, added.originalReps)
    assertEquals(8, added.targetReps)
    assertEquals(1, added.coachMutationRevision)
    assertEquals(
        CommandResult.REPLAYED,
        coordinator.submit("user", workoutId, "add", 0, add, authority(add)).result,
    )
    assertEquals(1, workoutFull(workoutId).workout.coachRevision)

    val undo = WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.UndoLast))
    assertEquals(
        CommandResult.APPLIED,
        coordinator.submit("user", workoutId, "undo", 1, undo, authority(undo)).result,
    )
    assertEquals(1, workoutFull(workoutId).exercises.single().sets.size)
    assertEquals(2, workoutFull(workoutId).workout.coachRevision)
  }

  @Test
  fun `concurrent packets at the same revision apply exactly once and stale the other`() = runTest {
    val workoutId = insertWorkout("workout")
    val section = insertWorkoutExercise(workoutId, exercise())
    val set = db.workoutDao().getSet(insertSet(section, 0, reps = 8))!!
    val coordinator =
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
    val first =
        WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.EditSet(set.syncId, reps = 9)))
    val second =
        WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.EditSet(set.syncId, reps = 10)))

    val results = coroutineScope {
      listOf(
              async {
                coordinator.submit("user", workoutId, "first", 0, first, authority(first)).result
              },
              async {
                coordinator.submit("user", workoutId, "second", 0, second, authority(second)).result
              },
          )
          .awaitAll()
    }

    assertEquals(setOf(CommandResult.APPLIED, CommandResult.STALE), results.toSet())
    assertEquals(1L, workoutFull(workoutId).workout.coachRevision)
    assertTrue(db.workoutDao().getSet(set.id)!!.reps in setOf(9, 10))
  }

  @Test
  fun `a note saved after a command makes its undo stale and keeps the note`() = runTest {
    val workoutId = insertWorkout("workout")
    val section = insertWorkoutExercise(workoutId, exercise())
    val setId = insertSet(section, 0, reps = 8)
    val coordinator =
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
    val set = db.workoutDao().getSet(setId)!!
    val change =
        WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.EditSet(set.syncId, reps = 9)))
    assertEquals(
        CommandResult.APPLIED,
        coordinator.submit("user", workoutId, "edit", 0, change, authority(change)).result,
    )

    val updated = db.workoutDao().getSet(setId)!!
    db.workoutDao().updateSet(updated.copy(note = "локальная заметка"))
    db.openHelper.writableDatabase.execSQL(
        "UPDATE workouts SET coachRevision = coachRevision + 1 WHERE id=?",
        arrayOf<Any?>(workoutId),
    )

    val undo = WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.UndoLast))
    assertEquals(
        CommandResult.STALE,
        coordinator.submit("user", workoutId, "undo", 2, undo, authority(undo)).result,
    )
    assertEquals("локальная заметка", db.workoutDao().getSet(setId)!!.note)
  }

  @Test
  fun `confirming a proposal after an independent write is stale and does not apply it`() =
      runTest {
        val workoutId = insertWorkout("workout")
        val section = insertWorkoutExercise(workoutId, exercise())
        val set = db.workoutDao().getSet(insertSet(section, 0, reps = 8))!!
        val timer = RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime })
        val coordinator = coordinator(timer)
        val proposed =
            WorkoutChangeSet.Packet(
                listOf(WorkoutChangeSet.Operation.EditSet(set.syncId, reps = 6))
            )
        val proposal =
            requireNotNull(
                coordinator.saveProposal(
                    "user",
                    workoutId,
                    proposed,
                    0,
                    "Было",
                    "Станет",
                    Long.MAX_VALUE,
                ),
            )
        val independent =
            WorkoutChangeSet.Packet(
                listOf(WorkoutChangeSet.Operation.EditSet(set.syncId, reps = 9))
            )

        assertEquals(
            CommandResult.APPLIED,
            coordinator
                .submit("user", workoutId, "screen", 0, independent, authority(independent))
                .result,
        )
        assertEquals(
            CommandResult.STALE,
            coordinator.confirmProposal("user", proposal.id, "confirm").result,
        )

        assertEquals(9, db.workoutDao().getSet(set.id)!!.reps)
        assertEquals(1L, workoutFull(workoutId).workout.coachRevision)
        assertNull(timer.state.value)
        db.openHelper.writableDatabase
            .query("SELECT state FROM coach_proposals WHERE id=?", arrayOf<Any>(proposal.id))
            .use {
              assertTrue(it.moveToFirst())
              assertEquals("STALE", it.getString(0))
            }
      }

  @Test
  fun `undo restores reversible coach context instead of reporting a no-op`() = runTest {
    val workoutId = insertWorkout("workout")
    val coordinator =
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
    val change =
        WorkoutChangeSet.Packet(
            listOf(WorkoutChangeSet.Operation.SetOccupiedEquipment(setOf("rack"))),
        )
    assertEquals(
        CommandResult.APPLIED,
        coordinator.submit("user", workoutId, "occupy", 0, change, authority(change)).result,
    )
    assertEquals("[\"rack\"]", db.coachDao().context(workoutId)!!.occupiedEquipmentJson)

    val undo = WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.UndoLast))
    assertEquals(
        CommandResult.APPLIED,
        coordinator.submit("user", workoutId, "undo-context", 1, undo, authority(undo)).result,
    )
    assertEquals("[]", db.coachDao().context(workoutId)!!.occupiedEquipmentJson)
  }

  @Test
  fun `missing workout and foreign account are stale without foreign key receipts`() = runTest {
    val coordinator =
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
    val packet = WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.UndoLast))

    assertEquals(
        CommandResult.STALE,
        coordinator.submit("user", "gone", "missing", 0, packet, authority(packet)).result,
    )
    assertEquals(0, tableCount("coach_command_receipts"))

    session.save(BackendTokens("other", "other@example.com", "access", "refresh"))
    assertEquals(
        CommandResult.STALE,
        coordinator.submit("user", "gone", "foreign", 0, packet, authority(packet)).result,
    )
    assertEquals(0, tableCount("coach_command_receipts"))
  }

  @Test
  fun `replacement uses a resolved history weight and creates a distinct section`() = runTest {
    val oldExercise = exercise("Old")
    val replacement = exercise("New")
    val history = insertWorkout("history", finishedAt = 2_000)
    val historySection = insertWorkoutExercise(history, replacement)
    insertSet(historySection, 0, weightKg = 42.5, isCompleted = true)
    val workout = insertWorkout("workout")
    val source = insertWorkoutExercise(workout, oldExercise)
    insertSet(source, 0, weightKg = null, reps = 8, isCompleted = true)
    val unfinishedId = insertSet(source, 1, weightKg = null, reps = 8)
    val sourceRow = db.workoutDao().getWorkoutExercises(workout).single()
    val unfinished = db.workoutDao().getSet(unfinishedId)!!
    val coordinator =
        coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
    val packet =
        WorkoutChangeSet.Packet(
            listOf(
                WorkoutChangeSet.Operation.ReplaceRemaining(
                    sourceRow.sectionId,
                    "c2fd43d1-0603-4a80-95d9-81686e6d5eaf",
                    replacement,
                    listOf(unfinished.syncId),
                    42.5,
                )
            )
        )

    assertEquals(
        CommandResult.APPLIED,
        coordinator.submit("user", workout, "replace", 0, packet, authority(packet)).result,
    )
    val sections = workoutFull(workout).exercises
    assertEquals(2, sections.size)
    assertTrue(
        sections.single { it.workoutExercise.exerciseId == oldExercise }.sets.single().isCompleted
    )
    val moved = sections.single { it.workoutExercise.exerciseId == replacement }.sets.single()
    assertEquals(42.5, moved.weightKg!!, 0.0)
    assertFalse(moved.isCompleted)
  }

  @Test
  fun `replacement permits an explicitly weightless cardio exercise without inventing a load`() =
      runTest {
        val sourceExercise = exercise("Source")
        val cardio =
            db.exerciseDao()
                .insert(
                    ExerciseEntity(
                        name = "Кардио",
                        muscleGroup = MuscleGroup.CARDIO,
                        type = ExerciseType.CARDIO,
                    ),
                )
        val workout = insertWorkout("workout")
        val source = insertWorkoutExercise(workout, sourceExercise)
        val remainingId = insertSet(source, 0, reps = 8, durationSec = 300)
        val sourceRow = db.workoutDao().getWorkoutExercises(workout).single()
        val remaining = db.workoutDao().getSet(remainingId)!!
        val coordinator =
            coordinator(RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime }))
        val packet =
            WorkoutChangeSet.Packet(
                listOf(
                    WorkoutChangeSet.Operation.ReplaceRemaining(
                        sourceRow.sectionId,
                        "b917e99d-6b03-4e16-96d8-a2f4a7599878",
                        cardio,
                        listOf(remaining.syncId),
                        null,
                    )
                )
            )

        assertEquals(
            CommandResult.APPLIED,
            coordinator.submit("user", workout, "cardio", 0, packet, authority(packet)).result,
        )
        val moved =
            workoutFull(workout)
                .exercises
                .single { it.workoutExercise.exerciseId == cardio }
                .sets
                .single()
        assertNull(moved.weightKg)
        assertNull(moved.reps)
        assertNull(moved.durationSec)
        assertNull(moved.targetReps)
        assertNull(moved.targetDurationSec)
      }

  @Test
  fun `snapshot exposes portable identities planned actual values context and rest`() = runTest {
    val workout = insertWorkout("workout", startedAt = 1_000)
    val exerciseId = exercise("Snapshot")
    val section = insertWorkoutExercise(workout, exerciseId)
    val setId = insertSet(section, 0, weightKg = 50.0, reps = 8, speedKmh = 5.0)
    val original = db.workoutDao().getSet(setId)!!
    db.workoutDao()
        .updateSet(
            original.copy(
                originalWeightKg = 45.0,
                targetWeightKg = 50.0,
                actualWeightKg = 47.5,
                reportedFeelingsJson = "[\"FATIGUE\"]",
            )
        )
    db.coachDao()
        .saveContext(
            CoachSessionContextEntity(
                workout,
                "user",
                availableTimeMinutes = 35,
                futureRestSeconds = 90,
                occupiedEquipmentJson = "[\"rack\"]",
            )
        )
    val timer = RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime })
    timer.start(60)
    assertNotNull(timer.currentStartId())
    assertNotNull(timer.state.value)
    val service = WorkoutControlService(db, coordinator(timer), timer, session)

    val snapshot = service.snapshot("user", workout)!!

    assertEquals(35, snapshot.availableTimeMinutes)
    assertEquals(setOf("rack"), snapshot.occupiedEquipment)
    assertEquals(setOf("FATIGUE"), snapshot.feelings)
    assertEquals(original.syncId, snapshot.exercises.single().sets.single().syncId)
    assertEquals(45.0, snapshot.exercises.single().sets.single().originalWeightKg!!, 0.0)
    assertEquals(47.5, snapshot.exercises.single().sets.single().actualWeightKg!!, 0.0)
    assertTrue(snapshot.rest!!.remainingSeconds in 0..60)
  }

  @Test
  fun `snapshot uses the latest completion as previous anchor and carries a fresh pulse timestamp`() =
      runTest {
        val workout = insertWorkout("workout")
        val section = insertWorkoutExercise(workout, exercise())
        val latestCompletedId = insertSet(section, 0, reps = 8, isCompleted = true)
        val earlierCompletedId = insertSet(section, 1, reps = 8, isCompleted = true)
        val currentId = insertSet(section, 2, reps = 8)
        val latest = db.workoutDao().getSet(latestCompletedId)!!
        db.workoutDao().updateSet(latest.copy(completedAt = 300L))
        val earlier = db.workoutDao().getSet(earlierCompletedId)!!
        db.workoutDao().updateSet(earlier.copy(completedAt = 100L))
        val pulseAt = System.currentTimeMillis()
        val timer = RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime })
        val service =
            WorkoutControlService(
                db,
                coordinator(timer),
                timer,
                session,
                FakeHeartRateMonitor(HeartRateReading(132, pulseAt)),
            )

        val snapshot = service.snapshot("user", workout)!!

        assertEquals(latest.syncId, snapshot.previousSetId)
        assertEquals(db.workoutDao().getSet(currentId)!!.syncId, snapshot.currentSetId)
        assertEquals(132, snapshot.pulse!!.bpm)
        assertEquals(pulseAt, snapshot.pulse!!.measuredAtMillis)
      }

  @Test
  fun `snapshot and local parser skip completed sets when anchoring the next set`() = runTest {
    val workout = insertWorkout("workout")
    val section = insertWorkoutExercise(workout, exercise())
    val currentId = insertSet(section, 0, reps = 8)
    insertSet(section, 1, reps = 8, isCompleted = true)
    val nextId = insertSet(section, 2, reps = 8)
    val timer = RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime })
    val snapshot =
        WorkoutControlService(db, coordinator(timer), timer, session).snapshot("user", workout)!!

    assertEquals(db.workoutDao().getSet(currentId)!!.syncId, snapshot.currentSetId)
    assertEquals(db.workoutDao().getSet(nextId)!!.syncId, snapshot.nextSetId)
    val packet =
        requireNotNull(
                LocalWorkoutCommandParser.parse(
                    "поставь в следующем подходе 6 повторений",
                    snapshot,
                )
            )
            .packet
    assertEquals(
        db.workoutDao().getSet(nextId)!!.syncId,
        (packet.operations.single() as WorkoutChangeSet.Operation.EditSet).setSyncId,
    )
  }

  @Test
  fun `user reply clears an initiative wait when no proposal is pending`() = runTest {
    val workout = insertWorkout("workout")
    val timer = RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime })
    val control = WorkoutControlService(db, coordinator(timer), timer, session)
    db.coachDao()
        .saveContext(
            CoachSessionContextEntity(workout, "user", initiativePendingInteraction = true)
        )

    assertTrue(control.appendMessage("reply", "user", workout, "user", "Продолжаю"))

    assertFalse(db.coachDao().context(workout)!!.initiativePendingInteraction)
  }

  @Test
  fun `correcting a completed result refreshes actual values without restarting rest`() = runTest {
    val workout = insertWorkout("workout")
    val section = insertWorkoutExercise(workout, exercise())
    val setId = insertSet(section, 0, reps = 8, isCompleted = true)
    val before = db.workoutDao().getSet(setId)!!
    db.workoutDao().updateSet(before.copy(completedAt = 123L, actualReps = 8))
    val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val timer = RestTimerEngine(timerScope, WallClock { 0L })
    try {
      timer.start(60)
      val startId = timer.currentStartId()
      val coordinator = coordinator(timer)
      val packet =
          WorkoutChangeSet.Packet(
              listOf(
                  WorkoutChangeSet.Operation.EditSet(before.syncId, reps = 6, recordResult = true)
              )
          )

      assertEquals(
          CommandResult.APPLIED,
          coordinator.submit("user", workout, "correct", 0, packet, authority(packet)).result,
      )

      val stored = db.workoutDao().getSet(setId)!!
      assertEquals(6, stored.reps)
      assertEquals(6, stored.actualReps)
      assertEquals(1, stored.coachMutationRevision)
      assertEquals(123L, stored.completedAt)
      assertTrue(stored.isCompleted)
      assertEquals(startId, timer.currentStartId())
    } finally {
      timerScope.cancel()
    }
  }

  private val session = FakeSession()

  private fun coordinator(timer: RestTimerEngine): WorkoutMutationCoordinator {
    db.openHelper.writableDatabase.execSQL(
        "INSERT OR REPLACE INTO backend_state (id, owner, generation, phase, initialMergeAcknowledged) VALUES (1, 'user', 0, 'OWNED', 1)",
    )
    return WorkoutMutationCoordinator(
        db,
        db.workoutDao(),
        db.coachDao(),
        timer,
        session,
        WorkoutWriteQueue(),
    )
  }

  private fun authority(packet: WorkoutChangeSet.Packet) =
      CommandAuthority.local(packet, CommandAuthority.Anchors(null, null, null))

  private suspend fun exercise(
      name: String = "Exercise",
      type: ExerciseType = ExerciseType.STRENGTH,
  ): Long =
      db.exerciseDao()
          .insert(ExerciseEntity(name = name, muscleGroup = MuscleGroup.CHEST, type = type))

  private class FakeSession : BackendSessionStore {
    private val state =
        MutableStateFlow<BackendTokens?>(
            BackendTokens("user", "user@example.com", "access", "refresh")
        )
    override val session: StateFlow<BackendTokens?> = state

    override fun save(tokens: BackendTokens?) {
      state.value = tokens
    }
  }

  private class FakeHeartRateMonitor(reading: HeartRateReading?) : HeartRateMonitor {
    override val state: StateFlow<HeartRateConnectionState> =
        MutableStateFlow(HeartRateConnectionState.Idle)
    override val reading: StateFlow<HeartRateReading?> = MutableStateFlow(reading)

    override fun scan() = Unit

    override fun connect(device: HeartRateDevice) = Unit

    override fun stop() = Unit

    override fun reportError(message: String) = Unit
  }
}
