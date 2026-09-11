package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporterImpl
import com.valerochka1337.valerochkagym.data.backup.ExportResult
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opens the schema-only device probe through the same Room builder used in production. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MigrationDeviceLegacyCoach17To27Test {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val name = DatabaseExporter.DATABASE_NAME

  @After
  fun cleanup() {
    context.deleteDatabase(name)
  }

  @Test
  fun `confirmed device v17 archives raw coach authority and opens current coach tables`() {
    createDeviceV17()
    val database = openCurrent()
    try {
      val raw = database.openHelper.writableDatabase
      LegacyCoachArchiveRegistry.archiveTableNames().forEach { table ->
        assertEquals(1, count(raw, table))
      }
      assertEquals(
          "{\"decision\":\"legacy\"}",
          value(raw, "SELECT decisionJson FROM legacy_live_coach_v1_proposals"),
      )
      assertEquals(
          "{\"raw\":true}",
          value(raw, "SELECT payloadJson FROM legacy_live_coach_v1_journal"),
      )
      assertEquals("cursor", value(raw, "SELECT cursor FROM legacy_live_coach_v1_sync_state"))
      assertEquals(
          "{\"session\":true}",
          value(raw, "SELECT sessionJson FROM legacy_live_coach_v1_sessions"),
      )
      raw.query("SELECT accountId FROM legacy_live_coach_v1_sessions").use { rows ->
        assertTrue(rows.moveToFirst())
        assertTrue(rows.isNull(0))
      }
      assertEquals("42", value(raw, "SELECT weightKg FROM legacy_live_coach_v1_weight_exclusions"))
      assertEquals("stable-set", value(raw, "SELECT syncId FROM workout_sets WHERE id=1"))
      assertEquals("Жим", value(raw, "SELECT name FROM exercises WHERE id=1"))
      assertEquals(0, count(raw, "coach_proposals"))
      assertEquals(0, count(raw, "coach_journal"))
      assertEquals(0, count(raw, "coach_sync_state"))
      assertFalse(indexExists(raw, "index_coach_journal_synced"))
      assertTrue(indexExists(raw, "index_coach_journal_accountId"))
      raw.execSQL(
          "INSERT INTO coach_journal(id,accountId,workoutId,createdAt,payload,uploaded,deviceId) VALUES('current','new-owner','w',4,'{}',0,'device')"
      )
      assertEquals(1, count(raw, "coach_journal"))
      raw.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }

      val backup = File(context.cacheDir, "device-legacy-coach-backup.db")
      try {
        assertEquals(
            ExportResult.Success,
            runBlocking { DatabaseExporterImpl(context, database).export(Uri.fromFile(backup)) },
        )
        SQLiteDatabase.openDatabase(backup.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
          copy.rawQuery("SELECT payloadJson FROM legacy_live_coach_v1_journal", null).use {
            assertTrue(it.moveToFirst())
            assertEquals("{\"raw\":true}", it.getString(0))
          }
        }
      } finally {
        backup.delete()
      }

      raw.execSQL("DELETE FROM workouts WHERE id='w'")
      assertEquals(0, count(raw, "legacy_live_coach_v1_sessions"))
      assertEquals(0, count(raw, "legacy_live_coach_v1_proposals"))
      assertEquals(0, count(raw, "legacy_live_coach_v1_journal"))
      assertEquals(1, count(raw, "legacy_live_coach_v1_sync_state"))
      assertEquals(1, count(raw, "legacy_live_coach_v1_weight_exclusions"))
      raw.execSQL("DELETE FROM gyms WHERE id=1")
      assertEquals(0, count(raw, "legacy_live_coach_v1_weight_exclusions"))
    } finally {
      database.close()
    }
  }

  @Test
  fun `archive registry purges every retained device table`() {
    createDeviceV17()
    val database = openCurrent()
    try {
      val raw = database.openHelper.writableDatabase
      LegacyCoachArchiveRegistry.purge(raw)
      LegacyCoachArchiveRegistry.archiveTableNames().forEach { table ->
        assertFalse(tableExists(raw, table))
      }
      assertTrue(tableExists(raw, "coach_journal"))
      assertTrue(tableExists(raw, "coach_proposals"))
      assertTrue(tableExists(raw, "coach_sync_state"))
    } finally {
      database.close()
    }
  }

  @Test
  fun `archived device baseline reopens without restoring legacy authority`() {
    createDeviceV17()
    val first = openCurrent()
    try {
      first.openHelper.writableDatabase
    } finally {
      first.close()
    }
    val reopened = openCurrent()
    try {
      val raw = reopened.openHelper.writableDatabase
      assertEquals(
          "{\"raw\":true}",
          value(raw, "SELECT payloadJson FROM legacy_live_coach_v1_journal"),
      )
      assertEquals(0, count(raw, "coach_journal"))
      assertEquals("stable-set", value(raw, "SELECT syncId FROM workout_sets WHERE id=1"))
    } finally {
      reopened.close()
    }
  }

  @Test
  fun `near miss device schema with a missing coach FK is not treated as archived baseline`() {
    createDeviceV17(omitJournalForeignKey = true)
    val database = openCurrent()
    var rejected = false
    try {
      try {
        database.openHelper.writableDatabase
        fail("An unknown same-column coach table must not be converted into current authority")
      } catch (_: android.database.sqlite.SQLiteException) {
        // The exact-signature guard leaves the table untouched and Room rejects the mismatch.
        rejected = true
      }
    } finally {
      database.close()
    }
    assertTrue(rejected)
    SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        .use { raw ->
          raw.rawQuery(
                  "SELECT name FROM sqlite_master WHERE type='table' AND name='coach_journal'",
                  null,
              )
              .use { assertTrue(it.moveToFirst()) }
          raw.rawQuery(
                  "SELECT name FROM sqlite_master WHERE type='table' AND name='legacy_live_coach_v1_journal'",
                  null,
              )
              .use { assertFalse(it.moveToFirst()) }
        }
  }

  private fun openCurrent(): GymDatabase =
      Room.databaseBuilder(context, GymDatabase::class.java, name)
          .addMigrations(*GymDatabase.ALL_MIGRATIONS)
          .allowMainThreadQueries()
          .build()

  private fun createDeviceV17(omitJournalForeignKey: Boolean = false) {
    context.deleteDatabase(name)
    val objects =
        Json.parseToJsonElement(
                requireNotNull(
                        javaClass.classLoader?.getResourceAsStream(
                            "legacy-coach-device-v17-schema.json"
                        )
                    )
                    .bufferedReader()
                    .use { it.readText() },
            )
            .jsonObject["objects"]!!
            .jsonArray
            .map { it.jsonObject }
    val helper =
        FrameworkSQLiteOpenHelperFactory()
            .create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(name)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(17) {
                          override fun onCreate(db: SupportSQLiteDatabase) {
                            listOf("table", "index", "trigger").forEach { kind ->
                              objects
                                  .filter {
                                    it["kind"]!!.jsonPrimitive.content == kind &&
                                        objectSqlName(it) != "android_metadata"
                                  }
                                  .forEach { objectSql ->
                                    db.execSQL(objectSql["sql"]!!.jsonPrimitive.content)
                                  }
                            }
                          }

                          override fun onUpgrade(
                              db: SupportSQLiteDatabase,
                              oldVersion: Int,
                              newVersion: Int,
                          ) = Unit
                        },
                    )
                    .build(),
            )
    helper.writableDatabase.use { db ->
      seed(db)
      if (omitJournalForeignKey) {
        db.execSQL("DROP INDEX IF EXISTS index_coach_journal_workoutId")
        db.execSQL("DROP INDEX IF EXISTS index_coach_journal_synced")
        db.execSQL("DROP INDEX IF EXISTS index_coach_journal_synced_createdAt")
        db.execSQL("DROP TABLE coach_journal")
        db.execSQL(
            "CREATE TABLE coach_journal (id TEXT NOT NULL,workoutId TEXT NOT NULL,deviceId TEXT NOT NULL,createdAt INTEGER NOT NULL,payloadJson TEXT NOT NULL,synced INTEGER NOT NULL,PRIMARY KEY(id))"
        )
        db.execSQL("INSERT INTO coach_journal VALUES('journal','w','device',3,'{\"raw\":true}',0)")
      }
    }
    helper.close()
  }

  private fun objectSqlName(value: kotlinx.serialization.json.JsonObject): String =
      value["name"]!!.jsonPrimitive.content

  private fun seed(db: SupportSQLiteDatabase) {
    db.execSQL("INSERT INTO backend_state(id,owner,generation) VALUES(1,NULL,0)")
    db.execSQL(
        "INSERT INTO exercises(id,name,muscleGroup,type,isCustom,syncId,updatedAt,needsMuscleMapReview,equipmentRequirementState,origin,archived) VALUES(1,'Жим','CHEST','STRENGTH',1,'exercise',0,0,'KNOWN','PERSONAL',0)"
    )
    db.execSQL(
        "INSERT INTO gyms(id,syncId,updatedAt,name,inventoryConfigured,origin,archived) VALUES(1,'gym',0,'Зал',1,'PERSONAL',0)"
    )
    db.execSQL(
        "INSERT INTO workouts(id,routineId,name,startedAt,finishedAt,note,uploadStatus,uploadError,coachRevision) VALUES('w',NULL,'Тренировка',1,NULL,'','PENDING',NULL,3)"
    )
    db.execSQL(
        "INSERT INTO workout_exercises(id,workoutId,exerciseId,sectionId,position) VALUES(1,'w',1,'section',0)"
    )
    db.execSQL(
        "INSERT INTO workout_sets(id,workoutExerciseId,setIndex,isCompleted,syncId) VALUES(1,1,0,0,'stable-set')"
    )
    db.execSQL(
        "INSERT INTO coach_sessions VALUES('w',NULL,'device',3,'v1','ACTIVE','{\"session\":true}',1)"
    )
    db.execSQL(
        "INSERT INTO coach_proposals VALUES('proposal','w','snapshot','{\"decision\":\"legacy\"}','{}','[]','PENDING',2,NULL)"
    )
    db.execSQL("INSERT INTO coach_journal VALUES('journal','w','device',3,'{\"raw\":true}',0)")
    db.execSQL("INSERT INTO coach_sync_state VALUES('legacy-owner',4,'cursor')")
    db.execSQL("INSERT INTO coach_weight_exclusions VALUES(1,'exercise',42.0,5)")
  }

  private fun count(db: SupportSQLiteDatabase, table: String): Int =
      db.query("SELECT COUNT(*) FROM `$table`").use { rows ->
        rows.moveToFirst()
        rows.getInt(0)
      }

  private fun value(db: SupportSQLiteDatabase, query: String): String =
      db.query(query).use { rows ->
        rows.moveToFirst()
        rows.getString(0)
      }

  private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
      db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
        it.moveToFirst()
      }

  private fun indexExists(db: SupportSQLiteDatabase, index: String): Boolean =
      db.query("SELECT 1 FROM sqlite_master WHERE type='index' AND name=?", arrayOf(index)).use {
        it.moveToFirst()
      }
}
