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
class Migration1To20Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "health-ai-disclosure-1-20.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `full migration creates an empty disclosure aggregate`() {
    helper.createDatabase(name, 1).use { db ->
      db.execSQL("INSERT INTO routines(id,name,note) VALUES(1,'Ноги','')")
    }
    helper.runMigrationsAndValidate(name, 20, true, *GymDatabase.ALL_MIGRATIONS).use { db ->
      db.query("SELECT COUNT(*) FROM health_ai_consent_state").use {
        assertTrue(it.moveToFirst())
        assertEquals(0, it.getInt(0))
      }
      db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }
  }
}
