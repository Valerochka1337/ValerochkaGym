package com.valerochka1337.valerochkagym.data

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.db.entity.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    var beforePost: (suspend () -> Unit)? = null
    var beforeGet: (suspend () -> Unit)? = null
    var beforeCatalog: (suspend () -> Unit)? = null
    var getReads = 0
    var postAttempts = 0

    override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement =
        json.encodeToJsonElement(catalog).also {
          check(path == "/catalog")
          catalogReads++
          beforeCatalog?.also {
            beforeCatalog = null
            it()
          }
        }

    override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement {
      if (method == "GET") {
        getReads++
        beforeGet?.also {
          beforeGet = null
          it()
        }
        return json.encodeToJsonElement(CloudSnapshot(revision, records.values.toList()))
      }
      if (failBeforeCommit) {
        failBeforeCommit = false
        throw IOException("Offline before commit")
      }
      val push = json.decodeFromJsonElement<CloudPush>(body!!)
      postAttempts++
      beforePost?.also {
        beforePost = null
        it()
      }
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
    assertEquals(2, server.postAttempts)
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
  fun `switching accounts blocks a retained previous history and pending uploads`() = runTest {
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
    val claim = sync.claim("user-b")
    assertTrue(claim is GuestClaimResult.Blocked)
    assertEquals(1, tableCount("exercises"))
    assertEquals(1, tableCount("body_measurements"))
    assertEquals(1, tableCount("backend_outbox"))
    assertEquals("user-a", sync.owner())
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
  fun `first account retains legacy local history for the initial merge`() = runTest {
    SyncSchema.install(raw)
    db.exerciseDao().insert(exercise())
    insertWorkout("legacy", finishedAt = 2000)
    val sync = BackendSync(db, Server(), Store())
    sync.claim("user-a")
    assertEquals(1, tableCount("workouts"))
    assertEquals(1, tableCount("exercises"))
  }

  @Test
  fun `initial merge retains the six kind guest union and existing server records`() = runTest {
    SyncSchema.install(raw)
    val exerciseId = db.exerciseDao().insert(exercise())
    val gymId = db.gymDao().insertGym(GymEntity(syncId = "gym", name = "Зал"))
    val routineId = db.routineDao().upsertRoutine(RoutineEntity(syncId = "routine", name = "План"))
    db.gymDao().replaceRoutineGyms(routineId, listOf(gymId))
    db.routineDao()
        .replaceRoutineExercises(
            routineId,
            listOf(
                RoutineExerciseEntity(routineId = routineId, exerciseId = exerciseId, position = 0)
            ),
        )
    db.scheduledWorkoutDao()
        .insert(
            ScheduledWorkoutEntity(
                routineId = routineId,
                dateTimeMillis = 1,
                calendarEventId = "event",
            )
        )
    insertWorkout("workout", finishedAt = 2)
    raw.execSQL("UPDATE workouts SET routineId=? WHERE id='workout'", arrayOf(routineId))
    insertWorkoutExercise("workout", exerciseId)
    db.bodyMeasurementDao().insert(BodyMeasurementEntity("measurement", 1))

    val snapshot = PortableData(raw).snapshot()
    val server = Server()
    val remote =
        CloudRecord("measurement", "remote", 1, false, snapshot.getValue("measurement:measurement"))
    server.records[remote.key] = remote
    server.revision = 1
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")
    sync.run()
    val merged = PortableData(raw).snapshot()
    snapshot.forEach { (key, payload) ->
      assertEquals(payload, merged[key])
      assertEquals(payload, server.records[key]?.payload)
    }
    assertEquals(remote.payload, merged[remote.key])
    assertEquals(GuestSyncPhase.OWNED, sync.transfer.value.phase)
    assertEquals(0, tableCount("backend_outbox"))

    assertEquals(
        setOf("exercise", "gym", "routine", "workout", "measurement", "schedule"),
        snapshot.keys.map { it.substringBefore(':') }.toSet(),
    )
    assertEquals(
        "routine",
        snapshot.getValue("workout:workout").getValue("routineId").jsonPrimitive.content,
    )
    assertEquals(
        "gym",
        snapshot
            .getValue("routine:routine")
            .getValue("gymIds")
            .jsonArray
            .single()
            .jsonPrimitive
            .content,
    )
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
  fun `claimed account rejects a different account before active data can change`() = runTest {
    SyncSchema.install(raw)
    val sync = BackendSync(db, Server(), Store())
    sync.claim("user-a")
    insertWorkout("active")
    try {
      sync.claim("user-b")
      fail("Workout must be finished")
    } catch (e: BackendException) {
      assertEquals("claim_owned_by_other", e.code)
    }
    assertEquals("user-a", sync.owner())
    assertEquals(1, tableCount("workouts"))
  }

  @Test
  fun `local logout retains a claimed transfer for its original account`() = runTest {
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
    try {
      sync.signIn(BackendTokens("user-b", "b@example.com", "access-b", "refresh-b"))
      fail("Different account must not replace a claimed transfer")
    } catch (e: BackendException) {
      assertEquals("claim_owned_by_other", e.code)
    }
    assertNull(store.session.value)
    assertEquals("user-a", sync.owner())
    assertEquals(1, tableCount("body_measurements"))
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
    val server = Server()
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")
    insertWorkout(UUID.randomUUID().toString())
    sync.run()
    assertTrue(server.operations.isEmpty())
    assertTrue(sync.status.value.contains("после тренировки"))
  }

  @Test
  fun `claimed transfer rejects C before replacing B tokens`() = runTest {
    SyncSchema.install(raw)
    val store = Store().also { it.save(null) }
    val sync = BackendSync(db, Server(), store)
    sync.signIn(BackendTokens("user-b", "b@example.com", "access-b", "refresh-b"))

    try {
      sync.signIn(BackendTokens("user-c", "c@example.com", "access-c", "refresh-c"))
      fail("C must not replace a durable B claim")
    } catch (error: BackendException) {
      assertEquals("claim_owned_by_other", error.code)
    }

    assertEquals("user-b", store.session.value?.userId)
    assertEquals("user-b", sync.transfer.value.owner)
    assertEquals(GuestSyncPhase.CLAIMED, sync.transfer.value.phase)
  }

  @Test
  fun `owned cache outbox blocks B before replacing A tokens`() = runTest {
    SyncSchema.install(raw)
    val store = Store()
    val sync = BackendSync(db, Server(), store)
    sync.claim("user-a")
    sync.run()
    val bytes = "{\"operationId\":\"retain-exact\",\"changes\":[] }"
    raw.execSQL(
        "INSERT INTO backend_outbox(id,owner,requestJson) VALUES(1,'user-a',?)",
        arrayOf(bytes),
    )

    try {
      sync.signIn(BackendTokens("user-b", "b@example.com", "access-b", "refresh-b"))
      fail("retained outbox must block replacement")
    } catch (error: BackendException) {
      assertEquals("guest_data_preservation_required", error.code)
    }

    assertEquals("user-a", store.session.value?.userId)
    raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
      assertTrue(it.moveToFirst())
      assertEquals(bytes, it.getString(0))
    }
  }

  private suspend fun assertIndependentFootprint(mutate: (CloudRecord) -> Unit) {
    SyncSchema.install(raw)
    db.exerciseDao().insert(exercise())
    val server = Server()
    val store = Store()
    val sync = BackendSync(db, server, store)
    sync.claim("user-a")
    sync.run()
    val generation =
        raw.query("SELECT generation FROM backend_state WHERE id=1").use {
          it.moveToFirst()
          it.getLong(0)
        }
    mutate(server.records.getValue("exercise:${exercise().syncId}"))
    raw.execSQL("UPDATE backend_state SET generation=? WHERE id=1", arrayOf(generation))
    val before = PortableData(raw).snapshot()
    val baselineBytes =
        raw.query("SELECT recordJson FROM backend_baseline ORDER BY `key`").use {
          buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
    assertEquals(0, tableCount("backend_outbox"))
    assertTrue(sync.claim("user-b") is GuestClaimResult.Blocked)
    assertEquals("user-a", sync.owner())
    assertEquals("user-a", store.session.value?.userId)
    assertEquals(before, PortableData(raw).snapshot())
    assertEquals(
        baselineBytes,
        raw.query("SELECT recordJson FROM backend_baseline ORDER BY `key`").use {
          buildList { while (it.moveToNext()) add(it.getString(0)) }
        },
    )
  }

  @Test
  fun `baseline divergence blocks replacement even with unchanged generation and no journals`() =
      runTest {
        assertIndependentFootprint { record ->
          val different =
              record.copy(
                  payload =
                      JsonObject(record.payload!! + ("name" to JsonPrimitive("Baseline differs")))
              )
          raw.execSQL(
              "UPDATE backend_baseline SET recordJson=? WHERE `key`=?",
              arrayOf(Json.encodeToString(different), record.key),
          )
        }
      }

  @Test
  fun `baseline only deleted local record blocks replacement without generation evidence`() =
      runTest {
        assertIndependentFootprint { raw.execSQL("DELETE FROM exercises") }
      }

  @Test
  fun `catalog applying alone blocks replacement without pending snapshot or outbox`() = runTest {
    assertIndependentFootprint { raw.execSQL("UPDATE catalog_state SET applying=1 WHERE id=1") }
    raw.query("SELECT applying,pendingSnapshot,originalOutbox FROM catalog_state WHERE id=1").use {
      assertTrue(it.moveToFirst())
      assertEquals(1, it.getInt(0))
      assertTrue(it.isNull(1))
      assertTrue(it.isNull(2))
    }
  }

  @Test
  fun `late POST acknowledgement cannot update baseline or discard the retained operation under B`() =
      runTest {
        SyncSchema.install(raw)
        val id = db.exerciseDao().insert(exercise())
        val server = Server()
        val store = Store()
        val sync = BackendSync(db, server, store)
        sync.claim("user-a")
        sync.run()
        val baseline =
            raw.query(
                    "SELECT recordJson FROM backend_baseline WHERE `key`=?",
                    arrayOf("exercise:${exercise().syncId}"),
                )
                .use {
                  it.moveToFirst()
                  it.getString(0)
                }
        raw.execSQL("UPDATE exercises SET name='Local A' WHERE id=?", arrayOf(id))
        var request = ""
        server.afterCommit = {
          request =
              raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
                it.moveToFirst()
                it.getString(0)
              }
          store.save(BackendTokens("user-b", "b@e", "b", "b"))
          raw.execSQL(
              "UPDATE backend_state SET owner='user-b',phase='CLAIMED',mergeId='b' WHERE id=1"
          )
        }
        try {
          sync.run()
          fail("late A ACK must fail closed")
        } catch (error: BackendException) {
          assertEquals("owner_changed", error.code)
        }
        assertEquals("user-b", sync.owner())
        assertEquals(
            baseline,
            raw.query(
                    "SELECT recordJson FROM backend_baseline WHERE `key`=?",
                    arrayOf("exercise:${exercise().syncId}"),
                )
                .use {
                  it.moveToFirst()
                  it.getString(0)
                },
        )
        assertEquals(
            request,
            raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
              it.moveToFirst()
              it.getString(0)
            },
        )
        assertEquals("Local A", db.exerciseDao().getById(id)!!.name)
      }

  @Test
  fun `preservation fingerprint changes with exact catalog journal bytes and tombstone contents`() =
      runTest {
        SyncSchema.install(raw)
        val sync = BackendSync(db, Server(), Store())
        sync.claim("user-a")
        sync.run()
        raw.execSQL("UPDATE catalog_state SET originalOutbox='first' WHERE id=1")
        val first = sync.claim("user-b") as GuestClaimResult.Blocked
        raw.execSQL("UPDATE catalog_state SET originalOutbox='second' WHERE id=1")
        val second = sync.claim("user-b") as GuestClaimResult.Blocked
        raw.execSQL(
            "INSERT INTO configuration_tombstones(kind,syncId,updatedAt) VALUES('routine','r',1)"
        )
        val third = sync.claim("user-b") as GuestClaimResult.Blocked
        raw.execSQL(
            "UPDATE configuration_tombstones SET updatedAt=2 WHERE kind='routine' AND syncId='r'"
        )
        val fourth = sync.claim("user-b") as GuestClaimResult.Blocked

        assertNotEquals(first.footprint.fingerprint, second.footprint.fingerprint)
        assertNotEquals(second.footprint.fingerprint, third.footprint.fingerprint)
        assertNotEquals(third.footprint.fingerprint, fourth.footprint.fingerprint)
      }

  @Test
  fun `failed B token installation leaves claimed B and rejects old A sync after recreation`() =
      runTest {
        SyncSchema.install(raw)
        raw.execSQL(
            "UPDATE backend_state SET owner='user-a',phase='OWNED',initialMergeAcknowledged=1 WHERE id=1"
        )
        var failTokenWrite = true
        val store =
            object : BackendSessionStore {
              override val session =
                  MutableStateFlow<BackendTokens?>(BackendTokens("user-a", "a@e", "a", "a"))

              override fun save(tokens: BackendTokens?) {
                if (tokens?.userId == "user-b" && failTokenWrite)
                    throw IOException("token write failed")
                session.value = tokens
              }
            }
        val server = Server()
        try {
          BackendSync(db, server, store).signIn(BackendTokens("user-b", "b@e", "b", "b"))
          fail("token installation must fail")
        } catch (_: IOException) {}

        assertEquals("user-a", store.session.value?.userId)
        assertEquals("user-b", BackendSync(db, server, store).transfer.value.owner)
        try {
          BackendSync(db, server, store).run()
          fail("old A credentials must never render or call through B's cache")
        } catch (error: BackendException) {
          assertEquals("owner_changed", error.code)
        }
        assertEquals(0, server.catalogReads)
        val restarted = BackendSync(db, server, store)
        val mergeId = restarted.transfer.value.mergeId
        try {
          restarted.signIn(BackendTokens("user-c", "c@e", "c", "c"))
          fail("a different account cannot resume B's transfer")
        } catch (error: BackendException) {
          assertEquals("claim_owned_by_other", error.code)
        }
        failTokenWrite = false
        restarted.signIn(BackendTokens("user-b", "b@e", "b", "b"))
        assertEquals(mergeId, restarted.transfer.value.mergeId)
        restarted.run()
        assertEquals("user-b", store.session.value?.userId)
        assertEquals(GuestSyncPhase.OWNED, restarted.transfer.value.phase)
      }

  @Test
  fun `confirmed account deletion resets claimed ownership while a failed request preserves it`() =
      runTest {
        SyncSchema.install(raw)
        val store = Store().also { it.save(BackendTokens("user-a", "a@e", "a", "a")) }
        val api =
            object : BackendTransport {
              override val json = Json
              var fail = true

              override suspend fun public(method: String, path: String, body: JsonElement?) =
                  buildJsonObject {}

              override suspend fun authorized(
                  method: String,
                  path: String,
                  body: JsonElement?,
              ): JsonElement {
                if (fail) throw IOException("offline")
                assertEquals("DELETE", method)
                return buildJsonObject {}
              }
            }
        val sync = BackendSync(db, api, store)
        sync.claim("user-a")
        try {
          sync.deleteAccount("code")
          fail("failed deletion must retain recovery state")
        } catch (_: IOException) {}
        assertEquals(GuestSyncPhase.CLAIMED, sync.transfer.value.phase)
        api.fail = false
        sync.deleteAccount("code")
        assertEquals(GuestSyncPhase.GUEST, sync.transfer.value.phase)
        assertNull(sync.owner())
        assertNull(store.session.value)
        BackendSync(db, api, store).signIn(BackendTokens("user-b", "b@e", "b", "b"))
        assertEquals("user-b", BackendSync(db, api, store).owner())
      }

  @Test
  fun `catalog response cannot apply after ownership changes`() = runTest {
    SyncSchema.install(raw)
    val store = Store()
    val server = Server()
    val sync = BackendSync(db, server, store)
    sync.claim("user-a")
    server.catalog =
        StandardSnapshot(
            true,
            1,
            listOf(
                StandardRecord(
                    "exercise",
                    "standard",
                    1,
                    false,
                    PortableData(raw).snapshot().values.firstOrNull()
                        ?: buildJsonObject { put("name", "x") },
                )
            ),
            emptyList(),
        )
    server.beforeCatalog = {
      store.save(BackendTokens("user-b", "b@e", "b", "b"))
      raw.execSQL("UPDATE backend_state SET owner='user-b',phase='CLAIMED',mergeId='b' WHERE id=1")
    }

    try {
      sync.run()
      fail("late catalog response must be rejected")
    } catch (error: BackendException) {
      assertEquals("owner_changed", error.code)
    }
    assertEquals(0, tableCount("catalog_records"))
  }

  @Test
  fun `late account request result is rejected after ownership changes`() = runTest {
    SyncSchema.install(raw)
    val store = Store()
    val server = Server()
    val sync = BackendSync(db, server, store)
    sync.claim("user-a")
    server.beforeGet = {
      store.save(BackendTokens("user-b", "b@e", "b", "b"))
      raw.execSQL("UPDATE backend_state SET owner='user-b',phase='CLAIMED',mergeId='b' WHERE id=1")
    }
    try {
      sync.accountRequest("GET", "/sessions")
      fail("late A response must not reach account UI")
    } catch (error: BackendException) {
      assertEquals("owner_changed", error.code)
    }
  }

  @Test
  fun `new batch definite 409 records marker before process recreation`() = runTest {
    SyncSchema.install(raw)
    val id = db.exerciseDao().insert(exercise())
    val server = Server()
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")
    sync.run()
    raw.execSQL("UPDATE exercises SET name='Локально' WHERE id=?", arrayOf(id))
    server.beforePost = {
      val record = server.records.getValue("exercise:${exercise().syncId}")
      server.revision++
      server.records[record.key] = record.copy(revision = server.revision)
    }
    try {
      sync.run()
      fail("new batch must receive a definite conflict")
    } catch (error: BackendException) {
      assertEquals("revision_conflict", error.code)
    }
    assertEquals(1, tableCount("backend_rejected_operations"))
    val attempts = server.postAttempts
    try {
      BackendSync(db, server, Store()).run()
      fail("manual conflict must remain after recreation")
    } catch (error: BackendException) {
      assertEquals("revision_conflict", error.code)
    }
    assertEquals(attempts, server.postAttempts)
  }

  @Test
  fun `definite 409 keeps rejected bytes through GET and manual conflict without prechoice mutation`() =
      runTest {
        SyncSchema.install(raw)
        val exerciseId = db.exerciseDao().insert(exercise())
        val server = Server()
        val sync = BackendSync(db, server, Store())
        sync.claim("user-a")
        sync.run()

        raw.execSQL("UPDATE exercises SET name='Локальная правка' WHERE id=?", arrayOf(exerciseId))
        val local = PortableData(raw).snapshot().getValue("exercise:${exercise().syncId}")
        val rejected =
            CloudPush(
                operationId = "definite-conflict",
                changes = listOf(CloudChange("exercise", exercise().syncId, 1, payload = local)),
            )
        val bytes = server.json.encodeToString(rejected)
        raw.execSQL(
            "INSERT INTO backend_outbox(id,owner,requestJson) VALUES(1,'user-a',?)",
            arrayOf(bytes),
        )
        val remote = server.records.getValue("exercise:${exercise().syncId}")
        server.revision++
        server.records[remote.key] =
            remote.copy(
                revision = server.revision,
                payload =
                    JsonObject(remote.payload!! + ("name" to JsonPrimitive("Серверная правка"))),
            )

        try {
          sync.run()
          fail("manual resolution must be requested")
        } catch (error: BackendException) {
          assertEquals("revision_conflict", error.code)
        }

        assertTrue(server.getReads > 0)
        val rejectedAttempts = server.postAttempts
        assertEquals("Локальная правка", db.exerciseDao().getById(exerciseId)!!.name)
        raw.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
          assertTrue(it.moveToFirst())
          assertEquals(bytes, it.getString(0))
        }
        raw.query(
                "SELECT recordJson FROM backend_baseline WHERE `key`='exercise:${exercise().syncId}'"
            )
            .use {
              assertTrue(it.moveToFirst())
              assertEquals(1L, server.json.decodeFromString<CloudRecord>(it.getString(0)).revision)
            }

        val restarted = BackendSync(db, server, Store())
        try {
          restarted.run()
          fail("manual resolution must survive process recreation")
        } catch (error: BackendException) {
          assertEquals("revision_conflict", error.code)
        }
        assertEquals(rejectedAttempts, server.postAttempts)
        db.bodyMeasurementDao().insert(BodyMeasurementEntity(id = "unrelated", measuredAt = 1))
        restarted.run("server")
        assertEquals(rejectedAttempts + 1, server.postAttempts)
        assertTrue(server.operations.keys.none { it == "definite-conflict" })
        assertTrue(
            server.operations.values
                .single { (_, ack) -> ack.revision > remote.revision }
                .first
                .changes
                .any { it.id == "unrelated" }
        )
        assertEquals(0, tableCount("backend_rejected_operations"))
      }

  @Test
  fun `workout conflict applies one server history without a local copy`() = runTest {
    SyncSchema.install(raw)
    val exerciseId = db.exerciseDao().insert(exercise())
    insertWorkout("history", finishedAt = 2_000)
    insertWorkoutExercise("history", exerciseId)
    val server = Server()
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")
    sync.run()
    raw.execSQL("UPDATE workouts SET name='Локальная история' WHERE id='history'")
    val remote = server.records.getValue("workout:history")
    server.revision++
    server.records[remote.key] =
        remote.copy(
            revision = server.revision,
            payload = JsonObject(remote.payload!! + ("name" to JsonPrimitive("Серверная история"))),
        )

    sync.run()

    assertEquals(1, tableCount("workouts"))
    assertEquals("Серверная история", workoutFull("history").workout.name)
    assertEquals(0, tableCount("backend_conflict_copies"))
  }

  @Test
  fun `routine deletion and gym measurement schedule conflicts retain every local choice before resolution`() =
      runTest {
        SyncSchema.install(raw)
        val exerciseId = db.exerciseDao().insert(exercise())
        val gymId = db.gymDao().insertGym(GymEntity(syncId = "gym", name = "Локальный зал"))
        val deletedRoutineId =
            db.routineDao()
                .upsertRoutine(RoutineEntity(syncId = "deleted-routine", name = "Удаляемая"))
        val scheduledRoutineId =
            db.routineDao()
                .upsertRoutine(RoutineEntity(syncId = "scheduled-routine", name = "По плану"))
        db.routineDao()
            .replaceRoutineExercises(
                scheduledRoutineId,
                listOf(
                    RoutineExerciseEntity(
                        routineId = scheduledRoutineId,
                        exerciseId = exerciseId,
                        position = 0,
                    )
                ),
            )
        val scheduledId =
            db.scheduledWorkoutDao()
                .insert(
                    ScheduledWorkoutEntity(
                        routineId = scheduledRoutineId,
                        dateTimeMillis = 10,
                        calendarEventId = "event",
                    )
                )
        db.bodyMeasurementDao()
            .insert(BodyMeasurementEntity(id = "measurement", measuredAt = 1, weightKg = 70.0))
        val server = Server()
        val sync = BackendSync(db, server, Store())
        sync.claim("user-a")
        sync.run()
        val originalRevisions = server.records.mapValues { it.value.revision }

        db.routineDao().deleteRoutine(deletedRoutineId)
        db.gymDao().updateGym(db.gymDao().getGym(gymId)!!.copy(name = "Правка зала"))
        raw.execSQL("UPDATE body_measurements SET weightKg=71 WHERE id='measurement'")
        raw.execSQL(
            "UPDATE scheduled_workouts SET dateTimeMillis=20 WHERE id=?",
            arrayOf(scheduledId),
        )
        server.revision++
        listOf(
                "routine:deleted-routine" to "Серверная программа",
                "gym:gym" to "Серверный зал",
                "measurement:measurement" to "72",
                PortableData(raw).snapshot().keys.single { it.startsWith("schedule:") } to "30",
            )
            .forEach { (key, replacement) ->
              val record = server.records.getValue(key)
              val field =
                  when (record.kind) {
                    "routine",
                    "gym" -> "name"
                    "measurement" -> "weightKg"
                    else -> "dateTimeMillis"
                  }
              server.records[key] =
                  record.copy(
                      revision = server.revision,
                      payload =
                          JsonObject(record.payload!! + (field to JsonPrimitive(replacement))),
                  )
            }

        try {
          sync.run()
          fail("each unresolved conflict must remain manual")
        } catch (error: BackendException) {
          assertEquals("revision_conflict", error.code)
        }

        assertEquals("Правка зала", db.gymDao().getGym(gymId)!!.name)
        assertEquals(71.0, db.bodyMeasurementDao().getById("measurement")!!.weightKg)
        assertEquals(20L, db.scheduledWorkoutDao().getById(scheduledId)!!.dateTimeMillis)
        assertNull(db.routineDao().getRoutineBySyncId("deleted-routine"))
        assertEquals(0, tableCount("backend_outbox"))
        raw.query("SELECT `key`,recordJson FROM backend_baseline").use { cursor ->
          while (cursor.moveToNext()) {
            val record = server.json.decodeFromString<CloudRecord>(cursor.getString(1))
            assertEquals(originalRevisions.getValue(cursor.getString(0)), record.revision)
          }
        }
      }

  @Test
  fun `routine conflict retains server original and one full mapped dirty copy until its own ACK`() =
      runTest {
        SyncSchema.install(raw)
        val exerciseId = db.exerciseDao().insert(exercise())
        val gymId = db.gymDao().insertGym(GymEntity(syncId = "gym", name = "Зал", updatedAt = 1))
        val routineId =
            db.routineDao()
                .upsertRoutine(
                    RoutineEntity(
                        syncId = "routine",
                        name = "Локальная программа",
                        note = "Заметка",
                        updatedAt = 1,
                    )
                )
        db.gymDao().replaceRoutineGyms(routineId, listOf(gymId))
        db.routineDao()
            .replaceRoutineExercises(
                routineId,
                listOf(
                    RoutineExerciseEntity(
                        routineId = routineId,
                        exerciseId = exerciseId,
                        position = 0,
                        restSeconds = 90,
                        plannedSets =
                            listOf(com.valerochka1337.valerochkagym.data.db.PlannedSet(80.0, 8)),
                    )
                ),
            )
        val server = Server()
        val sync = BackendSync(db, server, Store())
        sync.claim("user-a")
        sync.run()
        raw.execSQL("UPDATE routines SET name='Локальная правка' WHERE id=?", arrayOf(routineId))
        val localPayload = PortableData(raw).snapshot().getValue("routine:routine")
        val remote = server.records.getValue("routine:routine")
        raw.execSQL("UPDATE backend_state SET phase='OWNED',mergeId=NULL WHERE id=1")
        server.revision++
        server.records[remote.key] =
            remote.copy(
                revision = server.revision,
                payload =
                    JsonObject(remote.payload!! + ("name" to JsonPrimitive("Серверная программа"))),
            )
        server.failBeforeCommit = true

        try {
          sync.run()
          fail("copy ACK is deliberately interrupted")
        } catch (_: IOException) {}

        val rows = db.routineDao().observeRoutinesFull().first()
        assertEquals(2, rows.size)
        val original = rows.single { it.routine.syncId == "routine" }
        val copy = rows.single { it.routine.syncId != "routine" }
        assertEquals("Серверная программа", original.routine.name)
        assertEquals("Локальная правка", copy.routine.name)
        assertEquals(
            localPayload["exercises"],
            PortableData(raw).snapshot().getValue("routine:${copy.routine.syncId}")["exercises"],
        )
        assertEquals(
            localPayload["gymIds"],
            PortableData(raw).snapshot().getValue("routine:${copy.routine.syncId}")["gymIds"],
        )
        assertEquals(1, tableCount("backend_conflict_copies"))
        assertEquals(1, tableCount("backend_outbox"))
        raw.query(
                "SELECT recordJson FROM backend_baseline WHERE `key`='routine:${copy.routine.syncId}'"
            )
            .use { assertFalse(it.moveToFirst()) }

        BackendSync(db, server, Store()).run()
        assertEquals(2, db.routineDao().observeRoutinesFull().first().size)
        raw.query(
                "SELECT recordJson FROM backend_baseline WHERE `key`='routine:${copy.routine.syncId}'"
            )
            .use { assertTrue(it.moveToFirst()) }
      }

  @Test
  fun `initial merge stays claimed until a fresh post ACK snapshot matches every batch`() =
      runTest {
        SyncSchema.install(raw)
        db.exerciseDao().insert(exercise())
        val server = Server()
        val sync = BackendSync(db, server, Store())
        sync.claim("user-a")
        server.afterCommit = {
          val record = server.records.getValue("exercise:${exercise().syncId}")
          server.revision++
          server.records[record.key] =
              record.copy(
                  revision = server.revision,
                  payload =
                      JsonObject(record.payload!! + ("name" to JsonPrimitive("Новее на сервере"))),
              )
        }

        sync.run()
        assertEquals(GuestSyncPhase.CLAIMED, sync.transfer.value.phase)
        assertEquals(0, tableCount("backend_outbox"))

        sync.run()
        assertEquals(GuestSyncPhase.OWNED, sync.transfer.value.phase)
        assertTrue(sync.transfer.value.initialMergeAcknowledged)
      }

  @Test
  fun `initial merge acknowledges only after all limited batches are durably ACKed`() = runTest {
    SyncSchema.install(raw)
    repeat(1_001) { index ->
      db.bodyMeasurementDao()
          .insert(BodyMeasurementEntity(id = "m-$index", measuredAt = index.toLong()))
    }
    val server = Server()
    val sync = BackendSync(db, server, Store())
    sync.claim("user-a")

    sync.run()

    assertEquals(2, server.operations.size)
    assertEquals(1_001, server.records.count { it.value.kind == "measurement" })
    assertEquals(GuestSyncPhase.OWNED, sync.transfer.value.phase)
    assertEquals(0, tableCount("backend_outbox"))
  }

  @Test
  fun `account replacement preserves catalog journals tombstones rows and A token when blocked`() =
      runTest {
        SyncSchema.install(raw)
        val store = Store()
        val sync = BackendSync(db, Server(), store)
        sync.claim("user-a")
        sync.run()
        val bytes = "{\"operationId\":\"catalog-original\",\"changes\":[]}"
        raw.execSQL(
            "UPDATE catalog_state SET pendingSnapshot='pending',originalOutbox=? WHERE id=1",
            arrayOf(bytes),
        )
        raw.execSQL(
            "INSERT INTO configuration_tombstones(kind,syncId,updatedAt) VALUES('routine','dead',1)"
        )

        try {
          sync.signIn(BackendTokens("user-b", "b@example.com", "b", "b"))
          fail("catalog journals and tombstones must block replacement")
        } catch (error: BackendException) {
          assertEquals("guest_data_preservation_required", error.code)
        }

        assertEquals("user-a", store.session.value?.userId)
        assertEquals("user-a", sync.owner())
        raw.query("SELECT pendingSnapshot,originalOutbox FROM catalog_state WHERE id=1").use {
          assertTrue(it.moveToFirst())
          assertEquals("pending", it.getString(0))
          assertEquals(bytes, it.getString(1))
        }
        assertEquals(1, tableCount("configuration_tombstones"))
      }

  @Test
  fun `active apply owner change and cancellation leave remote data and durable retry safe`() =
      runTest {
        SyncSchema.install(raw)
        val exerciseId = db.exerciseDao().insert(exercise())
        val server = Server()
        val store = Store()
        val sync = BackendSync(db, server, store)
        sync.claim("user-a")
        sync.run()
        val remote = server.records.getValue("exercise:${exercise().syncId}")
        server.revision++
        server.records[remote.key] =
            remote.copy(
                revision = server.revision,
                payload = JsonObject(remote.payload!! + ("name" to JsonPrimitive("Не применять"))),
            )
        server.beforeGet = { insertWorkout("active-during-apply") }
        try {
          sync.run()
          fail("active transaction guard must reject apply")
        } catch (error: BackendException) {
          assertEquals("workout_active", error.code)
        }
        assertEquals("Тест", db.exerciseDao().getById(exerciseId)!!.name)
        raw.execSQL("UPDATE workouts SET finishedAt=2 WHERE id='active-during-apply'")
        server.beforeGet = {
          store.save(BackendTokens("user-b", "b@example.com", "b", "b"))
          raw.execSQL(
              "UPDATE backend_state SET owner='user-b',phase='CLAIMED',mergeId='b' WHERE id=1"
          )
        }
        try {
          sync.run()
          fail("stale A callback must not apply under B")
        } catch (error: BackendException) {
          assertEquals("owner_changed", error.code)
        }
        assertEquals("Тест", db.exerciseDao().getById(exerciseId)!!.name)

        val cancelledStore = Store()
        raw.execSQL(
            "UPDATE backend_state SET owner='user-a',phase='CLAIMED',mergeId='a' WHERE id=1"
        )
        cancelledStore.save(BackendTokens("user-a", "a@example.com", "a", "a"))
        db.bodyMeasurementDao().insert(BodyMeasurementEntity(id = "cancel", measuredAt = 3))
        val cancelled = BackendSync(db, server, cancelledStore)
        server.beforePost = { throw CancellationException("stop") }
        try {
          cancelled.run()
          fail("cancellation must be rethrown")
        } catch (_: CancellationException) {}
        assertEquals(1, tableCount("backend_outbox"))
      }
}
