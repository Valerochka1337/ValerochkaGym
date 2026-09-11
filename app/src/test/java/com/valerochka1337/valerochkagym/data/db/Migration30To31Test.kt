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
class Migration30To31Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val name = "coach-unread-30-31.db"

  @After
  fun cleanup() {
    ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
  }

  @Test
  fun `migration retains history and treats existing assistant replies as read`() {
    helper.createDatabase(name, 30).use { db ->
      db.execSQL(
          "INSERT INTO workouts(id,name,startedAt,note,uploadStatus,coachRevision) VALUES('w','Тренировка',1,'','PENDING',0)"
      )
      db.execSQL(
          "INSERT INTO coach_messages(id,accountId,workoutId,role,text,createdAt,status) VALUES('assistant','owner','w','assistant','Ответ',7,'DELIVERED')"
      )
      db.execSQL(
          "INSERT INTO coach_messages(id,accountId,workoutId,role,text,createdAt,status) VALUES('user','owner','w','user','Вопрос',8,'DELIVERED')"
      )
    }
    helper.runMigrationsAndValidate(name, 31, true, GymDatabase.MIGRATION_30_31).use { db ->
      db.query("SELECT readAt FROM coach_messages WHERE id='assistant'").use {
        assertTrue(it.moveToFirst())
        assertEquals(7L, it.getLong(0))
      }
      db.query("SELECT readAt FROM coach_messages WHERE id='user'").use {
        assertTrue(it.moveToFirst())
        assertTrue(it.isNull(0))
      }
    }
  }
}
