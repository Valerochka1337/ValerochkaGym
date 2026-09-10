package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration24To25Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase("proposal-24-25.db")
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase("proposal-1-25.db")
  }

  @Test
  fun `proposal migration preserves exact health journal and isolates participant drafts`() {
    helper.createDatabase("proposal-24-25.db", 24).use {
      it.execSQL(
          "INSERT INTO health_sync_outbox(operationId,scope,requestBytes,requestSha256,dispatched) VALUES('op','owner',X'200a7b7d','hash',1)"
      )
    }
    helper
        .runMigrationsAndValidate("proposal-24-25.db", 25, true, GymDatabase.MIGRATION_24_25)
        .use {
          it.query("SELECT hex(requestBytes),dispatched FROM health_sync_outbox").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("200A7B7D", c.getString(0))
            assertEquals(1, c.getInt(1))
          }
          it.execSQL(
              "INSERT INTO training_proposal_drafts(owner,proposalId,version,proposalJson,draftJson) VALUES('a','p',1,'{}','a')"
          )
          it.execSQL(
              "INSERT INTO training_proposal_drafts(owner,proposalId,version,proposalJson,draftJson) VALUES('b','p',1,'{}','b')"
          )
          it.query("SELECT draftJson FROM training_proposal_drafts WHERE owner='a'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("a", c.getString(0))
            assertFalse(c.moveToNext())
          }
        }
  }

  @Test
  fun `full historical migration retains routine and creates empty proposal journals`() {
    helper.createDatabase("proposal-1-25.db", 1).use {
      it.execSQL("INSERT INTO routines(id,name,note) VALUES(1,'Ноги','Примечание')")
    }
    helper.runMigrationsAndValidate("proposal-1-25.db", 25, true, *GymDatabase.ALL_MIGRATIONS).use {
      it.query("SELECT note FROM routines WHERE id=1").use { c ->
        assertTrue(c.moveToFirst())
        assertEquals("Примечание", c.getString(0))
      }
      it.query("SELECT COUNT(*) FROM training_proposal_operations").use { c ->
        c.moveToFirst()
        assertEquals(0, c.getInt(0))
      }
    }
  }
}
