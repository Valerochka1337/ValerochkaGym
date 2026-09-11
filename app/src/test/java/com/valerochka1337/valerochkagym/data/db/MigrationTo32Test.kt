package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MigrationTo32Test {
  @get:Rule
  val helper =
      MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val name = "coach-preparation-32.db"

  @After
  fun cleanup() {
    context.deleteDatabase(name)
  }

  @Test
  fun `released preparation v27 retains request bytes and workout data when coach is added`() {
    val schema =
        Json.parseToJsonElement(
                requireNotNull(
                        javaClass.classLoader?.getResourceAsStream("preparation-v27-schema.json")
                    )
                    .bufferedReader()
                    .use { it.readText() }
            )
            .jsonObject["database"]!!
            .jsonObject
    val path = context.getDatabasePath(name)
    path.parentFile!!.mkdirs()
    SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
      schema["entities"]!!.jsonArray.forEach { element ->
        val entity = element.jsonObject
        val table = entity["tableName"]!!.jsonPrimitive.content
        db.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
        entity["indices"]?.jsonArray?.forEach { index ->
          db.execSQL(
              index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
          )
        }
      }
      schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
      db.execSQL(
          "INSERT INTO workouts(id,name,startedAt,note,uploadStatus) VALUES('w','Тренировка',1,'заметка','PENDING')"
      )
      db.execSQL(
          "INSERT INTO workout_preparations(owner,requestId,intentJson,replacesJson,requestJson,revision,catalogRevision,generation,state) VALUES('owner','request','{}','[]','  { }  ',3,4,5,'PENDING')"
      )
      db.version = 27
    }
    val room = openCurrent()
    try {
      val db = room.openHelper.writableDatabase
      db.query(
              "SELECT requestId,requestJson,revision,catalogRevision,generation FROM workout_preparations"
          )
          .use {
            assertTrue(it.moveToFirst())
            assertEquals("request", it.getString(0))
            assertEquals("  { }  ", it.getString(1))
            assertEquals(3, it.getInt(2))
            assertEquals(4, it.getInt(3))
            assertEquals(5, it.getInt(4))
          }
      db.query("SELECT note,coachRevision FROM workouts WHERE id='w'").use {
        assertTrue(it.moveToFirst())
        assertEquals("заметка", it.getString(0))
        assertEquals(0, it.getInt(1))
      }
      db.query("SELECT COUNT(*) FROM coach_messages").use {
        assertTrue(it.moveToFirst())
        assertEquals(0, it.getInt(0))
      }
    } finally {
      room.close()
    }
  }

  @Test
  fun `early coach v27 retains transcript when upgrading directly to combined schema`() {
    assertCoachUpgrade(27)
  }

  @Test
  fun `coach v31 retains transcript and unread state when preparation journal is added`() {
    assertCoachUpgrade(31)
  }

  private fun assertCoachUpgrade(version: Int) {
    helper.createDatabase(name, version).use { db ->
      db.execSQL(
          "INSERT INTO workouts(id,name,startedAt,note,uploadStatus,coachRevision) VALUES('w','Тренировка',1,'','PENDING',7)"
      )
      db.execSQL(
          "INSERT INTO coach_messages(id,accountId,workoutId,role,text,createdAt,status) VALUES('m','owner','w','assistant','Ответ',9,'DELIVERED')"
      )
    }
    val room = openCurrent()
    try {
      val db = room.openHelper.writableDatabase
      db.query("SELECT text,readAt FROM coach_messages WHERE id='m'").use {
        assertTrue(it.moveToFirst())
        assertEquals("Ответ", it.getString(0))
        if (version == 27) assertEquals(9L, it.getLong(1)) else assertTrue(it.isNull(1))
      }
      db.query("SELECT coachRevision FROM workouts WHERE id='w'").use {
        assertTrue(it.moveToFirst())
        assertEquals(7, it.getInt(0))
      }
      db.query("SELECT COUNT(*) FROM workout_preparations").use {
        assertTrue(it.moveToFirst())
        assertEquals(0, it.getInt(0))
      }
    } finally {
      room.close()
    }
  }

  private fun openCurrent(): GymDatabase =
      Room.databaseBuilder(context, GymDatabase::class.java, name)
          .addMigrations(*GymDatabase.ALL_MIGRATIONS)
          .allowMainThreadQueries()
          .build()
}
