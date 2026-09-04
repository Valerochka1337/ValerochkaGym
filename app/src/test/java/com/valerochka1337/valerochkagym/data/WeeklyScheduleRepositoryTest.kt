package com.valerochka1337.valerochkagym.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.google.AccountBoundGoogleAuth
import com.valerochka1337.valerochkagym.data.google.CalendarApi
import com.valerochka1337.valerochkagym.data.google.CalendarEventDto
import com.valerochka1337.valerochkagym.data.google.CalendarEventResponseDto
import com.valerochka1337.valerochkagym.data.google.EventDateTimeDto
import com.valerochka1337.valerochkagym.data.google.EventRemindersDto
import com.valerochka1337.valerochkagym.data.google.ScheduleResult
import com.valerochka1337.valerochkagym.data.google.TokenResult
import com.valerochka1337.valerochkagym.data.schedule.DayRule
import com.valerochka1337.valerochkagym.data.schedule.PreparedCalendarEvent
import com.valerochka1337.valerochkagym.data.schedule.WeeklySchedule
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleOperation
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleOperationJournal
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleOperationKind
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleOperationPhase
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleOperationRead
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRecoveryResult
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRepositoryImpl
import com.valerochka1337.valerochkagym.worker.WeeklyScheduleRecoveryScheduler
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class WeeklyScheduleRepositoryTest : RoomDaoTest() {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  @Test
  fun `save confirms all new events before deleting old and persists client ids`() = runTest {
    val routine = seedRoutine("Ноги")
    val old = ownedSchedule(routine, "old-event")
    val settings = FakeDataStore(old)
    val operations = FakeDataStore()
    val api = FakeCalendarApi()

    val result =
        repository(api, settings, operations)
            .save(
                WeeklySchedule(listOf(DayRule(1, routine, 18, 30))),
            )

    assertEquals(ScheduleResult.Success, result)
    assertEquals(listOf("insert", "delete:old-event"), api.calls)
    val requestId = api.insertedBodies.single().id
    assertNotNull(requestId)
    assertTrue(requestId!!.matches(Regex("[0-9a-f]{32}")))
    val active = repository(api, settings, operations).observe().first()
    assertEquals(requestId, active.rules.single().calendarEventId)
    assertEquals(EMAIL, active.ownerEmail)
  }

  @Test
  fun `insert failure cleans attempted new ids and preserves old active and remote`() = runTest {
    val routine = seedRoutine("Ноги")
    val old = ownedSchedule(routine, "old-event")
    val settings = FakeDataStore(old)
    val operations = FakeDataStore()
    val api = FakeCalendarApi(insertFailure = IOException("timeout"))

    val result =
        repository(api, settings, operations)
            .save(
                WeeklySchedule(listOf(DayRule(1, routine, 18, 0))),
            )

    assertTrue((result as ScheduleResult.Failure).message.contains("старое расписание сохранено"))
    assertFalse(api.deletedEventIds.contains("old-event"))
    assertEquals(api.insertedBodies.single().id, api.deletedEventIds.single())
    assertEquals(old, repository(api, settings, operations).observe().first())
  }

  @Test
  fun `delete failure keeps active and recovery resumes only remaining delete`() = runTest {
    val routine = seedRoutine("Ноги")
    val old = ownedSchedule(routine, "old-event")
    val settings = FakeDataStore(old)
    val operations = FakeDataStore()
    val scheduler = FakeScheduler()
    val api = FakeCalendarApi(deleteResponses = ArrayDeque(listOf(500, 204)))
    val repo = repository(api, settings, operations, scheduler = scheduler)

    val first = repo.save(WeeklySchedule(listOf(DayRule(2, routine, 9, 0))))
    assertTrue(first is ScheduleResult.Failure)
    assertEquals(old, repo.observe().first())
    assertEquals(1, api.insertedBodies.size)
    assertTrue(scheduler.enqueues > 0)

    val recovered =
        repository(api, settings, operations, scheduler = scheduler).resumePendingOperation()
    assertEquals(WeeklyScheduleRecoveryResult.Completed, recovered)
    assertEquals(1, api.insertedBodies.size)
    assertEquals(2, api.deletedEventIds.count { it == "old-event" })
  }

  @Test
  fun `cancellation after insert apply keeps create pending and same id 409 confirms recovery`() =
      runTest {
        val routine = seedRoutine("Ноги")
        val old = ownedSchedule(routine, "old-event")
        val settings = FakeDataStore(old)
        val operations = FakeDataStore()
        val scheduler = FakeScheduler()
        val api = FakeCalendarApi(insertFailure = CancellationException("process stopped"))
        val repo = repository(api, settings, operations, scheduler = scheduler)

        try {
          repo.save(WeeklySchedule(listOf(DayRule(1, routine, 18, 0))))
          fail("CancellationException expected")
        } catch (_: CancellationException) {
          // Durable pre-insert checkpoint remains CREATE_NEW.
        }
        val attemptedId = api.insertedBodies.single().id
        assertEquals(old, repo.observe().first())
        assertFalse(api.deletedEventIds.contains("old-event"))
        assertTrue(scheduler.enqueues > 0)

        api.insertFailure = httpException(409)
        val recovered =
            repository(api, settings, operations, scheduler = scheduler).resumePendingOperation()

        assertEquals(WeeklyScheduleRecoveryResult.Completed, recovered)
        assertEquals(listOf(attemptedId, attemptedId), api.insertedBodies.map { it.id })
        assertTrue(api.deletedEventIds.contains("old-event"))
        assertEquals(attemptedId, repo.observe().first().rules.single().calendarEventId)
      }

  @Test
  fun `clear adopts a legacy owner before remote delete`() = runTest {
    val routine = seedRoutine("Ноги")
    val legacy = WeeklySchedule(listOf(DayRule(3, routine, 8, 0, "old-event")))
    val settings = FakeDataStore(legacy)
    val operations = FakeDataStore()
    val auth = FakeAccountAuth()

    val result = repository(FakeCalendarApi(), settings, operations, auth).clear()

    assertEquals(ScheduleResult.Success, result)
    assertEquals(listOf(EMAIL), auth.expectedEmails)
    assertTrue(
        repository(FakeCalendarApi(), settings, operations).observe().first().rules.isEmpty()
    )
  }

  @Test
  fun `clear on empty active bypasses account token and API`() = runTest {
    val settings = FakeDataStore(email = null)
    val operations = FakeDataStore()
    val auth = FakeAccountAuth()
    val api = FakeCalendarApi()

    assertEquals(ScheduleResult.Success, repository(api, settings, operations, auth).clear())
    assertTrue(auth.expectedEmails.isEmpty())
    assertTrue(api.calls.isEmpty())
  }

  @Test
  fun `account mismatch pauses without calendar calls`() = runTest {
    val routine = seedRoutine("Ноги")
    val settings = FakeDataStore(ownedSchedule(routine, "old-event"), email = "other@example.com")
    val operations = FakeDataStore()
    val api = FakeCalendarApi()

    val result = repository(api, settings, operations).clear()

    assertTrue(result is ScheduleResult.Failure)
    assertTrue(api.calls.isEmpty())
  }

  @Test
  fun `delete 404 and 410 responses and exceptions all confirm the pending id`() = runTest {
    val routine = seedRoutine("Ноги")
    for (code in listOf(404, 410)) {
      val settings = FakeDataStore(ownedSchedule(routine, "old-$code"))
      val result =
          repository(
                  FakeCalendarApi(deleteResponses = ArrayDeque(listOf(code))),
                  settings,
                  FakeDataStore(),
              )
              .clear()
      assertEquals(ScheduleResult.Success, result)
      assertTrue(
          repository(FakeCalendarApi(), settings, FakeDataStore()).observe().first().rules.isEmpty()
      )
    }

    val settings = FakeDataStore(ownedSchedule(routine, "old-http-exception"))
    val api = FakeCalendarApi(deleteFailure = httpException(404))
    assertEquals(ScheduleResult.Success, repository(api, settings, FakeDataStore()).clear())
    assertTrue(repository(api, settings, FakeDataStore()).observe().first().rules.isEmpty())
  }

  @Test
  fun `unreadable journal fails closed without API or a replacement operation`() = runTest {
    val routine = seedRoutine("Ноги")
    val old = ownedSchedule(routine, "old-event")
    val settings = FakeDataStore(old)
    val operations = FakeDataStore()
    operations.putRaw("pending_operation", "broken-json")
    val api = FakeCalendarApi()

    val result =
        repository(api, settings, operations)
            .save(
                WeeklySchedule(listOf(DayRule(1, routine, 18, 0))),
            )

    assertTrue(result is ScheduleResult.Failure)
    assertTrue(api.calls.isEmpty())
    assertEquals(old, repository(api, settings, operations).observe().first())
  }

  @Test
  fun `terminal delete marker commits locally despite later account mismatch`() = runTest {
    val routine = seedRoutine("Ноги")
    val old = ownedSchedule(routine, "old-event")
    val settings = FakeDataStore(old)
    val operations = FakeDataStore()
    val scheduler = FakeScheduler()
    val api = FakeCalendarApi()
    api.afterDelete = {
      settings.failure = CancellationException("process stopped before active commit")
    }

    try {
      repository(api, settings, operations, scheduler = scheduler)
          .save(
              WeeklySchedule(listOf(DayRule(2, routine, 9, 0))),
          )
      fail("CancellationException expected")
    } catch (_: CancellationException) {
      // DELETE_OLD with empty pendingDeleteIds is durable, active is still old.
    }
    settings.failure = null
    settings.setEmail("other@example.com")
    val auth = FakeAccountAuth(TokenResult.Failed(IOException("must not be called")))

    val recovered = repository(api, settings, operations, auth, scheduler).resumePendingOperation()

    assertEquals(WeeklyScheduleRecoveryResult.Completed, recovered)
    assertTrue(auth.expectedEmails.isEmpty())
    assertEquals("other@example.com", settings.currentEmail())
    assertFalse(repository(api, settings, operations).observe().first().rules.isEmpty())
    assertFalse(repository(api, settings, operations).observe().first() == old)
  }

  @Test
  fun `empty cleanup marker clears locally without account or token`() = runTest {
    val routine = seedRoutine("Ноги")
    val old = ownedSchedule(routine, "old-event")
    val settings = FakeDataStore(old, email = null)
    val operations = FakeDataStore()
    writeOperation(
        operations,
        replaceOperation(
            phase = WeeklyScheduleOperationPhase.CLEANUP_NEW,
            old = old,
            routineId = routine,
            pendingCreateIds = listOf(CLIENT_ID),
            cleanupNewIds = emptyList(),
        ),
    )
    val auth = FakeAccountAuth(TokenResult.Failed(IOException("must not be called")))

    val result = repository(FakeCalendarApi(), settings, operations, auth).resumePendingOperation()

    assertEquals(WeeklyScheduleRecoveryResult.Completed, result)
    assertTrue(auth.expectedEmails.isEmpty())
    assertEquals(old, repository(FakeCalendarApi(), settings, operations).observe().first())
  }

  @Test
  fun `confirmed create marker advances and commits locally without account or token`() = runTest {
    val routine = seedRoutine("Ноги")
    val settings = FakeDataStore(email = "other@example.com")
    val operations = FakeDataStore()
    val operation =
        replaceOperation(
            phase = WeeklyScheduleOperationPhase.CREATE_NEW,
            old = WeeklySchedule(),
            routineId = routine,
            pendingCreateIds = emptyList(),
            cleanupNewIds = listOf(CLIENT_ID),
        )
    writeOperation(operations, operation)
    val auth = FakeAccountAuth(TokenResult.Failed(IOException("must not be called")))

    val result = repository(FakeCalendarApi(), settings, operations, auth).resumePendingOperation()

    assertEquals(WeeklyScheduleRecoveryResult.Completed, result)
    assertTrue(auth.expectedEmails.isEmpty())
    assertEquals(
        operation.targetSchedule,
        repository(FakeCalendarApi(), settings, operations).observe().first(),
    )
  }

  @Test
  fun `terminal marker after active commit replays journal clear without auth`() = runTest {
    val routine = seedRoutine("Ноги")
    val old = ownedSchedule(routine, "old-event")
    val settings = FakeDataStore(old)
    val operations = FakeDataStore()
    val api = FakeCalendarApi()
    api.afterDelete = {
      settings.afterUpdate = {
        operations.failure = CancellationException("process stopped before journal clear")
      }
    }

    try {
      repository(api, settings, operations)
          .save(
              WeeklySchedule(listOf(DayRule(2, routine, 9, 0))),
          )
      fail("CancellationException expected")
    } catch (_: CancellationException) {}
    val committed = repository(api, settings, operations).observe().first()
    assertFalse(committed == old)
    settings.afterUpdate = null
    operations.failure = null
    settings.setEmail(null)
    val auth = FakeAccountAuth(TokenResult.Failed(IOException("must not be called")))

    val recovered = repository(api, settings, operations, auth).resumePendingOperation()

    assertEquals(WeeklyScheduleRecoveryResult.Completed, recovered)
    assertTrue(auth.expectedEmails.isEmpty())
    assertEquals(committed, repository(api, settings, operations).observe().first())
  }

  @Test
  fun `worker recovery never appends successors for permanent or transient cleanup failure`() =
      runTest {
        val routine = seedRoutine("Ноги")
        for ((code, expectedRetry) in listOf(403 to false, 500 to true)) {
          val settings = FakeDataStore()
          val operations = FakeDataStore()
          writeOperation(
              operations,
              replaceOperation(
                  phase = WeeklyScheduleOperationPhase.CLEANUP_NEW,
                  old = WeeklySchedule(),
                  routineId = routine,
                  pendingCreateIds = listOf(CLIENT_ID),
                  cleanupNewIds = listOf(CLIENT_ID),
              ),
          )
          val scheduler = FakeScheduler()

          val result =
              repository(
                      FakeCalendarApi(deleteResponses = ArrayDeque(listOf(code))),
                      settings,
                      operations,
                      scheduler = scheduler,
                  )
                  .resumePendingOperation()

          if (expectedRetry) {
            assertTrue(result is WeeklyScheduleRecoveryResult.Retry)
          } else {
            assertTrue(result is WeeklyScheduleRecoveryResult.Paused)
          }
          assertEquals(0, scheduler.enqueues)
        }
      }

  @Test
  fun `recovery retries cleanup when insert and compensating delete are transiently unavailable`() =
      runTest {
        val routine = seedRoutine("Ноги")
        val old = ownedSchedule(routine, "old-event")
        val settings = FakeDataStore(old)
        val operations = FakeDataStore()
        writeOperation(
            operations,
            replaceOperation(
                phase = WeeklyScheduleOperationPhase.CREATE_NEW,
                old = old,
                routineId = routine,
                pendingCreateIds = listOf(CLIENT_ID),
                cleanupNewIds = emptyList(),
            ),
        )
        val api =
            FakeCalendarApi(
                insertFailure = IOException("insert timeout"),
                deleteResponses = ArrayDeque(listOf(500, 204)),
            )
        val repo = repository(api, settings, operations)

        val first = repo.resumePendingOperation()

        assertTrue(first is WeeklyScheduleRecoveryResult.Retry)
        val pending = WeeklyScheduleOperationJournal(operations, json).read()
        assertTrue(pending is WeeklyScheduleOperationRead.Present)
        pending as WeeklyScheduleOperationRead.Present
        assertEquals(WeeklyScheduleOperationPhase.CLEANUP_NEW, pending.operation.phase)
        assertEquals(listOf(CLIENT_ID), pending.operation.cleanupNewIds)

        api.insertFailure = null
        val recovered = repo.resumePendingOperation()

        assertEquals(WeeklyScheduleRecoveryResult.Completed, recovered)
        assertEquals(1, api.insertedBodies.size)
        assertEquals(listOf(CLIENT_ID, CLIENT_ID), api.deletedEventIds)
        assertEquals(old, repo.observe().first())
      }

  @Test
  fun `journal io failure retries and next recovery completes the pending operation`() = runTest {
    val routine = seedRoutine("Ноги")
    val settings = FakeDataStore()
    val operations = FakeDataStore()
    val operation =
        replaceOperation(
            phase = WeeklyScheduleOperationPhase.CREATE_NEW,
            old = WeeklySchedule(),
            routineId = routine,
            pendingCreateIds = listOf(CLIENT_ID),
            cleanupNewIds = emptyList(),
        )
    writeOperation(operations, operation)
    operations.readFailures = 1
    val api = FakeCalendarApi()
    val repo = repository(api, settings, operations)

    val first = repo.resumePendingOperation()

    assertTrue(first is WeeklyScheduleRecoveryResult.Retry)
    assertTrue(api.calls.isEmpty())

    val recovered = repo.resumePendingOperation()

    assertEquals(WeeklyScheduleRecoveryResult.Completed, recovered)
    assertEquals(listOf("insert"), api.calls)
    assertEquals(operation.targetSchedule, repo.observe().first())
  }

  @Test
  fun `middle insert failure cleans attempted ids but excludes unattempted id`() = runTest {
    val routine = seedRoutine("Ноги")
    val old = ownedSchedule(routine, "old-event")
    val settings = FakeDataStore(old)
    val operations = FakeDataStore()
    val api =
        FakeCalendarApi(
            insertFailure = IOException("timeout"),
            failInsertOnCall = 1,
        )

    val result =
        repository(api, settings, operations)
            .save(
                WeeklySchedule(
                    listOf(
                        DayRule(1, routine, 18, 0),
                        DayRule(2, routine, 18, 0),
                        DayRule(3, routine, 18, 0),
                    ),
                ),
            )

    assertTrue(result is ScheduleResult.Failure)
    assertEquals(2, api.insertedBodies.size)
    assertEquals(api.insertedBodies.map { it.id }, api.deletedEventIds)
    assertFalse(api.deletedEventIds.contains("old-event"))
    assertEquals(old, repository(api, settings, operations).observe().first())
  }

  @Test
  fun `failed cleanup survives recreation and worker retry does not enqueue`() = runTest {
    val routine = seedRoutine("Ноги")
    val old = ownedSchedule(routine, "old-event")
    val settings = FakeDataStore(old)
    val operations = FakeDataStore()
    val scheduler = FakeScheduler()
    val api =
        FakeCalendarApi(
            insertFailure = IOException("insert timeout"),
            deleteResponses = ArrayDeque(listOf(500, 204)),
        )

    val first =
        repository(api, settings, operations, scheduler = scheduler)
            .save(
                WeeklySchedule(listOf(DayRule(1, routine, 18, 0))),
            )
    assertTrue(first is ScheduleResult.Failure)
    val interactiveEnqueues = scheduler.enqueues
    api.insertFailure = null

    val recovered =
        repository(api, settings, operations, scheduler = scheduler).resumePendingOperation()

    assertEquals(WeeklyScheduleRecoveryResult.Completed, recovered)
    assertEquals(interactiveEnqueues, scheduler.enqueues)
    assertEquals(old, repository(api, settings, operations).observe().first())
  }

  @Test
  fun `applied delete timeout stays pending and 404 recovery does not insert again`() = runTest {
    val routine = seedRoutine("Ноги")
    val old = ownedSchedule(routine, "old-event")
    val settings = FakeDataStore(old)
    val operations = FakeDataStore()
    val scheduler = FakeScheduler()
    val api = FakeCalendarApi(deleteFailure = IOException("applied then timeout"))

    val first =
        repository(api, settings, operations, scheduler = scheduler)
            .save(
                WeeklySchedule(listOf(DayRule(1, routine, 18, 0))),
            )
    assertTrue(first is ScheduleResult.Failure)
    api.deleteFailure = null
    api.deleteResponses += 404
    val inserts = api.insertedBodies.size

    val recovered =
        repository(api, settings, operations, scheduler = scheduler).resumePendingOperation()

    assertEquals(WeeklyScheduleRecoveryResult.Completed, recovered)
    assertEquals(inserts, api.insertedBodies.size)
    assertEquals(2, api.deletedEventIds.count { it == "old-event" })
  }

  @Test
  fun `delete 401 403 and 429 preserve pending clear until a confirmed retry`() = runTest {
    val routine = seedRoutine("Ноги")
    for (code in listOf(401, 403, 429)) {
      val old = ownedSchedule(routine, "old-$code")
      val settings = FakeDataStore(old)
      val operations = FakeDataStore()
      val scheduler = FakeScheduler()
      val api = FakeCalendarApi(deleteResponses = ArrayDeque(listOf(code, 204)))
      val repo = repository(api, settings, operations, scheduler = scheduler)

      assertTrue(repo.clear() is ScheduleResult.Failure)
      assertEquals(old, repo.observe().first())
      assertEquals(WeeklyScheduleRecoveryResult.Completed, repo.resumePendingOperation())
      assertTrue(repo.observe().first().rules.isEmpty())
    }
  }

  @Test
  fun `interactive save and worker recovery share the repository mutex`() = runTest {
    val routine = seedRoutine("Ноги")
    val gate = CompletableDeferred<Unit>()
    val api = FakeCalendarApi(insertGate = gate)
    val repo = repository(api, FakeDataStore(), FakeDataStore())

    val save = async { repo.save(WeeklySchedule(listOf(DayRule(1, routine, 18, 0)))) }
    runCurrent()
    val recovery = async { repo.resumePendingOperation() }
    runCurrent()

    assertFalse(recovery.isCompleted)
    assertEquals(1, api.insertedBodies.size)
    gate.complete(Unit)
    assertEquals(ScheduleResult.Success, save.await())
    assertEquals(WeeklyScheduleRecoveryResult.NothingPending, recovery.await())
    assertEquals(1, api.insertedBodies.size)
  }

  @Test
  fun `interactive datastore write failures preserve old active without API`() = runTest {
    val routine = seedRoutine("Ноги")
    val old = ownedSchedule(routine, "old-event")

    val adoptionSettings = FakeDataStore(old.copy(ownerEmail = null))
    adoptionSettings.failure = IOException("settings edit")
    val adoptionApi = FakeCalendarApi()
    val adoptionResult = repository(adoptionApi, adoptionSettings, FakeDataStore()).clear()
    assertTrue(adoptionResult is ScheduleResult.Failure)
    assertTrue(adoptionApi.calls.isEmpty())

    val settings = FakeDataStore(old)
    val operations = FakeDataStore().apply { failure = IOException("journal write") }
    val api = FakeCalendarApi()
    val saveResult =
        repository(api, settings, operations)
            .save(
                WeeklySchedule(listOf(DayRule(1, routine, 18, 0))),
            )
    assertTrue(saveResult is ScheduleResult.Failure)
    assertTrue(api.calls.isEmpty())
    assertEquals(old, repository(api, settings, FakeDataStore()).observe().first())
  }

  @Test
  fun `save empty uses resumable clear and cancellation recovery`() = runTest {
    val routine = seedRoutine("Ноги")
    val old = ownedSchedule(routine, "old-event")
    val settings = FakeDataStore(old)
    val operations = FakeDataStore()
    val scheduler = FakeScheduler()
    val api = FakeCalendarApi(deleteFailure = CancellationException("applied then stopped"))

    try {
      repository(api, settings, operations, scheduler = scheduler).save(WeeklySchedule())
      fail("CancellationException expected")
    } catch (_: CancellationException) {}
    api.deleteFailure = httpException(404)
    val recovered =
        repository(api, settings, operations, scheduler = scheduler).resumePendingOperation()

    assertEquals(WeeklyScheduleRecoveryResult.Completed, recovered)
    assertTrue(repository(api, settings, operations).observe().first().rules.isEmpty())
    assertEquals(1, scheduler.enqueues)
  }

  private fun repository(
      api: FakeCalendarApi,
      settings: FakeDataStore,
      operations: FakeDataStore,
      auth: FakeAccountAuth = FakeAccountAuth(),
      scheduler: FakeScheduler = FakeScheduler(),
  ) =
      WeeklyScheduleRepositoryImpl(
          api = api,
          googleAuth = auth,
          routineDao = db.routineDao(),
          dataStore = settings,
          operationsDataStore = operations,
          json = json,
          recoveryScheduler = scheduler,
      )

  private suspend fun writeOperation(
      operations: FakeDataStore,
      operation: WeeklyScheduleOperation,
  ) {
    WeeklyScheduleOperationJournal(operations, json).write(operation)
  }

  private fun replaceOperation(
      phase: WeeklyScheduleOperationPhase,
      old: WeeklySchedule,
      routineId: Long,
      pendingCreateIds: List<String>,
      cleanupNewIds: List<String>,
  ): WeeklyScheduleOperation {
    val rule = DayRule(1, routineId, 18, 0, CLIENT_ID)
    val request =
        CalendarEventDto(
            id = CLIENT_ID,
            summary = "Тренировка: Ноги",
            start = EventDateTimeDto("2026-09-07T18:00:00+03:00", "Europe/Moscow"),
            end = EventDateTimeDto("2026-09-07T19:00:00+03:00", "Europe/Moscow"),
            reminders = EventRemindersDto(false, emptyList()),
            recurrence = listOf("RRULE:FREQ=WEEKLY;BYDAY=MO"),
        )
    return WeeklyScheduleOperation(
        kind = WeeklyScheduleOperationKind.REPLACE,
        phase = phase,
        accountEmail = EMAIL,
        oldSchedule = old,
        targetSchedule = WeeklySchedule(listOf(rule), EMAIL),
        preparedEvents = listOf(PreparedCalendarEvent(CLIENT_ID, rule, request)),
        pendingCreateIds = pendingCreateIds,
        cleanupNewIds = cleanupNewIds,
        pendingDeleteIds = old.rules.mapNotNull { it.calendarEventId },
    )
  }

  private suspend fun seedRoutine(name: String): Long =
      db.routineDao().upsertRoutine(RoutineEntity(name = name))

  private fun ownedSchedule(routineId: Long, eventId: String) =
      WeeklySchedule(
          rules = listOf(DayRule(5, routineId, 8, 0, eventId)),
          ownerEmail = EMAIL,
      )

  private class FakeAccountAuth(
      private val result: TokenResult = TokenResult.Success("token"),
  ) : AccountBoundGoogleAuth {
    val expectedEmails = mutableListOf<String>()

    override suspend fun getAccessTokenForAccount(expectedEmail: String): TokenResult {
      expectedEmails += expectedEmail
      return result
    }
  }

  private class FakeScheduler : WeeklyScheduleRecoveryScheduler {
    var enqueues = 0

    override fun enqueue() {
      enqueues++
    }
  }

  private fun httpException(code: Int): HttpException =
      HttpException(Response.error<Unit>(code, "".toResponseBody()))

  private class FakeCalendarApi(
      var insertFailure: Exception? = null,
      var deleteFailure: Exception? = null,
      val deleteResponses: ArrayDeque<Int> = ArrayDeque(),
      private val failInsertOnCall: Int = 0,
      private val insertGate: CompletableDeferred<Unit>? = null,
  ) : CalendarApi {
    var afterDelete: (() -> Unit)? = null
    val insertedBodies = mutableListOf<CalendarEventDto>()
    val deletedEventIds = mutableListOf<String>()
    val calls = mutableListOf<String>()

    override suspend fun insertEvent(
        bearer: String,
        body: CalendarEventDto,
    ): CalendarEventResponseDto {
      calls += "insert"
      insertedBodies += body
      insertGate?.await()
      if (insertedBodies.lastIndex == failInsertOnCall) insertFailure?.let { throw it }
      return CalendarEventResponseDto(body.id!!)
    }

    override suspend fun deleteEvent(bearer: String, eventId: String): Response<Unit> {
      calls += "delete:$eventId"
      deletedEventIds += eventId
      deleteFailure?.let { throw it }
      val code = deleteResponses.removeFirstOrNull() ?: 204
      afterDelete?.invoke()
      return if (code in 200..299) Response.success(Unit)
      else Response.error(code, "".toResponseBody())
    }
  }

  private inner class FakeDataStore(
      initial: WeeklySchedule? = null,
      email: String? = EMAIL,
  ) : DataStore<Preferences> {
    var failure: Exception? = null
    var readFailures = 0
    private val state =
        MutableStateFlow<Preferences>(
            mutablePreferencesOf().apply {
              initial?.let { this[SCHEDULE_KEY] = json.encodeToString(it) }
              email?.let { this[EMAIL_KEY] = it }
            },
        )
    override val data: Flow<Preferences>
      get() = flow {
        if (readFailures > 0) {
          readFailures--
          throw IOException("read failed")
        }
        emitAll(state)
      }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
      failure?.let { throw it }
      state.value = transform(state.value)
      afterUpdate?.invoke()
      return state.value
    }

    fun putRaw(key: String, value: String) {
      state.value = mutablePreferencesOf(stringPreferencesKey(key) to value)
    }

    fun setEmail(value: String?) {
      val schedule = state.value[SCHEDULE_KEY]
      state.value =
          when {
            schedule != null && value != null ->
                mutablePreferencesOf(SCHEDULE_KEY to schedule, EMAIL_KEY to value)
            schedule != null -> mutablePreferencesOf(SCHEDULE_KEY to schedule)
            value != null -> mutablePreferencesOf(EMAIL_KEY to value)
            else -> emptyPreferences()
          }
    }

    fun currentEmail(): String? = state.value[EMAIL_KEY]

    var afterUpdate: (() -> Unit)? = null
  }

  private companion object {
    const val EMAIL = "owner@example.com"
    const val CLIENT_ID = "0123456789abcdef0123456789abcdef"
    val SCHEDULE_KEY = stringPreferencesKey("weekly_schedule")
    val EMAIL_KEY = stringPreferencesKey("google_email")
  }
}
