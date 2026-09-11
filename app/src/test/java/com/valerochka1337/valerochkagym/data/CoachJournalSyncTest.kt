package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.db.entity.CoachJournalEntity
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CoachJournalSyncTest : RoomDaoTest() {
  private val raw
    get() = db.openHelper.writableDatabase

  private val workout = "10000000-0000-0000-0000-000000000001"
  private val device = "20000000-0000-0000-0000-000000000001"
  private val store = Store()
  private val server = Server()
  private val sync
    get() = CoachJournalSync(db, server, store)

  private class Store : BackendSessionStore {
    override val session =
        MutableStateFlow<BackendTokens?>(BackendTokens("owner", "a@example.com", "a", "r"))
    override var sessionEpoch = 0L

    override fun save(tokens: BackendTokens?) {
      sessionEpoch++
      session.value = tokens
    }
  }

  private class Server : BackendTransport {
    override val json = Json { encodeDefaults = true }
    val pushes = mutableListOf<CoachJournalPush>()
    val paths = mutableListOf<String>()
    var post: (suspend (CoachJournalPush) -> JsonElement)? = null
    var get: (suspend (String) -> CoachJournalPage)? = null

    override suspend fun authorizedRawResponse(
        method: String,
        path: String,
        rawBody: ByteArray,
        headers: Map<String, String>,
        expectedOwner: String?,
        expectedSessionEpoch: Long?,
        retryOnUnauthorized: Boolean,
        maxResponseBytes: Int?,
    ): BackendResponse {
      val response =
          authorized(
              method,
              path,
              rawBody
                  .takeIf { it.isNotEmpty() }
                  ?.let { json.parseToJsonElement(it.decodeToString()) },
          )
      return BackendResponse(
          response,
          byteArrayOf(),
          emptySet(),
          expectedOwner,
          expectedSessionEpoch ?: 0L,
      )
    }

    override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement =
        error("Unused")

    override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement {
      paths += path
      if (method == "GET")
          return json.encodeToJsonElement(
              get?.invoke(path) ?: CoachJournalPage(emptyList(), watermark = 0)
          )
      val push = json.decodeFromJsonElement<CoachJournalPush>(body!!)
      pushes += push
      return post?.invoke(push) ?: json.encodeToJsonElement(CoachJournalAck(push.entries.size))
    }
  }

  private suspend fun prepare(acknowledge: Boolean = true) {
    SyncSchema.install(raw)
    raw.execSQL("UPDATE backend_state SET owner='owner' WHERE id=1")
    insertWorkout(workout, finishedAt = 2000)
    if (acknowledge) acknowledge()
  }

  private fun acknowledge() {
    val record =
        CloudRecord(
            "workout",
            workout,
            1,
            payload = PortableData(raw).snapshot().getValue("workout:$workout"),
        )
    raw.execSQL(
        "INSERT OR REPLACE INTO backend_baseline(`key`,recordJson) VALUES (?,?)",
        arrayOf(record.key, server.json.encodeToString(record)),
    )
  }

  private fun entry(text: String = "Готово", id: String = UUID.randomUUID().toString()) =
      CoachJournalEntry(
          id,
          workout,
          device,
          1000,
          buildJsonObject {
            put("kind", "proposal")
            put("text", text)
            put("role", "assistant")
            put("confirmed", true)
          },
      )

  private suspend fun local(entry: CoachJournalEntry = entry()) {
    db.coachDao()
        .saveJournal(
            CoachJournalEntity(
                entry.id,
                "owner",
                workout,
                entry.createdAt,
                entry.payload.toString(),
            )
        )
  }

  private fun watermark(): Long =
      raw.query("SELECT watermark FROM coach_sync_state WHERE accountId='owner'").use {
        if (it.moveToFirst()) it.getLong(0) else 0
      }

  private fun pending(): Int =
      raw.query("SELECT COUNT(*) FROM coach_journal WHERE uploaded=0").use {
        it.moveToFirst()
        it.getInt(0)
      }

  private suspend fun fails(code: String, block: suspend () -> Unit) {
    try {
      block()
      fail("Expected $code")
    } catch (e: BackendException) {
      assertEquals(code, e.code)
    }
  }

  @Test
  fun `journal waits for exact current workout content and revision`() = runTest {
    prepare()
    local()
    raw.execSQL("UPDATE workouts SET coachRevision=1 WHERE id=?", arrayOf(workout))
    fails("workout_not_acknowledged") { sync.run("owner") }
    assertTrue(server.pushes.isEmpty())
    assertEquals(1, pending())
    acknowledge()
    sync.run("owner")
    assertEquals(1, server.pushes.size)
    assertEquals(0, pending())
  }

  @Test
  fun `lost acknowledgement retries the identical immutable envelope`() = runTest {
    prepare()
    local()
    server.post = { throw IOException("Response lost") }
    try {
      sync.run("owner")
      fail()
    } catch (_: IOException) {}
    assertEquals(1, pending())
    server.post = null
    sync.run("owner")
    assertEquals(server.pushes[0], server.pushes[1])
    assertEquals(0, pending())
  }

  @Test
  fun `partial acknowledgement preserves all pending entries`() = runTest {
    prepare()
    local()
    local()
    server.post = { server.json.encodeToJsonElement(CoachJournalAck(1)) }
    fails("journal_ack_invalid") { sync.run("owner") }
    assertEquals(2, pending())
  }

  @Test
  fun `workout edit during journal upload leaves the packet pending`() = runTest {
    prepare()
    local()
    server.post = { push ->
      raw.execSQL(
          "UPDATE workouts SET note='После отправки',coachRevision=1 WHERE id=?",
          arrayOf(workout),
      )
      server.json.encodeToJsonElement(CoachJournalAck(push.entries.size))
    }
    fails("workout_not_acknowledged") { sync.run("owner") }
    assertEquals(1, pending())
  }

  @Test
  fun `failed second page keeps account cursor and resumes without duplicate authority`() =
      runTest {
        prepare()
        val first = entry()
        val second = entry("Второе")
        server.get = { path ->
          if ("cursor=" in path) throw IOException("Offline")
          else CoachJournalPage(listOf(first), "1:2", 2)
        }
        try {
          sync.run("owner")
          fail()
        } catch (_: IOException) {}
        assertEquals(0L, watermark())
        assertEquals(1, tableCount("coach_journal"))
        server.get = { path ->
          if ("cursor=" in path) CoachJournalPage(listOf(second), watermark = 2)
          else CoachJournalPage(listOf(first), "1:2", 2)
        }
        sync.run("owner")
        assertEquals(2L, watermark())
        assertEquals(2, tableCount("coach_messages"))
        assertEquals(0, tableCount("coach_proposals"))
        assertEquals(0, tableCount("coach_command_receipts"))
        raw.query("SELECT status FROM coach_messages").use {
          while (it.moveToNext()) assertEquals("IMPORTED", it.getString(0))
        }
        assertTrue(server.paths.any { "cursor=1%3A2" in it })
      }

  @Test
  fun `account switch during page request rejects imported rows and cursor`() = runTest {
    prepare()
    server.get = {
      store.save(null)
      CoachJournalPage(listOf(entry()), watermark = 1)
    }
    fails("owner_changed") { sync.run("owner") }
    assertEquals(0, tableCount("coach_journal"))
    assertEquals(0L, watermark())
  }

  @Test
  fun `same account signing in again invalidates an in flight journal page`() = runTest {
    prepare()
    server.get = {
      store.save(store.session.value)
      CoachJournalPage(listOf(entry()), watermark = 1)
    }
    fails("owner_changed") { sync.run("owner") }
    assertEquals(0, tableCount("coach_journal"))
    assertEquals(0L, watermark())
  }

  @Test
  fun `workout missing response keeps local journal for later retry`() = runTest {
    prepare()
    local()
    server.post = { throw BackendException(409, "workout_deleted", "Missing") }
    fails("workout_deleted") { sync.run("owner") }
    assertEquals(1, tableCount("coach_journal"))
    assertEquals(1, pending())
  }

  @Test
  fun `unknown downloaded workout stops cursor without deleting local journal`() = runTest {
    prepare()
    local()
    server.get = {
      CoachJournalPage(
          listOf(entry().copy(workoutId = UUID.randomUUID().toString())),
          watermark = 1,
      )
    }
    fails("journal_workout_missing") { sync.run("owner") }
    assertEquals(1, tableCount("coach_journal"))
    assertEquals(0L, watermark())
  }

  @Test
  fun `deleted highest sequence resets watermark only after a complete reread`() = runTest {
    prepare()
    raw.execSQL(
        "INSERT INTO coach_sync_state(accountId,deviceId,watermark) VALUES('owner',?,10)",
        arrayOf(device),
    )
    server.get = { path ->
      if ("after=10" in path)
          throw BackendException(400, "invalid_request", "Cursor exceeds maximum")
      CoachJournalPage(listOf(entry()), watermark = 5)
    }
    sync.run("owner")
    assertEquals(5L, watermark())
    assertEquals(1, tableCount("coach_messages"))
    assertTrue(server.paths.last().contains("after=0"))
    server.get = { CoachJournalPage(emptyList(), watermark = 5) }
    sync.run("owner")
    assertTrue(server.paths.last().contains("after=5"))
  }

  @Test
  fun `journal packets respect byte size and entry count`() = runTest {
    prepare()
    repeat(15) { local(entry("a".repeat(60000))) }
    sync.run("owner")
    assertTrue(server.pushes.size > 1)
    assertTrue(
        server.pushes.all {
          it.entries.size <= 100 && server.json.encodeToString(it).toByteArray().size <= 512 * 1024
        }
    )
    assertEquals(0, pending())
  }

  @Test
  fun `downloaded event cannot overwrite an existing immutable local event`() = runTest {
    prepare()
    val event = entry()
    local(event)
    server.get = {
      CoachJournalPage(
          listOf(
              server.pushes
                  .single()
                  .entries
                  .single()
                  .copy(payload = buildJsonObject { put("text", "Изменено") })
          ),
          watermark = 1,
      )
    }
    fails("journal_event_conflict") { sync.run("owner") }
    assertEquals(0L, watermark())
    assertEquals(0, tableCount("coach_messages"))
  }
}
