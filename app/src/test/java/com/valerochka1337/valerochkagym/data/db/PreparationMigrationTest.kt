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
class PreparationMigrationTest {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase("preparation-26-27.db")
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase("preparation-1-27.db")
  }

  @Test
  fun `preparation migration retains exact journals and existing participant operations`() {
    helper.createDatabase("preparation-26-27.db", 26).use {
      it.execSQL(
          "INSERT INTO health_sync_outbox(operationId,scope,requestBytes,requestSha256,dispatched) VALUES('op','owner',X'200a7b7d','hash',1)"
      )
    }
    helper
        .runMigrationsAndValidate("preparation-26-27.db", 32, true, *GymDatabase.ALL_MIGRATIONS)
        .use {
          it.query("SELECT hex(requestBytes),dispatched FROM health_sync_outbox").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("200A7B7D", c.getString(0))
            assertEquals(1, c.getInt(1))
          }
          it.execSQL(
              "INSERT INTO coach_relation_operations(owner,operationId,action,route,resource,rawSha256,firstSendBytes,state,resultJson) VALUES('a','op','CREATE_INVITE','POST /v1/coach-relations/invitations','a','hash',NULL,'PENDING',NULL)"
          )
          it.execSQL(
              "INSERT INTO coach_relation_operations(owner,operationId,action,route,resource,rawSha256,firstSendBytes,state,resultJson) VALUES('b','op','REVOKE_RELATION','POST /v1/coach-relations/r/revoke','r','hash',X'200a7b7d','PENDING',NULL)"
          )
          it.query(
                  "SELECT firstSendBytes,resultJson FROM coach_relation_operations WHERE owner='a'"
              )
              .use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
                assertTrue(c.isNull(1))
                assertFalse(c.moveToNext())
              }
          it.query("SELECT hex(firstSendBytes) FROM coach_relation_operations WHERE owner='b'")
              .use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("200A7B7D", c.getString(0))
              }
        }
  }

  @Test
  fun `full historical migration retains routine and creates empty preparation journal`() {
    helper.createDatabase("preparation-1-27.db", 1).use {
      it.execSQL("INSERT INTO routines(id,name,note) VALUES(1,'Ноги','Примечание')")
    }
    helper
        .runMigrationsAndValidate("preparation-1-27.db", 32, true, *GymDatabase.ALL_MIGRATIONS)
        .use {
          it.query("SELECT note FROM routines WHERE id=1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Примечание", c.getString(0))
          }
          it.query("SELECT COUNT(*) FROM workout_preparations").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
          }
        }
  }
}
