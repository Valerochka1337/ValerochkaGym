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
class Migration20To21Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "health-ai-consent-intent-20-21.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `migration preserves an exact outbox and creates a latest explicit intent`() {
    helper.createDatabase(name, 20).use { db ->
      db.execSQL(
          "INSERT INTO health_ai_consent_outbox(owner,operationId,requestBytes,requestSha256,dispatched) VALUES('owner','operation',CAST('{\"enabled\":true}' AS BLOB),'hash',1)",
      )
    }
    helper.runMigrationsAndValidate(name, 21, true, GymDatabase.MIGRATION_20_21).use { db ->
      db.query("SELECT enabled FROM health_ai_consent_intent WHERE owner='owner'").use {
        assertTrue(it.moveToFirst())
        assertEquals(1, it.getInt(0))
      }
      db.query("SELECT requestSha256 FROM health_ai_consent_outbox WHERE owner='owner'").use {
        assertTrue(it.moveToFirst())
        assertEquals("hash", it.getString(0))
      }
    }
  }
}
