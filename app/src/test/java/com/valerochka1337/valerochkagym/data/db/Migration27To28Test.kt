package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.domain.CommandAuthority
import com.valerochka1337.valerochkagym.domain.CommandResult
import com.valerochka1337.valerochkagym.domain.WorkoutChangeSet
import com.valerochka1337.valerochkagym.domain.WorkoutEditor
import com.valerochka1337.valerochkagym.domain.WorkoutWriteQueue
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import com.valerochka1337.valerochkagym.service.WallClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration27To28Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "remove-coach-occupied-equipment-27-28.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `migration removes occupied context and preserves compatible undo and unrelated proposals`() {
    helper.createDatabase(name, 27).use { db ->
      db.execSQL(
          "INSERT INTO workouts(id,routineId,name,startedAt,finishedAt,note,uploadStatus,uploadError,coachRevision) VALUES('w',NULL,'Тренировка',1,NULL,'','PENDING',NULL,0)"
      )
      db.execSQL(
          "INSERT INTO workouts(id,routineId,name,startedAt,finishedAt,note,uploadStatus,uploadError,coachRevision) VALUES('w2',NULL,'Тренировка 2',1,NULL,'','PENDING',NULL,0)"
      )
      db.execSQL(
          "INSERT INTO coach_session_context(workoutId,accountId,availableTimeMinutes,availableTimeEndsAtMillis,futureRestSeconds,occupiedEquipmentJson,excludedExerciseIdsJson,lastUndoPacketJson,lastUndoRevision,initiativeEnabled,initiativeWelcomed,initiativeAutomaticCount,initiativeLastAutomaticAtMillis,initiativeAskedExerciseIdsJson,initiativeEndReminderSent,initiativePendingInteraction) VALUES('w','owner',30,123,90,'[\"rack\"]','[1]',?,7,0,1,2,456,'[\"asked\"]',1,1)",
          arrayOf(validUndoPacket()),
      )
      db.execSQL(
          "INSERT INTO coach_session_context(workoutId,accountId,occupiedEquipmentJson,excludedExerciseIdsJson,lastUndoPacketJson,lastUndoRevision,initiativeEnabled,initiativeWelcomed,initiativeAutomaticCount,initiativeAskedExerciseIdsJson,initiativeEndReminderSent,initiativePendingInteraction) VALUES('w2','owner','[]','[]',?,3,1,0,0,'[]',0,0)",
          arrayOf(cleanUndoPacket()),
      )
      db.execSQL(
          "INSERT INTO coach_journal(id,accountId,workoutId,createdAt,payload,uploaded,deviceId) VALUES('journal','owner','w',1,'{\"operation\":\"SetOccupiedEquipment\"}',0,'device')"
      )
      insertProposal(db, "retired", "{\"operations\":[${retiredOperation()}]}")
      insertProposal(db, "mixed", "{\"operations\":[${retiredOperation()},${reorderOperation()}]}")
      insertProposal(db, "safe", "{\"operations\":[${reorderOperation()}]}")
      insertProposal(
          db,
          "nested",
          "{\"operations\":[{\"type\":\"com.valerochka1337.valerochkagym.domain.WorkoutChangeSet.Operation.ReorderExercises\",\"sectionIds\":[\"section\"],\"metadata\":{\"type\":\"com.valerochka1337.valerochkagym.domain.WorkoutChangeSet.Operation.SetOccupiedEquipment\"}}]}",
      )
      insertProposal(
          db,
          "text",
          "{\"operations\":[{\"type\":\"com.valerochka1337.valerochkagym.domain.WorkoutChangeSet.Operation.ReorderExercises\",\"sectionIds\":[\"section\"],\"reason\":\"com.valerochka1337.valerochkagym.domain.WorkoutChangeSet.Operation.SetOccupiedEquipment\"}]}",
      )
      insertProposal(db, "confirmed", "{\"operations\":[${retiredOperation()}]}", "CONFIRMED")
    }

    helper.runMigrationsAndValidate(name, 28, true, GymDatabase.MIGRATION_27_28).use { db ->
      db.query(
              "SELECT availableTimeMinutes,availableTimeEndsAtMillis,futureRestSeconds,excludedExerciseIdsJson,lastUndoPacketJson,lastUndoRevision,initiativeEnabled,initiativeWelcomed,initiativeAutomaticCount,initiativeLastAutomaticAtMillis,initiativeAskedExerciseIdsJson,initiativeEndReminderSent,initiativePendingInteraction FROM coach_session_context WHERE workoutId='w'"
          )
          .use { row ->
            assertTrue(row.moveToFirst())
            assertEquals(30, row.getLong(0))
            assertEquals(123, row.getLong(1))
            assertEquals(90, row.getLong(2))
            assertEquals("[1]", row.getString(3))
            assertFalse(row.getString(4).contains("occupiedEquipmentJson"))
            val migratedPacket =
                Json.decodeFromString(
                    WorkoutChangeSet.Packet.serializer(),
                    Json.parseToJsonElement(row.getString(4))
                        .jsonObject
                        .getValue("packet")
                        .toString(),
                )
            assertTrue(
                migratedPacket.operations.single() is WorkoutChangeSet.Operation.RestoreWorkout
            )
            assertEquals(7, row.getLong(5))
            assertEquals(0, row.getLong(6))
            assertEquals(1, row.getLong(7))
            assertEquals(2, row.getLong(8))
            assertEquals(456, row.getLong(9))
            assertEquals("[\"asked\"]", row.getString(10))
            assertEquals(1, row.getLong(11))
            assertEquals(1, row.getLong(12))
          }
      db.query("PRAGMA table_info(coach_session_context)").use { columns ->
        val names = buildSet { while (columns.moveToNext()) add(columns.getString(1)) }
        assertFalse("occupiedEquipmentJson" in names)
      }
      assertEquals(
          cleanUndoPacket(),
          value(db, "SELECT lastUndoPacketJson FROM coach_session_context WHERE workoutId='w2'"),
      )
      db.query("PRAGMA index_list(coach_session_context)").use { indexes ->
        assertTrue(indexes.moveToFirst())
        assertEquals("index_coach_session_context_accountId", indexes.getString(1))
      }
      assertEquals("STALE", proposalState(db, "retired"))
      assertEquals("STALE", proposalState(db, "mixed"))
      assertEquals("PENDING", proposalState(db, "safe"))
      assertEquals("PENDING", proposalState(db, "nested"))
      assertEquals("PENDING", proposalState(db, "text"))
      assertEquals("CONFIRMED", proposalState(db, "confirmed"))
      assertEquals(
          "{\"operation\":\"SetOccupiedEquipment\"}",
          value(db, "SELECT payload FROM coach_journal WHERE id='journal'"),
      )
      db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }
  }

  @Test
  fun `malformed undo clears only undo availability`() {
    helper.createDatabase(name, 27).use { db ->
      db.execSQL(
          "INSERT INTO workouts(id,routineId,name,startedAt,finishedAt,note,uploadStatus,uploadError,coachRevision) VALUES('w',NULL,'Тренировка',1,NULL,'','PENDING',NULL,0)"
      )
      db.execSQL(
          "INSERT INTO coach_session_context(workoutId,accountId,occupiedEquipmentJson,excludedExerciseIdsJson,lastUndoPacketJson,lastUndoRevision,initiativeEnabled,initiativeWelcomed,initiativeAutomaticCount,initiativeAskedExerciseIdsJson,initiativeEndReminderSent,initiativePendingInteraction) VALUES('w','owner','[\"rack\"]','[1]','{broken',7,1,0,0,'[]',0,0)"
      )
    }

    helper.runMigrationsAndValidate(name, 28, true, GymDatabase.MIGRATION_27_28).use { db ->
      db.query(
              "SELECT accountId,excludedExerciseIdsJson,lastUndoPacketJson,lastUndoRevision FROM coach_session_context WHERE workoutId='w'"
          )
          .use { row ->
            assertTrue(row.moveToFirst())
            assertEquals("owner", row.getString(0))
            assertEquals("[1]", row.getString(1))
            assertNull(row.getString(2))
            assertTrue(row.isNull(3))
          }
    }
  }

  @Test
  fun `null undo context stays available after migration`() {
    helper.createDatabase(name, 27).use { db ->
      db.execSQL(
          "INSERT INTO workouts(id,routineId,name,startedAt,finishedAt,note,uploadStatus,uploadError,coachRevision) VALUES('w',NULL,'Тренировка',1,NULL,'','PENDING',NULL,0)"
      )
      db.execSQL(
          "INSERT INTO coach_session_context(workoutId,accountId,occupiedEquipmentJson,excludedExerciseIdsJson,lastUndoPacketJson,lastUndoRevision,initiativeEnabled,initiativeWelcomed,initiativeAutomaticCount,initiativeAskedExerciseIdsJson,initiativeEndReminderSent,initiativePendingInteraction) VALUES('w','owner','[]','[]',?,4,1,0,0,'[]',0,0)",
          arrayOf(nullContextUndoPacket()),
      )
    }

    helper.runMigrationsAndValidate(name, 28, true, GymDatabase.MIGRATION_27_28).use { db ->
      assertEquals(
          nullContextUndoPacket(),
          value(db, "SELECT lastUndoPacketJson FROM coach_session_context WHERE workoutId='w'"),
      )
      assertEquals(
          "4",
          value(db, "SELECT lastUndoRevision FROM coach_session_context WHERE workoutId='w'"),
      )
    }
  }

  @Test
  fun `unaffected pending proposal remains confirmable after migration`() = runBlocking {
    helper.createDatabase(name, 27).use { db ->
      db.execSQL(
          "INSERT INTO workouts(id,routineId,name,startedAt,finishedAt,note,uploadStatus,uploadError,coachRevision) VALUES('w',NULL,'Тренировка',1,NULL,'','PENDING',NULL,0)"
      )
      insertProposal(
          db,
          "safe",
          "{\"operations\":[{\"type\":\"com.valerochka1337.valerochkagym.domain.WorkoutChangeSet.Operation.SetAvailableTime\",\"minutes\":20}]}",
          expiresAt = Long.MAX_VALUE,
      )
    }
    helper.runMigrationsAndValidate(name, 28, true, GymDatabase.MIGRATION_27_28).close()

    val database =
        Room.databaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                GymDatabase::class.java,
                name,
            )
            .addMigrations(
                GymDatabase.MIGRATION_28_29,
                GymDatabase.MIGRATION_29_30,
                GymDatabase.MIGRATION_30_31,
            )
            .allowMainThreadQueries()
            .build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      database.openHelper.writableDatabase.execSQL(
          "INSERT OR REPLACE INTO backend_state (id, owner, generation, phase, initialMergeAcknowledged) VALUES (1, 'owner', 0, 'OWNED', 1)"
      )
      val editor =
          WorkoutEditor(
              database,
              database.workoutDao(),
              database.coachDao(),
              RestTimerEngine(scope, WallClock { 0L }),
              FakeSession(),
              WorkoutWriteQueue(),
          )
      assertEquals(
          CommandResult.APPLIED,
          editor.confirmProposal("owner", "safe", "confirm-safe").result,
      )
      assertEquals("CONFIRMED", proposalState(database.openHelper.writableDatabase, "safe"))
      assertEquals(
          "20",
          value(
              database.openHelper.writableDatabase,
              "SELECT availableTimeMinutes FROM coach_session_context WHERE workoutId='w'",
          ),
      )
    } finally {
      scope.cancel()
      database.close()
    }
  }

  @Test
  fun `migrated legacy undo remains executable`() = runBlocking {
    helper.createDatabase(name, 27).use { db ->
      db.execSQL(
          "INSERT INTO workouts(id,routineId,name,startedAt,finishedAt,note,uploadStatus,uploadError,coachRevision) VALUES('w',NULL,'Тренировка',1,NULL,'','PENDING',NULL,0)"
      )
      db.execSQL(
          "INSERT INTO coach_session_context(workoutId,accountId,occupiedEquipmentJson,excludedExerciseIdsJson,lastUndoPacketJson,lastUndoRevision,initiativeEnabled,initiativeWelcomed,initiativeAutomaticCount,initiativeAskedExerciseIdsJson,initiativeEndReminderSent,initiativePendingInteraction) VALUES('w','owner','[\"rack\"]','[]',?,0,1,0,0,'[]',0,0)",
          arrayOf(validUndoPacket()),
      )
    }
    helper.runMigrationsAndValidate(name, 28, true, GymDatabase.MIGRATION_27_28).close()

    val database =
        Room.databaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                GymDatabase::class.java,
                name,
            )
            .addMigrations(
                GymDatabase.MIGRATION_28_29,
                GymDatabase.MIGRATION_29_30,
                GymDatabase.MIGRATION_30_31,
            )
            .allowMainThreadQueries()
            .build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      database.openHelper.writableDatabase.execSQL(
          "INSERT OR REPLACE INTO backend_state (id, owner, generation, phase, initialMergeAcknowledged) VALUES (1, 'owner', 0, 'OWNED', 1)"
      )
      val editor =
          WorkoutEditor(
              database,
              database.workoutDao(),
              database.coachDao(),
              RestTimerEngine(scope, WallClock { 0L }),
              FakeSession(),
              WorkoutWriteQueue(),
          )
      val undo = WorkoutChangeSet.Packet(listOf(WorkoutChangeSet.Operation.UndoLast))
      assertEquals(
          CommandResult.APPLIED,
          editor
              .submit(
                  "owner",
                  "w",
                  "undo-legacy",
                  0,
                  undo,
                  CommandAuthority.local(undo, CommandAuthority.Anchors(null, null, null)),
              )
              .result,
      )
      assertNull(database.coachDao().context("w")!!.lastUndoPacketJson)
    } finally {
      scope.cancel()
      database.close()
    }
  }

  private fun insertProposal(
      db: androidx.sqlite.db.SupportSQLiteDatabase,
      id: String,
      packetJson: String,
      state: String = "PENDING",
      expiresAt: Long = 999999999,
  ) {
    db.execSQL(
        "INSERT INTO coach_proposals(id,accountId,workoutId,baseRevision,beforeSummary,afterSummary,packetJson,expiresAt,state) VALUES(?,?, 'w',0,'before','after',?,?,?)",
        arrayOf<Any?>(id, "owner", packetJson, expiresAt, state),
    )
  }

  private fun proposalState(db: androidx.sqlite.db.SupportSQLiteDatabase, id: String) =
      value(db, "SELECT state FROM coach_proposals WHERE id='$id'")

  private fun value(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): String =
      db.query(sql).use { rows ->
        assertTrue(rows.moveToFirst())
        rows.getString(0)
      }

  private fun validUndoPacket() =
      """{"packet":{"operations":[${restoreOperation()}]},"context":{"availableTimeMinutes":30,"availableTimeEndsAtMillis":123,"futureRestSeconds":90,"occupiedEquipmentJson":"[\"rack\"]","excludedExerciseIdsJson":"[1]"}}"""

  private fun cleanUndoPacket() =
      """{"packet":{"operations":[${restoreOperation()}]},"context":{"availableTimeMinutes":30,"availableTimeEndsAtMillis":123,"futureRestSeconds":90,"excludedExerciseIdsJson":"[1]"}}"""

  private fun nullContextUndoPacket() =
      """{"packet":{"operations":[${restoreOperation()}]},"context":null}"""

  private fun restoreOperation() =
      """{"type":"com.valerochka1337.valerochkagym.domain.WorkoutChangeSet.Operation.RestoreWorkout","sections":[]}"""

  private fun retiredOperation() =
      """{"type":"com.valerochka1337.valerochkagym.domain.WorkoutChangeSet.Operation.SetOccupiedEquipment","equipmentIds":["rack"]}"""

  private fun reorderOperation() =
      """{"type":"com.valerochka1337.valerochkagym.domain.WorkoutChangeSet.Operation.ReorderExercises","sectionIds":["section"]}"""

  private class FakeSession : BackendSessionStore {
    override val session =
        MutableStateFlow<BackendTokens?>(
            BackendTokens("owner", "owner@example.com", "access", "refresh")
        )

    override fun save(tokens: BackendTokens?) {
      session.value = tokens
    }
  }
}
