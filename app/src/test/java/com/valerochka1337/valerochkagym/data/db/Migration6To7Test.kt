package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Проверяет ручную v6 → v7 миграцию stable UUID и версии синхронизации программ. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration6To7Test {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val dbName = "migration-6-7.db"

  @After
  fun deleteDatabase() {
    context.deleteDatabase(dbName)
  }

  @Test
  fun `migration gives every existing routine a unique sync id and a version`() {
    createV6Database().use { database ->
      database.execSQL("INSERT INTO routines (id, name, note) VALUES (1, 'Ноги', '')")
      database.execSQL("INSERT INTO routines (id, name, note) VALUES (2, 'Верх', 'Жим')")

      GymDatabase.MIGRATION_6_7.migrate(database)

      val rows = mutableListOf<Pair<String, Long>>()
      database.query("SELECT syncId, updatedAt FROM routines ORDER BY id").use { cursor ->
        while (cursor.moveToNext()) rows += cursor.getString(0) to cursor.getLong(1)
      }
      assertEquals(2, rows.size)
      assertEquals(2, rows.map { it.first }.toSet().size)
      assertTrue(rows.all { it.first.isNotBlank() && it.second > 0 })
      database.query("PRAGMA index_list('routines')").use { cursor ->
        val names = buildSet {
          while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
        assertTrue("index_routines_syncId" in names)
      }
    }
  }

  private fun createV6Database(): SupportSQLiteDatabase {
    val callback =
        object : SupportSQLiteOpenHelper.Callback(6) {
          override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE routines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "name TEXT NOT NULL, note TEXT NOT NULL)",
            )
          }

          override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
    return FrameworkSQLiteOpenHelperFactory()
        .create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        .writableDatabase
  }
}
