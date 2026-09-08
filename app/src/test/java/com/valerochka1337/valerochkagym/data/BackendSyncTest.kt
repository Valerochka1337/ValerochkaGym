package com.valerochka1337.valerochkagym.data

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.db.entity.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BackendSyncTest : RoomDaoTest() {
  private val raw
    get() = db.openHelper.writableDatabase

  private fun exercise(id: String = "00000000-0000-0000-0000-000000000001") =
      ExerciseEntity(
          name = "Тест",
          muscleGroup = MuscleGroup.LEGS,
          type = ExerciseType.STRENGTH,
          isCustom = true,
          syncId = id,
          updatedAt = 1,
          equipmentRequirementState = EquipmentRequirementState.KNOWN,
      )

  private class Store : BackendSessionStore {
    override val session =
        MutableStateFlow<BackendTokens?>(
            BackendTokens("user-a", "a@example.com", "access", "refresh")
        )

    override fun save(tokens: BackendTokens?) {
      session.value = tokens
    }
  }

  private class Server : BackendTransport {
    override val json = Json { encodeDefaults = true }
    val records = linkedMapOf<String, CloudRecord>()
    val operations = mutableMapOf<String, Pair<CloudPush, CloudAck>>()
    var revision = 0L
    var loseNextResponse = false
    var failBeforeCommit = false
    var catalog = StandardSnapshot(false, 0, emptyList(), emptyList())
    var catalogReads = 0
    var afterCommit: (suspend () -> Unit)? = null

    override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement =
        json.encodeToJsonElement(catalog).also {
          check(path == "/catalog")
          catalogReads++
        }

    override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement {
      if (method == "GET")
          return json.encodeToJsonElement(CloudSnapshot(revision, records.values.toList()))
      if (failBeforeCommit) {
        failBeforeCommit = false
        throw IOException("Offline before commit")
      }
      val push = json.decodeFromJsonElement<CloudPush>(body!!)
      check(push.changes.none { c -> catalog.records.any { it.id == c.id } })
      val old = operations[push.operationId]
      if (old != null) {
        check(old.first == push)
        return json.encodeToJsonElement(old.second)
      }
      push.changes.forEach {
        if ((records["${it.kind}:${it.id}"]?.revision ?: 0) != it.baseRevision)
            throw BackendException(409, "revision_conflict", "Conflict")
      }
      revision++
      push.changes.forEach {
        val r = CloudRecord(it.kind, it.id, revision, it.deleted, it.payload)
        records[r.key] = r
      }
      val ack = CloudAck(revision)
      operations[push.operationId] = push to ack
      afterCommit?.also {
        afterCommit = null
        it()
      }
      if (loseNextResponse) {
        loseNextResponse = false
        throw IOException("Response lost after commit")
      }
      return json.encodeToJsonElement(ack)
    }
  }

  @Test
  fun `clean restore removes unused bootstrap placeholders while keeping downloaded UUIDs`() =
      runTest {
        SyncSchema.install(raw)
        raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
        val placeholder = exercise().copy(isCustom = false)
        db.exerciseDao().insert(placeholder)
        raw.execSQL(
            "UPDATE catalog_state SET bootstrapSnapshot=? WHERE id=1",
            arrayOf(JsonObject(PortableData(raw).snapshot()).toString()),
        )
        val originalPayload = PortableData(raw).snapshot().values.single()
        val server = Server()
        val restoredId = UUID.randomUUID().toString()
        server.catalog =
            StandardSnapshot(
                true,
                1,
                listOf(StandardRecord("exercise", restoredId, 1, false, originalPayload)),
                emptyList(),
            )
        val sync = BackendSync(db, server, Store())
        sync.claim("user-a")
        sync.run()
        assertEquals(listOf(restoredId), db.exerciseDao().getAllOnce().map { it.syncId })
        assertTrue(server.records.isEmpty())
      }

  @Test
  fun `catalog reclassifies stable IDs without changing history and never uploads standard rows`() =
      runTest {
        SyncSchema.install(raw)
        raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
        val ex = exercise()
        val localId = db.exerciseDao().insert(ex)
        insertWorkout("history", finishedAt = 2000)
        val section = insertWorkoutExercise("history", localId)
        insertSet(section, 0, weightKg = 80.0, reps = 5, isCompleted = true)
        val server = Server()
        val sync = BackendSync(db, server, Store())
        sync.claim("user-a")
        sync.run()
        val before = PortableData(raw).snapshot()["workout:history"]
        val standard = server.records.remove("exercise:${ex.syncId}")!!
        server.catalog =
            StandardSnapshot(
                true,
                1,
                listOf(StandardRecord("exercise", ex.syncId, 1, false, standard.payload!!)),
                emptyList(),
            )
        sync.run()
        sync.run()
        assertEquals(localId, db.exerciseDao().getAllOnce().single().id)
        assertEquals("STANDARD", db.exerciseDao().getById(localId)!!.origin)
        assertEquals(before, PortableData(raw).snapshot()["workout:history"])
        assertEquals(section, workoutFull("history").exercises.single().workoutExercise.id)
        assertFalse(PortableData(raw).snapshot().containsKey(standard.key))
        assertTrue(server.records.values.none { it.kind == "exercise" })
      }

  @Test
  fun `transition retains exact outbox until restart choice and copies edits without relinking history`() =
      runTest {
        SyncSchema.install(raw)
        raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
        val ex = exercise()
        val localId = db.exerciseDao().insert(ex)
        insertWorkout("history", finishedAt = 2000)
        insertWorkoutExercise("history", localId)
        val server = Server()
        val store = Store()
        val sync = BackendSync(db, server, store)
        sync.claim("user-a")
        sync.run()
        val standard = server.records.getValue("exercise:${ex.syncId}")
        db.exerciseDao()
            .update(db.exerciseDao().getById(localId)!!.copy(name = "Моя правка", updatedAt = 2))
        server.failBeforeCommit = true
        try {
          sync.run()
          fail()
        } catch (_: IOException) {}
        val pending =
            raw.query("SELECT requestJson FROM backend_outbox").use {
              it.moveToFirst()
              it.getString(0)
            }
        server.records.remove(standard.key)
        server.catalog =
            StandardSnapshot(
                true,
                1,
                listOf(StandardRecord("exercise", ex.syncId, 1, false, standard.payload!!)),
                emptyList(),
            )
        try {
          sync.run()
          fail()
        } catch (e: BackendException) {
          assertEquals("catalog_transition_required", e.code)
        }
        assertEquals(
            pending,
            raw.query("SELECT requestJson FROM backend_outbox").use {
              it.moveToFirst()
              it.getString(0)
            },
        )
        assertEquals("Моя правка", db.exerciseDao().getById(localId)!!.name)
        BackendSync(db, server, store).run("local")
        val rows = db.exerciseDao().getAllOnce()
        assertEquals(2, rows.size)
        assertEquals("STANDARD", rows.single { it.id == localId }.origin)
        val copy = rows.single { it.id != localId }
        assertEquals("PERSONAL", copy.origin)
        assertTrue(copy.name.startsWith("Моя правка"))
        assertNotEquals(ex.syncId, copy.syncId)
        assertEquals(localId, workoutFull("history").exercises.single().exercise.id)
        assertEquals(0, tableCount("backend_outbox"))
        assertEquals(
            pending,
            raw.query("SELECT originalOutbox FROM catalog_state").use {
              it.moveToFirst()
              it.getString(0)
            },
        )
      }

  @Test
  fun `public catalog updates equipment without account and active workout defers its application`() =
      runTest {
        val server = Server()
        val store = Store().also { it.save(null) }
        val payload = buildJsonObject {
          put("name", "Новая скамья")
          put("group", "Скамьи")
          put("synonyms", JsonArray(listOf(JsonPrimitive("лавка"))))
          put(
              "provides",
              JsonArray(listOf(JsonPrimitive("flat_bench"), JsonPrimitive("new_bench"))),
          )
        }
        server.catalog =
            StandardSnapshot(
                true,
                2,
                emptyList(),
                listOf(StandardRecord("equipment", "new_bench", 2, false, payload)),
            )
        val sync = BackendSync(db, server, store)
        insertWorkout("active")
        sync.run()
        assertEquals(0, server.catalogReads)
        raw.execSQL("UPDATE workouts SET finishedAt=2000 WHERE id='active'")
        sync.run()
        assertTrue(
            com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog.covers(
                setOf("new_bench"),
                "flat_bench",
            )
        )
        CatalogSchema.install(raw)
        CatalogSchema.publishEquipment(raw)
        assertEquals(
            "Новая скамья",
            com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog.require("new_bench")
                .name,
        )
      }

  @Test
  fun `lost response retries the same durable operation without a duplicate`() = runTest {
    SyncSchema.install(raw)
    raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
    db.exerciseDao().insert(exercise())
    val server = Server()
    val store = Store()
    val sync = BackendSync(db, server, store)
    sync.claim("user-a")
    server.loseNextResponse = true
    try {
      sync.run()
      fail("Expected network failure")
    } catch (_: IOException) {}
    assertEquals(1, tableCount("backend_outbox"))
    val restarted = BackendSync(db, server, store)
    restarted.run()
    assertEquals(1, server.operations.size)
    assertEquals(0, tableCount("backend_outbox"))
    assertEquals(1, server.records.size)
  }

  @Test
  fun `local edits committed while uploading are sent as a newer operation`() = runTest {
    SyncSchema.install(raw)
    raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
    val id = db.exerciseDao().insert(exercise())
    val server = Server()
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")
    server.afterCommit = {
      raw.execSQL("UPDATE exercises SET name='После запроса' WHERE id=?", arrayOf(id))
    }
    sync.run()
    assertEquals(2, server.operations.size)
    assertEquals(
        "После запроса",
        server.records.values.single().payload!!["name"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `switching accounts clears the previous history baseline and pending uploads`() = runTest {
    SyncSchema.install(raw)
    val store = Store()
    val server = Server()
    val sync = BackendSync(db, server, store)
    sync.claim("user-a")
    db.exerciseDao().insert(exercise())
    sync.run()
    db.bodyMeasurementDao()
        .insert(
            BodyMeasurementEntity(
                id = UUID.randomUUID().toString(),
                measuredAt = 1,
                weightKg = 80.0,
            )
        )
    server.loseNextResponse = true
    try {
      sync.run()
    } catch (_: IOException) {}
    assertEquals(1, tableCount("backend_outbox"))
    sync.claim("user-b")
    assertEquals(0, tableCount("exercises"))
    assertEquals(0, tableCount("body_measurements"))
    assertEquals(0, tableCount("backend_baseline"))
    assertEquals(0, tableCount("backend_outbox"))
    assertEquals("user-b", sync.owner())
    // An old worker cannot write with account A's credentials after the owner changes.
    try {
      sync.run()
      fail("Old session must be rejected")
    } catch (e: BackendException) {
      assertEquals("owner_changed", e.code)
    }
    val otherServer = Server()
    store.save(BackendTokens("user-b", "b@example.com", "access-b", "refresh-b"))
    BackendSync(db, otherServer, store).run()
    assertTrue(otherServer.records.isEmpty())
  }

  @Test
  fun `account switch and sign in retain downloaded standard records without reseeding`() =
      runTest {
        SyncSchema.install(raw)
        val server = Server()
        val store = Store()
        val sync = BackendSync(db, server, store)
        sync.claim("user-a")
        val ex = exercise()
        val localId = db.exerciseDao().insert(ex)
        raw.execSQL(
            "INSERT INTO exercise_equipment(exerciseId,equipmentId) VALUES (?, 'dumbbells')",
            arrayOf(localId),
        )
        val payload = PortableData(raw).snapshot().getValue("exercise:${ex.syncId}")
        raw.execSQL("UPDATE catalog_state SET applying=1 WHERE id=1")
        raw.execSQL("UPDATE exercises SET origin='STANDARD' WHERE id=?", arrayOf(localId))
        raw.execSQL("UPDATE catalog_state SET applying=0 WHERE id=1")
        server.catalog =
            StandardSnapshot(
                true,
                1,
                listOf(StandardRecord("exercise", ex.syncId, 1, false, payload)),
                emptyList(),
            )
        sync.run()
        sync.signOut()
        assertNull(store.session.value)
        sync.signIn(BackendTokens("user-b", "b@example.com", "access-b", "refresh-b"))
        sync.run()
        val retained = db.exerciseDao().getAllOnce().single()
        assertEquals(localId, retained.id)
        assertEquals("STANDARD", retained.origin)
        assertEquals(ex.name, retained.name)
        assertTrue(server.records.isEmpty())
        assertEquals(1, tableCount("exercise_equipment"))
        assertEquals(
            payload,
            PortableData(raw).snapshot(includeStandard = true).getValue("exercise:${ex.syncId}"),
        )
        assertEquals(1, tableCount("catalog_records"))
        assertEquals("user-b", sync.owner())
      }

  @Test
  fun `first account discards legacy local history instead of importing it`() = runTest {
    SyncSchema.install(raw)
    db.exerciseDao().insert(exercise())
    insertWorkout("legacy", finishedAt = 2000)
    val sync = BackendSync(db, Server(), Store())
    sync.claim("user-a")
    assertEquals(0, tableCount("workouts"))
    assertEquals(0, tableCount("exercises"))
  }

  @Test
  fun `returning to the same account retains offline changes`() = runTest {
    SyncSchema.install(raw)
    val sync = BackendSync(db, Server(), Store())
    sync.claim("user-a")
    db.exerciseDao().insert(exercise())
    sync.claim("user-a")
    assertEquals(1, tableCount("exercises"))
  }

  @Test
  fun `active workout prevents switching to a different account`() = runTest {
    SyncSchema.install(raw)
    val sync = BackendSync(db, Server(), Store())
    sync.claim("user-a")
    insertWorkout("active")
    try {
      sync.claim("user-b")
      fail("Workout must be finished")
    } catch (e: BackendException) {
      assertEquals("workout_active", e.code)
    }
    assertEquals("user-a", sync.owner())
    assertEquals(1, tableCount("workouts"))
  }

  @Test
  fun `local logout succeeds offline and allows a different account to sign in`() = runTest {
    SyncSchema.install(raw)
    val store = Store()
    val offline =
        object : BackendTransport {
          override val json = Json

          override suspend fun public(
              method: String,
              path: String,
              body: JsonElement?,
          ): JsonElement = throw IOException()

          override suspend fun authorized(
              method: String,
              path: String,
              body: JsonElement?,
          ): JsonElement = throw IOException()
        }
    val sync = BackendSync(db, offline, store)
    sync.claim("user-a")
    db.bodyMeasurementDao().insert(BodyMeasurementEntity(id = "a", measuredAt = 1, weightKg = 80.0))
    sync.signOut()
    assertNull(store.session.value)
    sync.signIn(BackendTokens("user-b", "b@example.com", "access-b", "refresh-b"))
    assertEquals("user-b", store.session.value?.userId)
    assertEquals("user-b", sync.owner())
    assertEquals(0, tableCount("body_measurements"))
  }

  @Test
  fun `logout on every device requires an acknowledgement while offline`() = runTest {
    SyncSchema.install(raw)
    val store = Store()
    val offline =
        object : BackendTransport {
          override val json = Json

          override suspend fun public(
              method: String,
              path: String,
              body: JsonElement?,
          ): JsonElement = throw IOException()

          override suspend fun authorized(
              method: String,
              path: String,
              body: JsonElement?,
          ): JsonElement = throw IOException()
        }
    val sync = BackendSync(db, offline, store)
    sync.claim("user-a")
    try {
      sync.signOut(all = true)
      fail("Server acknowledgement required")
    } catch (_: IOException) {}
    assertEquals("user-a", store.session.value?.userId)
  }

  @Test
  fun `remote edits and offline deletion conflict without losing the local choice`() = runTest {
    SyncSchema.install(raw)
    raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
    val id = db.exerciseDao().insert(exercise())
    val server = Server()
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")
    sync.run()
    raw.execSQL("DELETE FROM exercises WHERE id=?", arrayOf(id))
    val record = server.records.values.single()
    server.revision++
    server.records[record.key] =
        record.copy(
            revision = server.revision,
            payload =
                JsonObject(record.payload!! + mapOf("name" to JsonPrimitive("Другое устройство"))),
        )
    try {
      sync.run()
      fail("Expected conflict")
    } catch (e: BackendException) {
      assertEquals("revision_conflict", e.code)
    }
    assertEquals(0, tableCount("exercises"))
    assertTrue(sync.conflict.value)
    sync.run("local")
    assertTrue(server.records.values.single().deleted)
  }

  @Test
  fun `remote version restores an offline deleted object when explicitly selected`() = runTest {
    SyncSchema.install(raw)
    raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
    val id = db.exerciseDao().insert(exercise())
    val server = Server()
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")
    sync.run()
    raw.execSQL("DELETE FROM exercises WHERE id=?", arrayOf(id))
    val record = server.records.values.single()
    server.revision++
    server.records[record.key] =
        record.copy(
            revision = server.revision,
            payload = JsonObject(record.payload!! + mapOf("name" to JsonPrimitive("Удалённо"))),
        )
    sync.run("server")
    assertEquals("Удалённо", db.exerciseDao().getAllOnce().single().name)
  }

  @Test
  fun `dirty marker and domain edit roll back together`() = runTest {
    SyncSchema.install(raw)
    raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
    try {
      db.withTransaction {
        db.exerciseDao().insert(exercise())
        throw IOException("rollback")
      }
    } catch (_: IOException) {}
    assertEquals(0, tableCount("exercises"))
    raw.query("SELECT generation FROM backend_state").use {
      it.moveToFirst()
      assertEquals(0, it.getLong(0))
    }
    db.exerciseDao().insert(exercise())
    raw.query("SELECT generation FROM backend_state").use {
      it.moveToFirst()
      assertEquals(1, it.getLong(0))
    }
  }

  @Test
  fun `portable snapshot restores duplicate sections null measurements and UUID references`() =
      runTest {
        val exercise = db.exerciseDao().insert(exercise())
        val workout = "00000000-0000-0000-0000-000000000002"
        insertWorkout(workout, finishedAt = 2000)
        val first = insertWorkoutExercise(workout, exercise, 0)
        val second = insertWorkoutExercise(workout, exercise, 1)
        insertSet(first, 0, weightKg = 25.0, reps = 8, isCompleted = true)
        insertSet(second, 0, weightKg = 30.0, reps = 6, isCompleted = true)
        raw.execSQL(
            "UPDATE workout_exercises SET sectionId=? WHERE id=?",
            arrayOf<Any>("00000000-0000-0000-0000-000000000003", first),
        )
        raw.execSQL(
            "UPDATE workout_exercises SET sectionId=? WHERE id=?",
            arrayOf<Any>("00000000-0000-0000-0000-000000000004", second),
        )
        db.bodyMeasurementDao()
            .insert(
                BodyMeasurementEntity(
                    id = "00000000-0000-0000-0000-000000000005",
                    measuredAt = 1000,
                    weightKg = 75.0,
                )
            )
        val before = PortableData(raw).snapshot()
        val records =
            before.map { (key, payload) ->
              CloudRecord(key.substringBefore(':'), key.substringAfter(':'), 1, false, payload)
            }
        val fixture = java.io.File("build/reports/backend/android-snapshot.json")
        fixture.parentFile.mkdirs()
        fixture.writeText(Json.encodeToString(CloudSnapshot(1, records)))
        db.clearAllTables()
        // Ensure restoration does not accidentally depend on the old numeric row IDs.
        db.exerciseDao().insert(exercise("00000000-0000-0000-0000-000000000099"))
        db.withTransaction { PortableData(raw).apply(records, emptyList()) }
        val after = PortableData(raw).snapshot().filterKeys { !it.endsWith("0099") }
        assertEquals(before, after)
        assertEquals(2, tableCount("workout_exercises"))
        assertEquals(2, tableCount("workout_sets"))
      }

  @Test
  fun `active workout blocks remote writes until the foreground session completes`() = runTest {
    SyncSchema.install(raw)
    raw.execSQL("UPDATE backend_state SET owner='user-a' WHERE id=1")
    insertWorkout(UUID.randomUUID().toString())
    val server = Server()
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")
    sync.run()
    assertTrue(server.operations.isEmpty())
    assertTrue(sync.status.value.contains("после тренировки"))
  }
}
