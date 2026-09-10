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
class Migration16To17Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "calendar-owner-16-17.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `migration quarantines every legacy one off event without inferring an owner`() {
    helper.createDatabase(name, 16).use { db ->
      db.execSQL(
          "INSERT INTO routines(id,name,note,syncId,updatedAt,origin,archived) VALUES(1,'Ноги','','r',0,'PERSONAL',0)"
      )
      db.execSQL(
          "INSERT INTO scheduled_workouts(id,routineId,dateTimeMillis,calendarEventId) VALUES(7,1,100,'event')"
      )
    }
    helper.runMigrationsAndValidate(name, 17, true, GymDatabase.MIGRATION_16_17).use { db ->
      db.query("SELECT scheduledWorkoutId,ownerEmail,state FROM calendar_event_account_links").use {
        assertTrue(it.moveToFirst())
        assertEquals(7, it.getInt(0))
        assertTrue(it.isNull(1))
        assertEquals("LEGACY_OWNER_UNKNOWN", it.getString(2))
      }
      db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }
  }
}
