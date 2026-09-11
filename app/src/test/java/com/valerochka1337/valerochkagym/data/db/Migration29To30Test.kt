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
class Migration29To30Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "coach-replies-29-30.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `migration preserves existing messages and adds nullable contextual replies`() {
    helper.createDatabase(name, 29).use { db ->
      db.execSQL(
          "INSERT INTO workouts(id,name,startedAt,note,uploadStatus,coachRevision) VALUES('w','Тренировка',1,'','PENDING',0)"
      )
      db.execSQL(
          "INSERT INTO coach_messages(id,accountId,workoutId,role,text,createdAt,status) VALUES('m','owner','w','assistant','Сохранённый ответ',2,'DELIVERED')"
      )
    }
    helper.runMigrationsAndValidate(name, 30, true, GymDatabase.MIGRATION_29_30).use { db ->
      db.query("SELECT text,status,quickRepliesJson FROM coach_messages WHERE id='m'").use {
        assertTrue(it.moveToFirst())
        assertEquals("Сохранённый ответ", it.getString(0))
        assertEquals("DELIVERED", it.getString(1))
        assertTrue(it.isNull(2))
      }
    }
  }
}
