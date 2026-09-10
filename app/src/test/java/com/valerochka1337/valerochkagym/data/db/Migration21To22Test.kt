package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration21To22Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "workout-notes-21-22.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `migration gives existing sets an empty note and creates personal hints`() {
    helper.createDatabase(name, 21).use { db ->
      db.execSQL(
          "INSERT INTO workouts(id,name,startedAt,note,uploadStatus) VALUES('w','W',1,'','PENDING')"
      )
      db.execSQL(
          "INSERT INTO exercises(id,name,muscleGroup,type,isCustom,syncId,updatedAt,needsMuscleMapReview,equipmentRequirementState,origin,archived) VALUES(1,'E','CHEST','STRENGTH',0,'exercise',1,0,'KNOWN','PERSONAL',0)"
      )
      db.execSQL(
          "INSERT INTO workout_exercises(id,workoutId,exerciseId,sectionId,position) VALUES(1,'w',1,'section',0)"
      )
      db.execSQL(
          "INSERT INTO workout_sets(id,workoutExerciseId,setIndex,isCompleted) VALUES(1,1,0,0)"
      )
    }
    helper.runMigrationsAndValidate(name, 22, true, GymDatabase.MIGRATION_21_22).use { db ->
      db.query("SELECT note FROM workout_sets WHERE id=1").use {
        assertTrue(it.moveToFirst())
        assertEquals("", it.getString(0))
      }
      db.execSQL(
          "INSERT INTO exercise_personal_hints(exerciseSyncId,text,updatedAt) VALUES('exercise','cue',2)"
      )
      db.query(
              "SELECT name FROM sqlite_master WHERE type='trigger' AND name='backend_exercise_personal_hints_insert'"
          )
          .use { assertTrue(it.moveToFirst()) }
    }
  }
}
