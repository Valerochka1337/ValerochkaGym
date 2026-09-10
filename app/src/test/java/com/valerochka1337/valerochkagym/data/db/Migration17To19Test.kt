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
class Migration17To19Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "calendar-17-19.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `migration keeps known and unknown event ownership evidence beside calendar sources`() {
    helper.createDatabase(name, 17).use { db ->
      db.execSQL(
          "INSERT INTO routines(id,syncId,updatedAt,name,note,origin,archived) VALUES(1,'r',0,'Ноги','','PERSONAL',0)"
      )
      db.execSQL(
          "INSERT INTO scheduled_workouts(id,routineId,dateTimeMillis,calendarEventId) VALUES(7,1,100,'known')"
      )
      db.execSQL(
          "INSERT INTO scheduled_workouts(id,routineId,dateTimeMillis,calendarEventId) VALUES(8,1,200,'unknown')"
      )
      db.execSQL(
          "INSERT INTO calendar_event_account_links(scheduledWorkoutId,ownerEmail,state) VALUES(7,'owner@example.com','OWNED')"
      )
      db.execSQL(
          "INSERT INTO calendar_event_account_links(scheduledWorkoutId,ownerEmail,state) VALUES(8,NULL,'LEGACY_OWNER_UNKNOWN')"
      )
    }

    helper.runMigrationsAndValidate(name, 19, true, *GymDatabase.ALL_MIGRATIONS).use { db ->
      db.query(
              "SELECT ownerEmail,state FROM calendar_event_account_links ORDER BY scheduledWorkoutId"
          )
          .use {
            assertTrue(it.moveToFirst())
            assertEquals("owner@example.com", it.getString(0))
            assertEquals("OWNED", it.getString(1))
            assertTrue(it.moveToNext())
            assertTrue(it.isNull(0))
            assertEquals("LEGACY_OWNER_UNKNOWN", it.getString(1))
          }
      db.query("SELECT phase FROM calendar_migration_state WHERE id=1").use {
        assertTrue(it.moveToFirst())
        assertEquals("PENDING", it.getString(0))
      }
    }
  }
}
