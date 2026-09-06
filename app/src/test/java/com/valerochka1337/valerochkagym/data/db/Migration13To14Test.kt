package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration13To14Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val name = "migration-13-14.db"
  private val oldestName = "migration-1-14.db"

  @After
  fun cleanup() {
    context.deleteDatabase(name)
    context.deleteDatabase(oldestName)
  }

  @Test
  fun `oldest supported schema reaches v14 through the production migration registry`() {
    helper.createDatabase(oldestName, 1).close()
    helper.runMigrationsAndValidate(oldestName, 14, true, *GymDatabase.ALL_MIGRATIONS).use { db ->
      db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='gym_equipment'").use {
          cursor ->
        assertEquals(true, cursor.moveToFirst())
      }
      db.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
    }
  }

  @Test
  fun `migration preserves legacy links routine and workout history and starts unknown custom requirements`() {
    helper.createDatabase(name, 13).use { db ->
      db.execSQL(
          "INSERT INTO exercises(id,name,muscleGroup,type,isCustom,syncId,updatedAt,needsMuscleMapReview) VALUES(9,'Своё','CHEST','STRENGTH',1,'custom',5,0)"
      )
      db.execSQL("INSERT INTO gyms(id,syncId,updatedAt,name) VALUES(4,'gym',6,'Зал')")
      db.execSQL("INSERT INTO gym_exercises(gymId,exerciseId) VALUES(4,9)")
      db.execSQL(
          "INSERT INTO routines(id,syncId,updatedAt,name,note) VALUES(3,'routine',7,'План','')"
      )
      db.execSQL("INSERT INTO routine_gyms(routineId,gymId) VALUES(3,4)")
      db.execSQL(
          "INSERT INTO routine_exercises(id,routineId,exerciseId,position,restSeconds,plannedSetsJson) VALUES(8,3,9,0,60,'[]')"
      )
      db.execSQL(
          "INSERT INTO workouts(id,routineId,name,startedAt,finishedAt,note,uploadStatus,uploadError) VALUES('workout',3,'История',10,20,'','UPLOADED',NULL)"
      )
      db.execSQL("INSERT INTO workout_gyms(workoutId,gymId) VALUES('workout',4)")
      db.execSQL(
          "INSERT INTO workout_exercises(id,workoutId,exerciseId,sectionId,position) VALUES(6,'workout',9,'section',0)"
      )
      db.execSQL(
          "INSERT INTO workout_sets(id,workoutExerciseId,setIndex,weightKg,reps,durationSec,speedKmh,inclinePct,isCompleted,completedAt) VALUES(5,6,0,80.0,5,NULL,NULL,NULL,1,19)"
      )
    }
    helper.runMigrationsAndValidate(name, 14, true, *GymDatabase.ALL_MIGRATIONS).use { db ->
      db.query("SELECT inventoryConfigured FROM gyms WHERE id=4").use { cursor ->
        cursor.moveToFirst()
        assertEquals(0, cursor.getInt(0))
      }
      db.query("SELECT equipmentRequirementState FROM exercises WHERE id=9").use { cursor ->
        cursor.moveToFirst()
        assertEquals("UNKNOWN", cursor.getString(0))
      }
      db.query("SELECT COUNT(*) FROM gym_exercises WHERE gymId=4 AND exerciseId=9").use { cursor ->
        cursor.moveToFirst()
        assertEquals(1, cursor.getInt(0))
      }
      db.query("SELECT COUNT(*) FROM gym_equipment").use { cursor ->
        cursor.moveToFirst()
        assertEquals(0, cursor.getInt(0))
      }
      db.query("SELECT routineId,exerciseId FROM routine_exercises WHERE id=8").use { cursor ->
        cursor.moveToFirst()
        assertEquals(3, cursor.getLong(0))
        assertEquals(9, cursor.getLong(1))
      }
      db.query(
              "SELECT workoutExerciseId,weightKg,reps,isCompleted,completedAt FROM workout_sets WHERE id=5"
          )
          .use { cursor ->
            cursor.moveToFirst()
            assertEquals(6, cursor.getLong(0))
            assertEquals(80.0, cursor.getDouble(1), 0.0)
            assertEquals(5, cursor.getInt(2))
            assertEquals(1, cursor.getInt(3))
            assertEquals(19, cursor.getLong(4))
          }
      db.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
    }
  }
}
