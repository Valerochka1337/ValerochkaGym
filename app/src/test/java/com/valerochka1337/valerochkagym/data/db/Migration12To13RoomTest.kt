package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the committed v12 schema, not a hand-built approximation of it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration12To13RoomTest {
  @get:Rule
  val helper =
      MigrationTestHelper(
          InstrumentationRegistry.getInstrumentation(),
          GymDatabase::class.java,
      )

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val name = "migration-12-13-room.db"

  @After
  fun cleanup() {
    context.deleteDatabase(name)
  }

  @Test
  fun `v12 database migrates through production list without changing protected workout records`() {
    helper.createDatabase(name, 12).use { database ->
      assertFalse(database.hasColumn("exercises", "needsMuscleMapReview"))
      assertFalse(database.hasTable("muscle_load_upgrade_notice"))

      database.execSQL(
          "INSERT INTO exercises(id,name,muscleGroup,type,isCustom,syncId,updatedAt) " +
              "VALUES(99,'Своё','CHEST','STRENGTH',1,'exercise-sync',123)",
      )
      database.execSQL(
          "INSERT INTO exercise_muscles(exerciseId,muscle,contribution) VALUES " +
              "(99,'ABS',0),(99,'BICEPS',1),(99,'TRICEPS',25),(99,'BACK',60),(99,'CHEST',70)",
      )
      database.execSQL("INSERT INTO gyms(id,syncId,updatedAt,name) VALUES(10,'gym-sync',456,'Зал')")
      database.execSQL("INSERT INTO gym_exercises(gymId,exerciseId) VALUES(10,99)")
      database.execSQL(
          "INSERT INTO routines(id,syncId,updatedAt,name,note) VALUES(20,'routine-sync',789,'Программа','Заметка')",
      )
      database.execSQL("INSERT INTO routine_gyms(routineId,gymId) VALUES(20,10)")
      database.execSQL(
          "INSERT INTO routine_exercises(id,routineId,exerciseId,position,restSeconds,plannedSetsJson) " +
              "VALUES(30,20,99,2,90,'[{\"reps\":5}]')",
      )
      database.execSQL(
          "INSERT INTO workouts(id,routineId,name,startedAt,finishedAt,note,uploadStatus,uploadError) " +
              "VALUES('workout-1',20,'История',1000,2000,'Заметка тренировки','PENDING',NULL)",
      )
      database.execSQL("INSERT INTO workout_gyms(workoutId,gymId) VALUES('workout-1',10)")
      database.execSQL(
          "INSERT INTO workout_exercises(id,workoutId,exerciseId,sectionId,position) " +
              "VALUES(40,'workout-1',99,'section-1',3)",
      )
      database.execSQL(
          "INSERT INTO workout_sets(id,workoutExerciseId,setIndex,weightKg,reps,durationSec,speedKmh,inclinePct,isCompleted,completedAt) " +
              "VALUES(50,40,4,87.5,6,NULL,NULL,NULL,1,1500)",
      )
    }

    helper.runMigrationsAndValidate(name, 13, true, *GymDatabase.ALL_MIGRATIONS).use { database ->
      assertEquals(
          listOf(
              "BACK" to 100,
              "BICEPS" to 0,
              "LOWER_CHEST" to 100,
              "TRICEPS" to 50,
              "UPPER_CHEST" to 100,
          ),
          database
              .query(
                  "SELECT muscle, contribution FROM exercise_muscles WHERE exerciseId = 99 ORDER BY muscle"
              )
              .rows { getString(0) to getInt(1) },
      )
      assertEquals(
          1,
          database.singleInt("SELECT needsMuscleMapReview FROM exercises WHERE id = 99"),
      )
      assertEquals(1, database.singleInt("SELECT id FROM muscle_load_upgrade_notice"))

      assertEquals(
          listOf("Своё", "CHEST", "STRENGTH", 1, "exercise-sync", 123L),
          database
              .query(
                  "SELECT name,muscleGroup,type,isCustom,syncId,updatedAt FROM exercises WHERE id = 99"
              )
              .rows {
                listOf(
                    getString(0),
                    getString(1),
                    getString(2),
                    getInt(3),
                    getString(4),
                    getLong(5),
                )
              }
              .single(),
      )
      assertEquals(
          listOf(10L, "gym-sync", 456L, "Зал"),
          database
              .query("SELECT id,syncId,updatedAt,name FROM gyms WHERE id = 10")
              .rows { listOf(getLong(0), getString(1), getLong(2), getString(3)) }
              .single(),
      )
      assertEquals(
          listOf(10L to 99L),
          database.query("SELECT gymId,exerciseId FROM gym_exercises").rows {
            getLong(0) to getLong(1)
          },
      )
      assertEquals(
          listOf(20L, "routine-sync", 789L, "Программа", "Заметка"),
          database
              .query("SELECT id,syncId,updatedAt,name,note FROM routines WHERE id = 20")
              .rows { listOf(getLong(0), getString(1), getLong(2), getString(3), getString(4)) }
              .single(),
      )
      assertEquals(
          listOf(20L to 10L),
          database.query("SELECT routineId,gymId FROM routine_gyms").rows {
            getLong(0) to getLong(1)
          },
      )
      assertEquals(
          listOf(30L, 20L, 99L, 2, 90, "[{\"reps\":5}]"),
          database
              .query(
                  "SELECT id,routineId,exerciseId,position,restSeconds,plannedSetsJson FROM routine_exercises WHERE id = 30"
              )
              .rows {
                listOf(getLong(0), getLong(1), getLong(2), getInt(3), getInt(4), getString(5))
              }
              .single(),
      )
      assertEquals(
          listOf("workout-1" to 10L),
          database.query("SELECT workoutId,gymId FROM workout_gyms").rows {
            getString(0) to getLong(1)
          },
      )
      assertEquals(
          listOf("workout-1", 20L, "История", 1000L, 2000L, "Заметка тренировки", "PENDING", null),
          database
              .query(
                  "SELECT id,routineId,name,startedAt,finishedAt,note,uploadStatus,uploadError FROM workouts WHERE id = 'workout-1'"
              )
              .rows {
                listOf(
                    getString(0),
                    getLong(1),
                    getString(2),
                    getLong(3),
                    getLong(4),
                    getString(5),
                    getString(6),
                    if (isNull(7)) null else getString(7),
                )
              }
              .single(),
      )
      assertEquals(
          listOf(40L, "workout-1", 99L, "section-1", 3),
          database
              .query(
                  "SELECT id,workoutId,exerciseId,sectionId,position FROM workout_exercises WHERE id = 40"
              )
              .rows { listOf(getLong(0), getString(1), getLong(2), getString(3), getInt(4)) }
              .single(),
      )
      assertEquals(
          listOf(50L, 40L, 4, 87.5, 6, 1, 1500L),
          database
              .query(
                  "SELECT id,workoutExerciseId,setIndex,weightKg,reps,isCompleted,completedAt FROM workout_sets WHERE id = 50"
              )
              .rows {
                listOf(
                    getLong(0),
                    getLong(1),
                    getInt(2),
                    getDouble(3),
                    getInt(4),
                    getInt(5),
                    getLong(6),
                )
              }
              .single(),
      )
      database.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
  }

  private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
      query("PRAGMA table_info($table)").use { columns ->
        generateSequence { if (columns.moveToNext()) columns.getString(1) else null }
            .any { it == column }
      }

  private fun SupportSQLiteDatabase.hasTable(table: String): Boolean =
      singleInt(
          "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$table')"
      ) == 1

  private fun SupportSQLiteDatabase.singleInt(query: String): Int =
      query(query).use { row ->
        assertTrue(row.moveToFirst())
        row.getInt(0)
      }

  private fun <T> android.database.Cursor.rows(
      transform: android.database.Cursor.() -> T
  ): List<T> = buildList { while (moveToNext()) add(transform()) }
}
