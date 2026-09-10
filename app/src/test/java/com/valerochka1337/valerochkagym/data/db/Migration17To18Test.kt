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
class Migration17To18Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase("guest-sync-17-18.db")
  }

  @Test
  fun `migration preserves legacy owner baseline and exact outbox while making it owned`() {
    helper.createDatabase("guest-sync-17-18.db", 17).use { db ->
      db.execSQL("INSERT INTO backend_state(id,owner,generation) VALUES(1,'user-a',9)")
      db.execSQL(
          "INSERT INTO backend_baseline(`key`,recordJson) VALUES('routine:r','{\"kind\":\"routine\"}')"
      )
      db.execSQL(
          "INSERT INTO backend_outbox(id,owner,requestJson) VALUES(1,'user-a','{\"operationId\":\"exact\"}')"
      )
    }
    helper
        .runMigrationsAndValidate(
            "guest-sync-17-18.db",
            18,
            true,
            GymDatabase.MIGRATION_17_18,
        )
        .use { db ->
          db.query(
                  "SELECT owner,phase,mergeId,initialMergeAcknowledged,generation FROM backend_state"
              )
              .use {
                assertTrue(it.moveToFirst())
                assertEquals("user-a", it.getString(0))
                assertEquals("OWNED", it.getString(1))
                assertTrue(it.isNull(2))
                assertEquals(1, it.getInt(3))
                assertEquals(9L, it.getLong(4))
              }
          db.query("SELECT recordJson FROM backend_baseline").use {
            assertTrue(it.moveToFirst())
            assertEquals("{\"kind\":\"routine\"}", it.getString(0))
          }
          db.query("SELECT requestJson FROM backend_outbox").use {
            assertTrue(it.moveToFirst())
            assertEquals("{\"operationId\":\"exact\"}", it.getString(0))
          }
        }
  }

  @Test
  fun `migration maps a null legacy owner to guest without touching journals`() {
    helper.createDatabase("guest-sync-null-17-18.db", 17).use { db ->
      db.execSQL("INSERT INTO backend_state(id,owner,generation) VALUES(1,NULL,4)")
      db.execSQL("INSERT INTO backend_outbox(id,owner,requestJson) VALUES(1,'legacy','exact')")
    }
    helper
        .runMigrationsAndValidate(
            "guest-sync-null-17-18.db",
            18,
            true,
            GymDatabase.MIGRATION_17_18,
        )
        .use { db ->
          db.query("SELECT owner,phase,generation FROM backend_state WHERE id=1").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(0))
            assertEquals("GUEST", it.getString(1))
            assertEquals(4L, it.getLong(2))
          }
          db.query("SELECT requestJson FROM backend_outbox WHERE id=1").use {
            assertTrue(it.moveToFirst())
            assertEquals("exact", it.getString(0))
          }
        }
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase("guest-sync-null-17-18.db")
  }
}
