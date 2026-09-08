package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration15To16Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "catalog-migration-15-16.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `migration preserves local ids muscle maps history and exact pending request`() {
    helper.createDatabase(name, 15).use { db ->
      db.execSQL(
          "INSERT INTO exercises(id,name,muscleGroup,type,isCustom,syncId,updatedAt,needsMuscleMapReview,equipmentRequirementState) VALUES(9,'Присед','LEGS','STRENGTH',1,'00000000-0000-0000-0000-000000000001',5,0,'KNOWN')"
      )
      db.execSQL(
          "INSERT INTO exercise_muscles(exerciseId,muscle,contribution) VALUES(9,'QUADS',100)"
      )
      db.execSQL(
          "INSERT INTO backend_outbox(id,owner,requestJson) VALUES(1,'owner','exact pending bytes')"
      )
      db.execSQL(
          "INSERT INTO workouts(id,routineId,name,startedAt,finishedAt,note,uploadStatus,uploadError) VALUES('history',NULL,'История',10,20,'','UPLOADED',NULL)"
      )
      db.execSQL(
          "INSERT INTO workout_exercises(id,workoutId,exerciseId,sectionId,position) VALUES(6,'history',9,'section',0)"
      )
      db.execSQL(
          "INSERT INTO workout_sets(id,workoutExerciseId,setIndex,weightKg,reps,durationSec,speedKmh,inclinePct,isCompleted,completedAt) VALUES(5,6,0,80.0,5,NULL,NULL,NULL,1,19)"
      )
    }
    helper.runMigrationsAndValidate(name, 16, true, GymDatabase.MIGRATION_15_16).use { db ->
      db.query("SELECT id,origin FROM exercises").use {
        assertTrue(it.moveToFirst())
        assertEquals(9, it.getInt(0))
        assertEquals("PERSONAL", it.getString(1))
      }
      db.query("SELECT requestJson FROM backend_outbox").use {
        assertTrue(it.moveToFirst())
        assertEquals("exact pending bytes", it.getString(0))
      }
      db.query("SELECT sum(weightKg*reps) FROM workout_sets WHERE isCompleted=1").use {
        it.moveToFirst()
        assertEquals(400.0, it.getDouble(0), 0.0)
      }
      db.query("SELECT exerciseId,contribution FROM exercise_muscles").use {
        it.moveToFirst()
        assertEquals(9, it.getInt(0))
        assertEquals(100, it.getInt(1))
      }
      db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
      db.query("SELECT count(*) FROM catalog_equipment").use {
        it.moveToFirst()
        assertEquals(61, it.getInt(0))
      }
    }
  }
}
