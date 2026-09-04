package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opens real v1 and v9 files through the exact shipping migration list and validates v12. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration1To12Test {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val name = "migration-1-12.db"

  @After
  fun tearDown() {
    context.deleteDatabase(name)
  }

  @Test
  fun `room opens v1 data through v12 with section and sets intact`() {
    createV1().use { db ->
      db.execSQL("INSERT INTO exercises VALUES (1, 'Жим', 'CHEST', 'STRENGTH', 1)")
      db.execSQL(
          "INSERT INTO workouts VALUES ('w', NULL, 'Тренировка', 1, NULL, '', 'PENDING', NULL)"
      )
      db.execSQL("INSERT INTO workout_exercises VALUES (1, 'w', 1, 0)")
      db.execSQL("INSERT INTO workout_sets VALUES (1, 1, 0, 70.0, 10, NULL, NULL, NULL, 1)")
    }
    val db =
        Room.databaseBuilder(context, GymDatabase::class.java, name)
            .addMigrations(*GymDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    db.openHelper.writableDatabase
        .query(
            "SELECT sectionId FROM workout_exercises WHERE id = 1",
        )
        .use { cursor ->
          assertTrue(cursor.moveToFirst())
          assertTrue(cursor.getString(0).isNotBlank())
        }
    db.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM workout_sets WHERE workoutExerciseId = 1")
        .use { cursor ->
          assertTrue(cursor.moveToFirst())
          assertEquals(1, cursor.getInt(0))
        }
    db.close()
  }

  @Test
  fun `room opens v9 variant fixture through v10 and v12 preserving every base row and set`() {
    createV1().use { db ->
      db.execSQL("INSERT INTO exercises VALUES (1, 'Жим', 'CHEST', 'STRENGTH', 1)")
      db.execSQL(
          "INSERT INTO workouts VALUES ('w', NULL, 'Тренировка', 1, NULL, '', 'PENDING', NULL)"
      )
      db.execSQL("INSERT INTO workout_exercises VALUES (1, 'w', 1, 0)")
      db.execSQL("INSERT INTO workout_sets VALUES (10, 1, 0, 70.0, 10, 30, 9.5, 4.0, 1)")
      db.execSQL("INSERT INTO workout_sets VALUES (11, 1, 1, 72.5, 8, 45, 8.0, 2.0, 0)")
      listOf(
              GymDatabase.MIGRATION_1_2,
              GymDatabase.MIGRATION_2_3,
              GymDatabase.MIGRATION_3_4,
              GymDatabase.MIGRATION_4_5,
              GymDatabase.MIGRATION_5_6,
              GymDatabase.MIGRATION_6_7,
              GymDatabase.MIGRATION_7_8,
              GymDatabase.MIGRATION_8_9,
          )
          .forEach { it.migrate(db) }
      db.execSQL(
          "INSERT INTO routines VALUES (1, '22222222-2222-2222-2222-222222222222', 2, 'A', '')"
      )
      db.execSQL(
          "INSERT INTO exercise_variants VALUES (1, '33333333-3333-3333-3333-333333333333', 1, 'Узкий хват', 'узкий хват', 0, 3)"
      )
      db.execSQL(
          "INSERT INTO routine_exercises VALUES (2, 1, 1, '33333333-3333-3333-3333-333333333333', 4, 90, '[]')"
      )
      db.execSQL(
          "UPDATE workout_exercises SET variantSyncId = '33333333-3333-3333-3333-333333333333', variantNameSnapshot = 'Узкий хват' WHERE id = 1"
      )
      db.execSQL("UPDATE workout_sets SET completedAt = 1000 WHERE id = 10")
      db.execSQL("PRAGMA user_version = 9")
    }
    val db = openThroughProductionList()
    val sql = db.openHelper.writableDatabase
    sql.query(
            "SELECT id, workoutExerciseId, setIndex, weightKg, reps, durationSec, speedKmh, inclinePct, isCompleted, completedAt FROM workout_sets ORDER BY id"
        )
        .use { cursor ->
          assertTrue(cursor.moveToFirst())
          assertEquals(10L, cursor.getLong(0))
          assertEquals(1L, cursor.getLong(1))
          assertEquals(0, cursor.getInt(2))
          assertEquals(70.0, cursor.getDouble(3), 0.0)
          assertEquals(10, cursor.getInt(4))
          assertEquals(30, cursor.getInt(5))
          assertEquals(9.5, cursor.getDouble(6), 0.0)
          assertEquals(4.0, cursor.getDouble(7), 0.0)
          assertEquals(1, cursor.getInt(8))
          assertEquals(1000L, cursor.getLong(9))
          assertTrue(cursor.moveToNext())
          assertEquals(11L, cursor.getLong(0))
          assertEquals(1L, cursor.getLong(1))
          assertEquals(1, cursor.getInt(2))
          assertEquals(72.5, cursor.getDouble(3), 0.0)
          assertEquals(8, cursor.getInt(4))
          assertEquals(45, cursor.getInt(5))
          assertEquals(8.0, cursor.getDouble(6), 0.0)
          assertEquals(2.0, cursor.getDouble(7), 0.0)
          assertEquals(0, cursor.getInt(8))
          assertTrue(cursor.isNull(9))
        }
    sql.query(
            "SELECT id, routineId, exerciseId, position, restSeconds, plannedSetsJson FROM routine_exercises WHERE id = 2"
        )
        .use { cursor ->
          assertTrue(cursor.moveToFirst())
          assertEquals(2L, cursor.getLong(0))
          assertEquals(1L, cursor.getLong(1))
          assertEquals(1L, cursor.getLong(2))
          assertEquals(4, cursor.getInt(3))
          assertEquals(90, cursor.getInt(4))
          assertEquals("[]", cursor.getString(5))
        }
    sql.query("SELECT sectionId, position FROM workout_exercises WHERE id = 1").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertTrue(cursor.getString(0).isNotBlank())
      assertEquals(0, cursor.getInt(1))
    }
    sql.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'exercise_variants'")
        .use { cursor -> assertTrue(!cursor.moveToFirst()) }
    sql.query("PRAGMA table_info('routine_exercises')").use { cursor ->
      val columns = mutableSetOf<String>()
      while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
      assertTrue("variantSyncId" !in columns)
    }
    sql.query("PRAGMA table_info('workout_exercises')").use { cursor ->
      val columns = mutableSetOf<String>()
      while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
      assertTrue(
          "variantSyncId" !in columns && "variantNameSnapshot" !in columns && "sectionId" in columns
      )
    }
    sql.query("PRAGMA foreign_key_check").use { cursor -> assertTrue(!cursor.moveToFirst()) }
    sql.query("PRAGMA index_list('workout_exercises')").use { cursor ->
      val indexes = mutableSetOf<String>()
      while (cursor.moveToNext()) indexes += cursor.getString(cursor.getColumnIndexOrThrow("name"))
      assertTrue("index_workout_exercises_sectionId" in indexes)
    }
    sql.query("PRAGMA index_list('routine_exercises')").use { cursor ->
      val indexes = mutableSetOf<String>()
      while (cursor.moveToNext()) indexes += cursor.getString(cursor.getColumnIndexOrThrow("name"))
      assertTrue(
          "index_routine_exercises_routineId" in indexes &&
              "index_routine_exercises_exerciseId" in indexes
      )
    }
    sql.query("PRAGMA foreign_key_list('workout_exercises')").use { cursor ->
      val referenced = mutableSetOf<String>()
      while (cursor.moveToNext()) referenced +=
          cursor.getString(cursor.getColumnIndexOrThrow("table"))
      assertTrue("workouts" in referenced && "exercises" in referenced)
    }
    db.close()
  }

  private fun openThroughProductionList() =
      Room.databaseBuilder(context, GymDatabase::class.java, name)
          .addMigrations(*GymDatabase.ALL_MIGRATIONS)
          .allowMainThreadQueries()
          .build()

  private fun createV1(): SupportSQLiteDatabase {
    val callback =
        object : SupportSQLiteOpenHelper.Callback(1) {
          override fun onCreate(db: SupportSQLiteDatabase) = v1Schema.forEach(db::execSQL)

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

  private companion object {
    val v1Schema =
        listOf(
            "CREATE TABLE exercises (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, muscleGroup TEXT NOT NULL, type TEXT NOT NULL, isCustom INTEGER NOT NULL)",
            "CREATE TABLE routines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, note TEXT NOT NULL)",
            "CREATE TABLE routine_exercises (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, routineId INTEGER NOT NULL, exerciseId INTEGER NOT NULL, position INTEGER NOT NULL, restSeconds INTEGER, plannedSetsJson TEXT NOT NULL, FOREIGN KEY(routineId) REFERENCES routines(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
            "CREATE INDEX index_routine_exercises_routineId ON routine_exercises(routineId)",
            "CREATE INDEX index_routine_exercises_exerciseId ON routine_exercises(exerciseId)",
            "CREATE TABLE scheduled_workouts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, routineId INTEGER NOT NULL, dateTimeMillis INTEGER NOT NULL, calendarEventId TEXT NOT NULL, FOREIGN KEY(routineId) REFERENCES routines(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_scheduled_workouts_routineId ON scheduled_workouts(routineId)",
            "CREATE TABLE workouts (id TEXT NOT NULL PRIMARY KEY, routineId INTEGER, name TEXT NOT NULL, startedAt INTEGER NOT NULL, finishedAt INTEGER, note TEXT NOT NULL, uploadStatus TEXT NOT NULL, uploadError TEXT, FOREIGN KEY(routineId) REFERENCES routines(id) ON UPDATE NO ACTION ON DELETE SET NULL)",
            "CREATE INDEX index_workouts_routineId ON workouts(routineId)",
            "CREATE INDEX index_workouts_finishedAt ON workouts(finishedAt)",
            "CREATE TABLE workout_exercises (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, workoutId TEXT NOT NULL, exerciseId INTEGER NOT NULL, position INTEGER NOT NULL, FOREIGN KEY(workoutId) REFERENCES workouts(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
            "CREATE INDEX index_workout_exercises_workoutId ON workout_exercises(workoutId)",
            "CREATE INDEX index_workout_exercises_exerciseId ON workout_exercises(exerciseId)",
            "CREATE TABLE workout_sets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, workoutExerciseId INTEGER NOT NULL, setIndex INTEGER NOT NULL, weightKg REAL, reps INTEGER, durationSec INTEGER, speedKmh REAL, inclinePct REAL, isCompleted INTEGER NOT NULL, FOREIGN KEY(workoutExerciseId) REFERENCES workout_exercises(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_workout_sets_workoutExerciseId ON workout_sets(workoutExerciseId)",
        )
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration9To10Test {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val name = "migration-9-10.db"

  @After
  fun tearDown() {
    context.deleteDatabase(name)
  }

  @Test
  fun `migration removes variant columns while preserving routine sections and sets`() {
    val callback =
        object : SupportSQLiteOpenHelper.Callback(9) {
          override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE exercises (id INTEGER PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE routines (id INTEGER PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE workouts (id TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE exercise_variants (id INTEGER PRIMARY KEY NOT NULL)")
            db.execSQL(
                "CREATE TABLE routine_exercises (id INTEGER PRIMARY KEY NOT NULL, routineId INTEGER NOT NULL, exerciseId INTEGER NOT NULL, variantSyncId TEXT, position INTEGER NOT NULL, restSeconds INTEGER, plannedSetsJson TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE workout_exercises (id INTEGER PRIMARY KEY NOT NULL, workoutId TEXT NOT NULL, exerciseId INTEGER NOT NULL, sectionId TEXT NOT NULL, variantSyncId TEXT, variantNameSnapshot TEXT, position INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE workout_sets (id INTEGER PRIMARY KEY NOT NULL, workoutExerciseId INTEGER NOT NULL, setIndex INTEGER NOT NULL, weightKg REAL, reps INTEGER, durationSec INTEGER, speedKmh REAL, inclinePct REAL, isCompleted INTEGER NOT NULL, completedAt INTEGER)"
            )
          }

          override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
    val sql =
        FrameworkSQLiteOpenHelperFactory()
            .create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(name)
                    .callback(callback)
                    .build(),
            )
            .writableDatabase
    sql.execSQL("INSERT INTO routine_exercises VALUES (7, 3, 4, 'variant', 2, 60, '[]')")
    sql.execSQL(
        "INSERT INTO workout_exercises VALUES (8, 'w', 4, 'section', 'variant', 'Узкий хват', 3)"
    )
    sql.execSQL("INSERT INTO workout_sets VALUES (9, 8, 1, 80, 8, NULL, NULL, NULL, 1, 1000)")
    GymDatabase.MIGRATION_9_10.migrate(sql)
    sql.query("SELECT id, routineId, exerciseId, position, restSeconds FROM routine_exercises")
        .use { cursor ->
          assertTrue(cursor.moveToFirst())
          assertEquals(7L, cursor.getLong(0))
          assertEquals(2, cursor.getInt(3))
          assertEquals(60, cursor.getInt(4))
        }
    sql.query("SELECT sectionId, position FROM workout_exercises").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals("section", cursor.getString(0))
      assertEquals(3, cursor.getInt(1))
    }
    sql.query("SELECT isCompleted, completedAt FROM workout_sets").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(1, cursor.getInt(0))
      assertEquals(1000L, cursor.getLong(1))
    }
    sql.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'exercise_variants'")
        .use { cursor -> assertTrue(!cursor.moveToFirst()) }
    sql.close()
  }
}
