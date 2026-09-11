package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration26To27Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "live-coach-26-27.db"

  @After
  fun cleanUp() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `clean v26 adds durable coach contract`() {
    val cleanName = "clean-live-coach-26-27.db"
    try {
      helper.createDatabase(cleanName, 26).use { db ->
        db.execSQL(
            "INSERT INTO exercises(id,name,muscleGroup,type,isCustom,syncId,updatedAt,needsMuscleMapReview,equipmentRequirementState,origin,archived) VALUES(1,'Жим','CHEST','STRENGTH',1,'exercise',0,0,'KNOWN','PERSONAL',0)"
        )
        db.execSQL(
            "INSERT INTO workouts(id,routineId,name,startedAt,finishedAt,note,uploadStatus,uploadError) VALUES('w',NULL,'Тренировка',1,NULL,'','PENDING',NULL)"
        )
        db.execSQL(
            "INSERT INTO workout_exercises(id,workoutId,exerciseId,sectionId,position) VALUES(1,'w',1,'section',0)"
        )
        db.execSQL(
            "INSERT INTO workout_sets(id,workoutExerciseId,setIndex,isCompleted) VALUES(1,1,0,0)"
        )
      }
      helper.runMigrationsAndValidate(cleanName, 27, true, *GymDatabase.ALL_MIGRATIONS).use { db ->
        db.query(
                "SELECT syncId,setType,reportedFeelingsJson,coachMutationRevision FROM workout_sets WHERE id=1"
            )
            .use { row ->
              assertTrue(row.moveToFirst())
              assertNotEquals("", row.getString(0))
              assertEquals("UNKNOWN", row.getString(1))
              assertEquals("[]", row.getString(2))
              assertEquals(0, row.getLong(3))
            }
        db.query("SELECT coachRevision FROM workouts WHERE id='w'").use { row ->
          assertTrue(row.moveToFirst())
          assertEquals(0, row.getLong(0))
        }
      }
    } finally {
      ApplicationProvider.getApplicationContext<Context>().deleteDatabase(cleanName)
    }
  }

  @Test
  fun `duplicate populated set identities fail without changing the original database`() {
    helper.createDatabase(name, 26).use { db ->
      db.execSQL(
          "INSERT INTO exercises(id,name,muscleGroup,type,isCustom,syncId,updatedAt,needsMuscleMapReview,equipmentRequirementState,origin,archived) VALUES(1,'Жим','CHEST','STRENGTH',1,'exercise',0,0,'KNOWN','PERSONAL',0)"
      )
      db.execSQL(
          "INSERT INTO workouts(id,routineId,name,startedAt,finishedAt,note,uploadStatus,uploadError) VALUES('w',NULL,'Тренировка',1,NULL,'','PENDING',NULL)"
      )
      db.execSQL(
          "INSERT INTO workout_exercises(id,workoutId,exerciseId,sectionId,position) VALUES(1,'w',1,'section',0)"
      )
      db.execSQL("ALTER TABLE workout_sets ADD COLUMN syncId TEXT")
      db.execSQL(
          "INSERT INTO workout_sets(id,workoutExerciseId,setIndex,isCompleted,syncId) VALUES(1,1,0,1,'duplicate'),(2,1,1,0,'duplicate')"
      )
    }
    assertThrows(android.database.sqlite.SQLiteException::class.java) {
      helper.runMigrationsAndValidate(name, 27, true, *GymDatabase.ALL_MIGRATIONS).close()
    }
    val file = ApplicationProvider.getApplicationContext<Context>().getDatabasePath(name)
    android.database.sqlite.SQLiteDatabase.openDatabase(
            file.path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        .use { db ->
          assertEquals(26, db.version)
          db.rawQuery("SELECT id,syncId,isCompleted FROM workout_sets ORDER BY id", null).use { rows
            ->
            assertEquals(2, rows.count)
            assertTrue(rows.moveToFirst())
            assertEquals(1, rows.getLong(0))
            assertEquals("duplicate", rows.getString(1))
            assertEquals(1, rows.getInt(2))
            assertTrue(rows.moveToNext())
            assertEquals(2, rows.getLong(0))
            assertEquals("duplicate", rows.getString(1))
            assertEquals(0, rows.getInt(2))
          }
        }
  }

  @Test
  fun `migration assigns immutable set uuid and preserves legacy null semantics`() {
    helper.createDatabase(name, 26).use { db ->
      db.execSQL(
          "INSERT INTO exercises(id,name,muscleGroup,type,isCustom,syncId,updatedAt,needsMuscleMapReview,equipmentRequirementState,origin,archived) VALUES(1,'Жим','CHEST','STRENGTH',1,'exercise',0,0,'KNOWN','PERSONAL',0)"
      )
      db.execSQL(
          "INSERT INTO workouts(id,routineId,name,startedAt,finishedAt,note,uploadStatus,uploadError) VALUES('w',NULL,'Тренировка',1,NULL,'','PENDING',NULL)"
      )
      db.execSQL(
          "INSERT INTO workouts(id,routineId,name,startedAt,finishedAt,note,uploadStatus,uploadError) VALUES('w2',NULL,'Тренировка 2',1,NULL,'','PENDING',NULL)"
      )
      db.execSQL(
          "INSERT INTO workout_exercises(id,workoutId,exerciseId,sectionId,position) VALUES(1,'w',1,'section',0)"
      )
      db.execSQL(
          "INSERT INTO workout_sets(id,workoutExerciseId,setIndex,weightKg,reps,durationSec,speedKmh,inclinePct,isCompleted,completedAt) VALUES(1,1,0,80.0,5,NULL,NULL,NULL,1,2)"
      )
      // Archived draft fields existed without Room defaults. Keep populated data and leave one
      // blank identity for the migration to fill.
      db.execSQL("ALTER TABLE workouts ADD COLUMN coachRevision INTEGER")
      db.execSQL("UPDATE workouts SET coachRevision=9 WHERE id='w'")
      db.execSQL("ALTER TABLE workout_sets ADD COLUMN syncId TEXT")
      db.execSQL("ALTER TABLE workout_sets ADD COLUMN setType TEXT")
      db.execSQL("ALTER TABLE workout_sets ADD COLUMN reportedFeelingsJson TEXT")
      db.execSQL("ALTER TABLE workout_sets ADD COLUMN coachMutationRevision INTEGER")
      db.execSQL("ALTER TABLE workout_sets ADD COLUMN targetWeightKg REAL")
      db.execSQL("ALTER TABLE workout_sets ADD COLUMN actualWeightKg REAL")
      db.execSQL(
          "UPDATE workout_sets SET syncId='preserved-id',setType='WORK',reportedFeelingsJson='[\"FATIGUE\"]',coachMutationRevision=7,targetWeightKg=82.5,actualWeightKg=80.0 WHERE id=1"
      )
      db.execSQL(
          "INSERT INTO workout_sets(id,workoutExerciseId,setIndex,weightKg,reps,isCompleted,syncId,setType,reportedFeelingsJson,coachMutationRevision) VALUES(2,1,1,81.0,6,0,'','UNKNOWN','[]',0)"
      )
      db.execSQL(
          "INSERT INTO workout_sets(id,workoutExerciseId,setIndex,isCompleted,syncId) VALUES(3,1,2,0,NULL)"
      )
      db.execSQL(
          "CREATE TABLE coach_session_context (workoutId TEXT NOT NULL,accountId TEXT NOT NULL,availableTimeMinutes INTEGER,availableTimeEndsAtMillis INTEGER,futureRestSeconds INTEGER,occupiedEquipmentJson TEXT NOT NULL,excludedExerciseIdsJson TEXT NOT NULL,lastUndoPacketJson TEXT,lastUndoRevision INTEGER,initiativeEnabled INTEGER,initiativeAskedExerciseIdsJson TEXT,PRIMARY KEY(workoutId),FOREIGN KEY(workoutId) REFERENCES workouts(id) ON DELETE CASCADE)"
      )
      db.execSQL(
          "INSERT INTO coach_session_context VALUES('w','owner',30,123456,90,'[\"rack\"]','[]',NULL,NULL,0,'[\"saved\"]')"
      )
      db.execSQL(
          "INSERT INTO coach_session_context VALUES('w2','owner',NULL,NULL,NULL,'[]','[]',NULL,NULL,NULL,NULL)"
      )
    }
    helper.runMigrationsAndValidate(name, 27, true, GymDatabase.MIGRATION_26_27).use { db ->
      db.query(
              "SELECT syncId,originalWeightKg,targetWeightKg,actualWeightKg,setType,reportedFeelingsJson,restSnapshotJson,coachMutationRevision FROM workout_sets WHERE id=1"
          )
          .use { row ->
            assertTrue(row.moveToFirst())
            assertEquals("preserved-id", row.getString(0))
            assertTrue(row.isNull(1))
            assertEquals(82.5, row.getDouble(2), 0.0)
            assertEquals(80.0, row.getDouble(3), 0.0)
            assertEquals("WORK", row.getString(4))
            assertEquals("[\"FATIGUE\"]", row.getString(5))
            assertTrue(row.isNull(6))
            assertEquals(7, row.getLong(7))
          }
      db.query("SELECT syncId FROM workout_sets WHERE id=2").use { row ->
        assertTrue(row.moveToFirst())
        assertNotEquals("", row.getString(0))
        assertNotEquals("preserved-id", row.getString(0))
      }
      db.query("SELECT syncId FROM workout_sets WHERE id=3").use { row ->
        assertTrue(row.moveToFirst())
        assertNotEquals("", row.getString(0))
        java.util.UUID.fromString(row.getString(0))
      }
      db.query("SELECT coachRevision FROM workouts WHERE id='w'").use { row ->
        assertTrue(row.moveToFirst())
        assertEquals(9, row.getLong(0))
      }
      db.query("SELECT coachRevision FROM workouts WHERE id='w2'").use { row ->
        assertTrue(row.moveToFirst())
        assertEquals(0, row.getLong(0))
      }
      val defaults = mutableMapOf<String, String?>()
      db.query("PRAGMA table_info(workouts)").use { rows ->
        while (rows.moveToNext()) if (rows.getString(1) == "coachRevision")
            defaults["coachRevision"] = rows.getString(4)
      }
      db.query("PRAGMA table_info(workout_sets)").use { rows ->
        while (rows.moveToNext()) if (
            rows.getString(1) in
                setOf("syncId", "setType", "reportedFeelingsJson", "coachMutationRevision")
        )
            defaults[rows.getString(1)] = rows.getString(4)
      }
      assertEquals("0", defaults["coachRevision"])
      assertEquals("''", defaults["syncId"])
      assertEquals("'UNKNOWN'", defaults["setType"])
      assertEquals("'[]'", defaults["reportedFeelingsJson"])
      assertEquals("0", defaults["coachMutationRevision"])
      // The helper is deliberately idempotent for an interrupted/open retry and retains UUIDs.
      GymDatabase.MIGRATION_26_27.migrate(db)
      db.query("SELECT syncId FROM workout_sets WHERE id=1").use { row ->
        assertTrue(row.moveToFirst())
        assertEquals("preserved-id", row.getString(0))
      }

      db.query(
              "SELECT availableTimeEndsAtMillis,initiativeEnabled,initiativeWelcomed,initiativeAutomaticCount,initiativeLastAutomaticAtMillis,initiativeAskedExerciseIdsJson,initiativeEndReminderSent,initiativePendingInteraction FROM coach_session_context WHERE workoutId='w'"
          )
          .use { row ->
            assertTrue(row.moveToFirst())
            assertEquals(123456, row.getLong(0))
            assertEquals(0, row.getLong(1))
            assertEquals(0, row.getLong(2))
            assertEquals(0, row.getLong(3))
            assertTrue(row.isNull(4))
            assertEquals("[\"saved\"]", row.getString(5))
            assertEquals(0, row.getLong(6))
            assertEquals(0, row.getLong(7))
          }
      db.query(
              "SELECT availableTimeEndsAtMillis,initiativeEnabled,initiativeAskedExerciseIdsJson FROM coach_session_context WHERE workoutId='w2'"
          )
          .use { row ->
            assertTrue(row.moveToFirst())
            assertTrue(row.isNull(0))
            assertEquals(1, row.getLong(1))
            assertEquals("[]", row.getString(2))
          }

      db.query("PRAGMA table_info(coach_journal)").use { columns ->
        val names = buildSet { while (columns.moveToNext()) add(columns.getString(1)) }
        assertTrue("deviceId" in names)
      }
      db.query("PRAGMA table_info(coach_sync_state)").use { columns ->
        val names = buildSet { while (columns.moveToNext()) add(columns.getString(1)) }
        assertTrue("accountId" in names)
        assertTrue("deviceId" in names)
        assertTrue("watermark" in names)
      }
      db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }
  }
}
