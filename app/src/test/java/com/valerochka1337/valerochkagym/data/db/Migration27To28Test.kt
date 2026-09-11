package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.Room
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
class Migration27To28Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val name = "coach-preparation-27-28.db"

  @After
  fun cleanup() {
    context.deleteDatabase(name)
  }

  @Test
  fun `released preparation v27 retains request bytes and workout data when coach is added`() {
    helper.createDatabase(name, 27).use { db ->
      db.execSQL(
          "INSERT INTO workouts(id,name,startedAt,note,uploadStatus) VALUES('w','Тренировка',1,'заметка','PENDING')"
      )
      db.execSQL(
          "INSERT INTO workout_preparations(owner,requestId,intentJson,replacesJson,requestJson,revision,catalogRevision,generation,state) VALUES('owner','request','{}','[]','  { }  ',3,4,5,'PENDING')"
      )
    }
    val room = openCurrent()
    try {
      val db = room.openHelper.writableDatabase
      db.query(
              "SELECT requestId,requestJson,revision,catalogRevision,generation FROM workout_preparations"
          )
          .use {
            assertTrue(it.moveToFirst())
            assertEquals("request", it.getString(0))
            assertEquals("  { }  ", it.getString(1))
            assertEquals(3, it.getInt(2))
            assertEquals(4, it.getInt(3))
            assertEquals(5, it.getInt(4))
          }
      db.query("SELECT note,coachRevision FROM workouts WHERE id='w'").use {
        assertTrue(it.moveToFirst())
        assertEquals("заметка", it.getString(0))
        assertEquals(0, it.getInt(1))
      }
      db.query("SELECT COUNT(*) FROM coach_messages").use {
        assertTrue(it.moveToFirst())
        assertEquals(0, it.getInt(0))
      }
    } finally {
      room.close()
    }
  }

  private fun openCurrent(): GymDatabase =
      Room.databaseBuilder(context, GymDatabase::class.java, name)
          .addMigrations(*GymDatabase.ALL_MIGRATIONS)
          .allowMainThreadQueries()
          .build()
}
