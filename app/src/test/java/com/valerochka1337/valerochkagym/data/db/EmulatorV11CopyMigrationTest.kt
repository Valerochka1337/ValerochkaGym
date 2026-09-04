package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opt-in local recovery gate. It only reads VALEROCHKA_GYM_DB_COPY env, then migrates an internal
 * copy; the source fixture (and optional WAL/SHM sidecars) never become a Room open target.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class EmulatorV11CopyMigrationTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val targetName = "emulator-v11-copy-migration.db"

  @After
  fun tearDown() {
    context.deleteDatabase(targetName)
  }

  @Test
  fun `external emulator copy migrates through production registry without changing source`() {
    val source = System.getenv("VALEROCHKA_GYM_DB_COPY")?.takeIf { it.isNotBlank() }?.let(::File)
    assumeTrue(
        "VALEROCHKA_GYM_DB_COPY is required for this local-only gate",
        source?.isFile == true,
    )
    val sourceFile = source ?: return
    val sourceShaBefore = sha256(sourceFile)
    val sourceForeignKeyViolations = readV11Provenance(sourceFile)

    val target = context.getDatabasePath(targetName)
    target.parentFile?.mkdirs()
    try {
      sourceFile.copyTo(target, overwrite = true)
      copySidecarIfPresent(sourceFile, target, "-wal")
      copySidecarIfPresent(sourceFile, target, "-shm")

      val database = MigrationRecoveryFixtures.openThroughProductionList(context, targetName)
      try {
        assertRecoveredSchema(database.openHelper.writableDatabase, sourceForeignKeyViolations)
      } finally {
        database.close()
      }
      assertEquals(
          "migration must not modify the source database",
          sourceShaBefore,
          sha256(sourceFile),
      )
    } finally {
      context.deleteDatabase(targetName)
    }
  }

  private fun readV11Provenance(source: File): Set<String> {
    SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
      database.rawQuery("PRAGMA user_version", null).use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals(11, cursor.getInt(0))
      }
      database
          .rawQuery(
              "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'exercise_variant_muscles'",
              null,
          )
          .use { cursor -> assertTrue(cursor.moveToFirst()) }
      return database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
        buildSet {
          while (cursor.moveToNext()) add(
              "${cursor.getString(0)}|${cursor.getLong(1)}|${cursor.getString(2)}|${cursor.getInt(3)}"
          )
        }
      }
    }
  }

  private fun copySidecarIfPresent(source: File, target: File, suffix: String) {
    val sidecar = File(source.path + suffix)
    if (sidecar.isFile) sidecar.copyTo(File(target.path + suffix), overwrite = true)
  }

  private fun sha256(file: File): String =
      MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
        "%02x".format(it)
      }

  private fun assertRecoveredSchema(
      database: androidx.sqlite.db.SupportSQLiteDatabase,
      sourceForeignKeyViolations: Set<String>,
  ) {
    database.query("PRAGMA user_version").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertEquals(12, cursor.getInt(0))
    }
    database
        .query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('exercise_variants', 'exercise_variant_muscles')"
        )
        .use { cursor -> assertTrue(!cursor.moveToFirst()) }
    database.query("PRAGMA foreign_key_check").use { cursor ->
      val afterMigration = buildSet {
        while (cursor.moveToNext()) add(
            "${cursor.getString(0)}|${cursor.getLong(1)}|${cursor.getString(2)}|${cursor.getInt(3)}"
        )
      }
      assertEquals(
          "migration must not add foreign-key violations",
          sourceForeignKeyViolations,
          afterMigration,
      )
    }
  }
}
