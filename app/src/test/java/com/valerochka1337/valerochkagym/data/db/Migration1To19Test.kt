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
class Migration1To19Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "calendar-1-19.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `full supported migration retains unknown account evidence beside new calendar tables`() {
    helper.createDatabase(name, 1).use { db ->
      db.execSQL("INSERT INTO routines(id,name,note) VALUES(1,'Ноги','')")
      db.execSQL(
          "INSERT INTO scheduled_workouts(id,routineId,dateTimeMillis,calendarEventId) VALUES(9,1,100,'event')"
      )
    }
    helper.runMigrationsAndValidate(name, 19, true, *GymDatabase.ALL_MIGRATIONS).use { db ->
      db.query(
              "SELECT ownerEmail,state FROM calendar_event_account_links WHERE scheduledWorkoutId=9"
          )
          .use {
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(0))
            assertEquals("LEGACY_OWNER_UNKNOWN", it.getString(1))
          }
      db.query("SELECT phase FROM calendar_migration_state WHERE id=1").use {
        assertTrue(it.moveToFirst())
        assertEquals("PENDING", it.getString(0))
      }
      db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }
  }
}
