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
class Migration28To29Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "coach-preview-28-29.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `migration preserves pending proposal bytes and adds optional preview`() {
    helper.createDatabase(name, 28).use { db ->
      db.execSQL(
          "INSERT INTO workouts(id,name,startedAt,note,uploadStatus,coachRevision) VALUES('w','Тренировка',1,'','PENDING',0)"
      )
      db.execSQL(
          "INSERT INTO coach_proposals(id,accountId,workoutId,baseRevision,beforeSummary,afterSummary,packetJson,expiresAt,state) VALUES('p','owner','w',0,'before','after','packet',123,'PENDING')"
      )
    }
    helper.runMigrationsAndValidate(name, 29, true, GymDatabase.MIGRATION_28_29).use { db ->
      db.query(
              "SELECT beforeSummary,afterSummary,packetJson,state,previewJson FROM coach_proposals WHERE id='p'"
          )
          .use {
            assertTrue(it.moveToFirst())
            assertEquals("before", it.getString(0))
            assertEquals("after", it.getString(1))
            assertEquals("packet", it.getString(2))
            assertEquals("PENDING", it.getString(3))
            assertTrue(it.isNull(4))
          }
    }
  }
}
