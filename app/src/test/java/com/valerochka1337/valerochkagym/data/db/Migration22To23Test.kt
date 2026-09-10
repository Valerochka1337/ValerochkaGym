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
class Migration22To23Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "basic-profile-22-23.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `migration creates scoped profile children and sync triggers`() {
    helper.createDatabase(name, 22).use {}

    helper.runMigrationsAndValidate(name, 23, true, GymDatabase.MIGRATION_22_23).use { db ->
      db.execSQL(
          "INSERT INTO profiles(scope,syncId,schemaVersion,updatedAt) VALUES('GUEST','profile',1,0)"
      )
      db.execSQL("INSERT INTO profile_equipment(scope,equipmentId) VALUES('GUEST','barbell')")
      db.query(
              "SELECT name FROM sqlite_master WHERE type='trigger' AND name='backend_profiles_insert'"
          )
          .use { assertTrue(it.moveToFirst()) }
      // MigrationTestHelper exposes a raw SQLite connection; unlike Room's production helper it
      // does not enable foreign-key enforcement for this post-migration fixture automatically.
      db.execSQL("PRAGMA foreign_keys=ON")
      db.query("PRAGMA foreign_keys").use {
        assertTrue(it.moveToFirst())
        assertEquals(1, it.getInt(0))
      }
      db.execSQL("DELETE FROM profiles WHERE scope='GUEST'")
      db.query("SELECT COUNT(*) FROM profile_equipment").use {
        assertTrue(it.moveToFirst())
        assertEquals(0, it.getInt(0))
      }
    }
  }
}
