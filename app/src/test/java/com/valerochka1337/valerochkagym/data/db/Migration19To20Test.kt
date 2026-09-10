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
class Migration19To20Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "health-ai-disclosure-19-20.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `migration creates owner scoped receipt and exact byte outbox`() {
    helper.createDatabase(name, 19).use { db ->
      db.execSQL(
          "INSERT INTO backend_state(id,owner,generation,phase,initialMergeAcknowledged,acceptedCapabilities) VALUES(1,'owner',0,'OWNED',1,'calendar-plans')"
      )
    }
    helper.runMigrationsAndValidate(name, 20, true, GymDatabase.MIGRATION_19_20).use { db ->
      db.execSQL(
          "INSERT INTO health_ai_consent_state(owner,revision,noticeVersion,enabled,recordedAtEpochMs,receiptBytes) VALUES('owner',2,1,1,3,X'7B7D')"
      )
      db.execSQL(
          "INSERT INTO health_ai_consent_outbox(owner,operationId,requestBytes,requestSha256,dispatched) VALUES('owner','operation',X'7B7D','hash',0)"
      )
      db.query("SELECT revision,enabled FROM health_ai_consent_state WHERE owner='owner'").use {
        assertTrue(it.moveToFirst())
        assertEquals(2, it.getLong(0))
        assertEquals(1, it.getInt(1))
      }
    }
  }
}
