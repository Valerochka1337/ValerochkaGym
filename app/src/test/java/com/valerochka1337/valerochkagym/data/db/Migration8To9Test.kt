package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration8To9Test {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val name = "migration-8-9.db"

  @After
  fun tearDown() {
    context.deleteDatabase(name)
  }

  @Test
  fun `migration keeps legacy rows and every completed and incomplete set`() {
    database().use { db ->
      db.execSQL(
          "INSERT INTO exercises VALUES (1, 'Жим', 'CHEST', 'STRENGTH', 1, '11111111-1111-1111-1111-111111111111', 1)"
      )
      db.execSQL(
          "INSERT INTO routines VALUES (1, '22222222-2222-2222-2222-222222222222', 1, 'A', '')"
      )
      db.execSQL("INSERT INTO workouts VALUES ('w', NULL, 'T', 1, NULL, '', 'PENDING', NULL)")
      db.execSQL("INSERT INTO routine_exercises VALUES (1, 1, 1, 0, NULL, '[]')")
      db.execSQL("INSERT INTO workout_exercises VALUES (1, 'w', 1, 0)")
      db.execSQL("INSERT INTO workout_sets VALUES (10, 1, 0, 80.0, 8, NULL, NULL, NULL, 1, 1000)")
      db.execSQL("INSERT INTO workout_sets VALUES (11, 1, 1, 82.5, 6, NULL, NULL, NULL, 0, NULL)")

      GymDatabase.MIGRATION_8_9.migrate(db)

      db.query("SELECT variantSyncId FROM routine_exercises WHERE id = 1").use { cursor ->
        cursor.moveToFirst()
        assertEquals(null, cursor.getString(0))
      }
      db.query(
              "SELECT sectionId, variantSyncId, variantNameSnapshot FROM workout_exercises WHERE id = 1"
          )
          .use { cursor ->
            cursor.moveToFirst()
            assertNotNull(cursor.getString(0))
            assertEquals(null, cursor.getString(1))
            assertEquals(null, cursor.getString(2))
          }
      db.query("SELECT id, isCompleted, completedAt FROM workout_sets ORDER BY id").use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals(10L, cursor.getLong(0))
        assertEquals(1, cursor.getInt(1))
        assertEquals(1000L, cursor.getLong(2))
        assertTrue(cursor.moveToNext())
        assertEquals(11L, cursor.getLong(0))
        assertEquals(0, cursor.getInt(1))
        assertTrue(cursor.isNull(2))
        assertEquals(false, cursor.moveToNext())
      }
    }
  }

  private fun database(): SupportSQLiteDatabase {
    val callback =
        object : SupportSQLiteOpenHelper.Callback(8) {
          override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE exercises (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, muscleGroup TEXT NOT NULL, type TEXT NOT NULL, isCustom INTEGER NOT NULL, syncId TEXT NOT NULL, updatedAt INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE routines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, syncId TEXT NOT NULL, updatedAt INTEGER NOT NULL, name TEXT NOT NULL, note TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE workouts (id TEXT NOT NULL PRIMARY KEY, routineId INTEGER, name TEXT NOT NULL, startedAt INTEGER NOT NULL, finishedAt INTEGER, note TEXT NOT NULL, uploadStatus TEXT NOT NULL, uploadError TEXT)"
            )
            db.execSQL(
                "CREATE TABLE routine_exercises (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, routineId INTEGER NOT NULL, exerciseId INTEGER NOT NULL, position INTEGER NOT NULL, restSeconds INTEGER, plannedSetsJson TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE workout_exercises (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, workoutId TEXT NOT NULL, exerciseId INTEGER NOT NULL, position INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE workout_sets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, workoutExerciseId INTEGER NOT NULL, setIndex INTEGER NOT NULL, weightKg REAL, reps INTEGER, durationSec INTEGER, speedKmh REAL, inclinePct REAL, isCompleted INTEGER NOT NULL, completedAt INTEGER, FOREIGN KEY(workoutExerciseId) REFERENCES workout_exercises(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
          }

          override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
    return FrameworkSQLiteOpenHelperFactory()
        .create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(callback)
                .build(),
        )
        .writableDatabase
  }
}
