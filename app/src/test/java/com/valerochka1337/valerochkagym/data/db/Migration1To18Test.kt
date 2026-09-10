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
class Migration1To18Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "guest-sync-1-18.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `full migration gives a legacy unowned database the guest transfer state`() {
    helper.createDatabase(name, 1).use { db ->
      db.execSQL("INSERT INTO exercises VALUES(1,'Жим','CHEST','STRENGTH',1)")
    }
    helper.runMigrationsAndValidate(name, 18, true, *GymDatabase.ALL_MIGRATIONS).use { db ->
      db.query("SELECT owner,phase,mergeId,initialMergeAcknowledged FROM backend_state WHERE id=1")
          .use {
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(0))
            assertEquals("GUEST", it.getString(1))
            assertTrue(it.isNull(2))
            assertEquals(0, it.getInt(3))
          }
      db.query("SELECT COUNT(*) FROM backend_rejected_operations").use {
        assertTrue(it.moveToFirst())
        assertEquals(0, it.getInt(0))
      }
    }
  }
}
