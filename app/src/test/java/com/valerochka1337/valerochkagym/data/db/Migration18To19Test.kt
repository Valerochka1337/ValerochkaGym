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
class Migration18To19Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "calendar-18-19.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `migration retains legacy sources and creates a pending calendar gate`() {
    helper.createDatabase(name, 18).use { db ->
      db.execSQL(
          "INSERT INTO routines(id,syncId,updatedAt,name,note,origin,archived) VALUES(1,'r',0,'Ноги','','PERSONAL',0)"
      )
      db.execSQL(
          "INSERT INTO scheduled_workouts(id,routineId,dateTimeMillis,calendarEventId) VALUES(7,1,100,'event')"
      )
      db.execSQL("INSERT INTO backend_outbox(id,owner,requestJson) VALUES(1,'owner','exact-bytes')")
    }
    helper.runMigrationsAndValidate(name, 19, true, GymDatabase.MIGRATION_18_19).use { db ->
      db.query("SELECT routineId,calendarEventId FROM scheduled_workouts WHERE id=7").use {
        assertTrue(it.moveToFirst())
        assertEquals(1, it.getInt(0))
        assertEquals("event", it.getString(1))
      }
      db.query("SELECT phase FROM calendar_migration_state WHERE id=1").use {
        assertTrue(it.moveToFirst())
        assertEquals("PENDING", it.getString(0))
      }
      db.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
        assertTrue(it.moveToFirst())
        assertEquals("exact-bytes", it.getString(0))
      }
      db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }
  }
}
