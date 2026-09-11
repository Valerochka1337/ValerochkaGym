package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Archived pre-rebase Live Coach v17 shape, intentionally different from upstream Room v17. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MigrationLegacyLiveCoach17To27Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "legacy-live-coach-17-27.db"

  @After
  fun cleanUp() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `archived coach v17 creates missing calendar links and preserves durable coach facts`() {
    verifyArchivedUpgrade(calendarAlreadyRecovered = false)
  }

  @Test
  fun `partly recovered calendar links retain their owner during archived coach upgrade`() {
    verifyArchivedUpgrade(calendarAlreadyRecovered = true)
  }

  private fun verifyArchivedUpgrade(calendarAlreadyRecovered: Boolean) {
    helper.createDatabase(name, 17).use { db ->
      if (!calendarAlreadyRecovered) db.execSQL("DROP TABLE calendar_event_account_links")
      // Reconstruct the archived NOT NULL column definition before seeding any child rows.
      db.execSQL("DROP TABLE workouts")
      db.execSQL(
          "CREATE TABLE workouts (id TEXT NOT NULL,routineId INTEGER,name TEXT NOT NULL,startedAt INTEGER NOT NULL,finishedAt INTEGER,note TEXT NOT NULL,uploadStatus TEXT NOT NULL,uploadError TEXT,coachRevision INTEGER NOT NULL,PRIMARY KEY(id),FOREIGN KEY(routineId) REFERENCES routines(id) ON UPDATE NO ACTION ON DELETE SET NULL)"
      )
      db.execSQL("CREATE INDEX index_workouts_routineId ON workouts(routineId)")
      db.execSQL("CREATE INDEX index_workouts_finishedAt ON workouts(finishedAt)")
      db.execSQL("INSERT INTO backend_state(id,owner,generation) VALUES(1,NULL,0)")
      db.execSQL(
          "INSERT INTO routines(id,syncId,updatedAt,name,note,origin,archived) VALUES(1,'routine',0,'План','', 'PERSONAL',0)"
      )
      db.execSQL(
          "INSERT INTO scheduled_workouts(id,routineId,dateTimeMillis,calendarEventId) VALUES(1,1,1,'event')"
      )
      db.execSQL(
          "INSERT INTO scheduled_workouts(id,routineId,dateTimeMillis,calendarEventId) VALUES(2,1,2,'event-2')"
      )
      if (calendarAlreadyRecovered)
          db.execSQL(
              "INSERT INTO calendar_event_account_links(scheduledWorkoutId,ownerEmail,state) VALUES(1,'owner@example.com','OWNED')"
          )
      db.execSQL(
          "INSERT INTO exercises(id,name,muscleGroup,type,isCustom,syncId,updatedAt,needsMuscleMapReview,equipmentRequirementState,origin,archived) VALUES(1,'Жим','CHEST','STRENGTH',1,'exercise',0,0,'KNOWN','PERSONAL',0)"
      )
      db.execSQL(
          "INSERT INTO workouts(id,routineId,name,startedAt,finishedAt,note,uploadStatus,uploadError,coachRevision) VALUES('w',NULL,'Тренировка',1,NULL,'','PENDING',NULL,11)"
      )
      db.execSQL(
          "INSERT INTO workout_exercises(id,workoutId,exerciseId,sectionId,position) VALUES(1,'w',1,'section',0)"
      )
      db.execSQL(
          "INSERT INTO workout_sets(id,workoutExerciseId,setIndex,weightKg,reps,isCompleted,completedAt) VALUES(1,1,0,80.0,5,1,2)"
      )
      installArchivedCoachShape(db)
    }

    helper.runMigrationsAndValidate(name, 27, true, *GymDatabase.ALL_MIGRATIONS).use { db ->
      db.query("SELECT coachRevision FROM workouts WHERE id='w'").use { rows ->
        assertTrue(rows.moveToFirst())
        assertEquals(11, rows.getLong(0))
      }
      db.query("SELECT sectionId,position FROM workout_exercises WHERE id=1").use { rows ->
        assertTrue(rows.moveToFirst())
        assertEquals("section", rows.getString(0))
        assertEquals(0, rows.getLong(1))
      }
      db.query("SELECT name,type,syncId FROM exercises WHERE id=1").use { rows ->
        assertTrue(rows.moveToFirst())
        assertEquals("Жим", rows.getString(0))
        assertEquals("STRENGTH", rows.getString(1))
        assertEquals("exercise", rows.getString(2))
      }
      db.query(
              "SELECT syncId,targetWeightKg,actualWeightKg,setType,reportedFeelingsJson,coachMutationRevision FROM workout_sets WHERE id=1"
          )
          .use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("archived-set", rows.getString(0))
            assertEquals(82.5, rows.getDouble(1), 0.0)
            assertEquals(80.0, rows.getDouble(2), 0.0)
            assertEquals("WORK", rows.getString(3))
            assertEquals("[\"FATIGUE\"]", rows.getString(4))
            assertEquals(7, rows.getLong(5))
          }
      db.query(
              "SELECT ownerEmail,state FROM calendar_event_account_links WHERE scheduledWorkoutId=1"
          )
          .use { rows ->
            assertTrue(rows.moveToFirst())
            if (calendarAlreadyRecovered) {
              assertEquals("owner@example.com", rows.getString(0))
              assertEquals("OWNED", rows.getString(1))
            } else {
              assertTrue(rows.isNull(0))
              assertEquals("LEGACY_OWNER_UNKNOWN", rows.getString(1))
            }
          }
      db.query(
              "SELECT ownerEmail,state FROM calendar_event_account_links WHERE scheduledWorkoutId=2"
          )
          .use { rows ->
            assertTrue(rows.moveToFirst())
            assertTrue(rows.isNull(0))
            assertEquals("LEGACY_OWNER_UNKNOWN", rows.getString(1))
          }
      db.query("SELECT text FROM coach_messages WHERE id='message'").use { rows ->
        assertTrue(rows.moveToFirst())
        assertEquals("сохранённый ответ", rows.getString(0))
      }
      db.query("SELECT state FROM coach_proposals WHERE id='proposal'").use { rows ->
        assertTrue(rows.moveToFirst())
        assertEquals("PENDING", rows.getString(0))
      }
      db.query("SELECT result FROM coach_command_receipts WHERE operationId='receipt'").use { rows
        ->
        assertTrue(rows.moveToFirst())
        assertEquals("APPLIED", rows.getString(0))
      }
      db.query("SELECT payload,deviceId FROM coach_journal WHERE id='journal'").use { rows ->
        assertTrue(rows.moveToFirst())
        assertEquals("{\"kind\":\"message\"}", rows.getString(0))
        assertEquals("device", rows.getString(1))
      }
      db.query("SELECT deviceId,watermark FROM coach_sync_state WHERE accountId='owner'").use { rows
        ->
        assertTrue(rows.moveToFirst())
        assertEquals("device", rows.getString(0))
        assertEquals(4, rows.getLong(1))
      }
      db.query(
              "SELECT availableTimeMinutes,availableTimeEndsAtMillis,initiativeEnabled,initiativeWelcomed,initiativeAutomaticCount,initiativeLastAutomaticAtMillis,initiativeAskedExerciseIdsJson,initiativeEndReminderSent,initiativePendingInteraction FROM coach_session_context WHERE workoutId='w'"
          )
          .use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(35, rows.getLong(0))
            assertTrue(rows.isNull(1))
            assertEquals(1, rows.getLong(2))
            assertEquals(0, rows.getLong(3))
            assertEquals(0, rows.getLong(4))
            assertTrue(rows.isNull(5))
            assertEquals("[]", rows.getString(6))
            assertEquals(0, rows.getLong(7))
            assertEquals(0, rows.getLong(8))
          }
      try {
        db.execSQL(
            "UPDATE calendar_event_account_links SET state='LEGACY_OWNER_UNKNOWN' WHERE scheduledWorkoutId=1"
        )
        fail("Calendar link trigger must keep an owned link immutable")
      } catch (_: android.database.sqlite.SQLiteException) {
        // Trigger installed by the collision recovery remains active after the full route.
      }
      db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }
  }

  private fun installArchivedCoachShape(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    // Exact archived set shape: twenty coach columns are NOT NULL where required but had no
    // SQL defaults, which is what collides with Room's v27 validator.
    db.execSQL("DROP TABLE workout_sets")
    db.execSQL(
        "CREATE TABLE workout_sets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,workoutExerciseId INTEGER NOT NULL,setIndex INTEGER NOT NULL,weightKg REAL,reps INTEGER,durationSec INTEGER,speedKmh REAL,inclinePct REAL,isCompleted INTEGER NOT NULL,completedAt INTEGER,syncId TEXT NOT NULL,originalWeightKg REAL,originalReps INTEGER,originalDurationSec INTEGER,originalSpeedKmh REAL,originalInclinePct REAL,targetWeightKg REAL,targetReps INTEGER,targetDurationSec INTEGER,targetSpeedKmh REAL,targetInclinePct REAL,actualWeightKg REAL,actualReps INTEGER,actualDurationSec INTEGER,actualSpeedKmh REAL,actualInclinePct REAL,setType TEXT NOT NULL,reportedFeelingsJson TEXT NOT NULL,restSnapshotJson TEXT,coachMutationRevision INTEGER NOT NULL,FOREIGN KEY(workoutExerciseId) REFERENCES workout_exercises(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    db.execSQL(
        "INSERT INTO workout_sets(id,workoutExerciseId,setIndex,weightKg,reps,durationSec,speedKmh,inclinePct,isCompleted,completedAt,syncId,originalWeightKg,originalReps,originalDurationSec,originalSpeedKmh,originalInclinePct,targetWeightKg,targetReps,targetDurationSec,targetSpeedKmh,targetInclinePct,actualWeightKg,actualReps,actualDurationSec,actualSpeedKmh,actualInclinePct,setType,reportedFeelingsJson,restSnapshotJson,coachMutationRevision) VALUES(1,1,0,80.0,5,NULL,NULL,NULL,1,2,'archived-set',75.0,5,NULL,NULL,NULL,82.5,6,NULL,NULL,NULL,80.0,5,NULL,NULL,NULL,'WORK','[\"FATIGUE\"]','{\"plannedSeconds\":90}',7)"
    )
    db.execSQL("CREATE UNIQUE INDEX index_workout_sets_syncId ON workout_sets(syncId)")
    db.execSQL(
        "CREATE INDEX index_workout_sets_workoutExerciseId ON workout_sets(workoutExerciseId)"
    )
    db.execSQL(
        "CREATE TABLE coach_messages (id TEXT NOT NULL,accountId TEXT NOT NULL,workoutId TEXT NOT NULL,role TEXT NOT NULL,text TEXT NOT NULL,createdAt INTEGER NOT NULL,status TEXT NOT NULL,PRIMARY KEY(id),FOREIGN KEY(workoutId) REFERENCES workouts(id) ON DELETE CASCADE)"
    )
    db.execSQL(
        "CREATE TABLE coach_proposals (id TEXT NOT NULL,accountId TEXT NOT NULL,workoutId TEXT NOT NULL,baseRevision INTEGER NOT NULL,beforeSummary TEXT NOT NULL,afterSummary TEXT NOT NULL,packetJson TEXT NOT NULL,expiresAt INTEGER NOT NULL,state TEXT NOT NULL,PRIMARY KEY(id),FOREIGN KEY(workoutId) REFERENCES workouts(id) ON DELETE CASCADE)"
    )
    db.execSQL(
        "CREATE TABLE coach_command_receipts (operationId TEXT NOT NULL,accountId TEXT NOT NULL,workoutId TEXT NOT NULL,revision INTEGER NOT NULL,result TEXT NOT NULL,createdAt INTEGER NOT NULL,PRIMARY KEY(operationId),FOREIGN KEY(workoutId) REFERENCES workouts(id) ON DELETE CASCADE)"
    )
    db.execSQL(
        "CREATE TABLE coach_journal (id TEXT NOT NULL,accountId TEXT NOT NULL,workoutId TEXT NOT NULL,createdAt INTEGER NOT NULL,payload TEXT NOT NULL,uploaded INTEGER NOT NULL,deviceId TEXT,PRIMARY KEY(id),FOREIGN KEY(workoutId) REFERENCES workouts(id) ON DELETE CASCADE)"
    )
    db.execSQL(
        "CREATE TABLE coach_sync_state (accountId TEXT NOT NULL,deviceId TEXT NOT NULL,watermark INTEGER NOT NULL,PRIMARY KEY(accountId))"
    )
    db.execSQL(
        "CREATE TABLE coach_session_context (workoutId TEXT NOT NULL,accountId TEXT NOT NULL,availableTimeMinutes INTEGER,futureRestSeconds INTEGER,occupiedEquipmentJson TEXT NOT NULL,excludedExerciseIdsJson TEXT NOT NULL,lastUndoPacketJson TEXT,lastUndoRevision INTEGER,PRIMARY KEY(workoutId),FOREIGN KEY(workoutId) REFERENCES workouts(id) ON DELETE CASCADE)"
    )
    db.execSQL(
        "INSERT INTO coach_messages VALUES('message','owner','w','assistant','сохранённый ответ',1,'DELIVERED')"
    )
    db.execSQL(
        "INSERT INTO coach_proposals VALUES('proposal','owner','w',11,'было','стало','{}',100,'PENDING')"
    )
    db.execSQL("INSERT INTO coach_command_receipts VALUES('receipt','owner','w',11,'APPLIED',2)")
    db.execSQL(
        "INSERT INTO coach_journal VALUES('journal','owner','w',3,'{\"kind\":\"message\"}',0,'device')"
    )
    db.execSQL("INSERT INTO coach_sync_state VALUES('owner','device',4)")
    db.execSQL(
        "INSERT INTO coach_session_context VALUES('w','owner',35,90,'[\"rack\"]','[]',NULL,NULL)"
    )
  }
}
