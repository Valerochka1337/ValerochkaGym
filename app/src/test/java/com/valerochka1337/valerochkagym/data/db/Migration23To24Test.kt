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
class Migration23To24Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "manual-health-23-24.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `migration creates isolated health ledger without recreating AI consent tables`() {
    helper.createDatabase(name, 23).use { db ->
      db.execSQL(
          "INSERT INTO health_ai_consent_state(owner,revision,noticeVersion,enabled,recordedAtEpochMs,receiptBytes) VALUES('owner',1,1,1,1,X'01')"
      )
      db.execSQL(
          "INSERT INTO health_ai_consent_outbox(owner,operationId,requestBytes,requestSha256,dispatched) VALUES('owner','op',X'20','hash',0)"
      )
    }
    helper.runMigrationsAndValidate(name, 24, true, GymDatabase.MIGRATION_23_24).use { db ->
      db.execSQL(
          "INSERT INTO health_logical_records(logicalId,scope,kind,createdAtEpochMs,currentVersionId,headRevision,deleted,healthRevision) VALUES('l','GUEST','RESTRICTION',1,'v',1,0,NULL)"
      )
      db.execSQL(
          "INSERT INTO health_record_versions(versionId,logicalId,parentVersionId,kind,state,enteredAtEpochMs,payloadJson,serverSequence,healthRevision) VALUES('v','l',NULL,'RESTRICTION','CONFIRMED',1,'{}',NULL,NULL)"
      )
      db.execSQL(
          "INSERT INTO health_head_history(logicalId,headRevision,currentVersionId,kind,deleted,healthRevision) VALUES('l',1,'v','RESTRICTION',0,10)"
      )
      db.query("SELECT COUNT(*) FROM health_ai_consent_state WHERE owner='owner'").use {
        assertTrue(it.moveToFirst())
        assertEquals(1, it.getInt(0))
      }
      db.query("SELECT requestBytes FROM health_ai_consent_outbox WHERE owner='owner'").use {
        assertTrue(it.moveToFirst())
        assertEquals(1, it.getBlob(0).size)
      }
    }
  }
}
