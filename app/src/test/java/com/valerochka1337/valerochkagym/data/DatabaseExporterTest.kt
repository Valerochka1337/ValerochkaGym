package com.valerochka1337.valerochkagym.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporterImpl
import com.valerochka1337.valerochkagym.data.backup.ExportResult
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [DatabaseExporterImpl] поверх настоящей файловой Room-базы: копия должна
 * открываться как SQLite и содержать данные, записанные до экспорта (WAL сброшен чекпоинтом).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DatabaseExporterTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private lateinit var db: GymDatabase
  private lateinit var exporter: DatabaseExporterImpl

  @Before
  fun setUp() {
    // Файловая (не in-memory) база с настоящим именем: экспорт копирует файл gym.db.
    db =
        Room.databaseBuilder(context, GymDatabase::class.java, DatabaseExporter.DATABASE_NAME)
            .allowMainThreadQueries()
            .build()
    exporter = DatabaseExporterImpl(context, db)
  }

  @After
  fun tearDown() {
    db.close()
    context.deleteDatabase(DatabaseExporter.DATABASE_NAME)
  }

  @Test
  fun `export writes an openable sqlite copy with the data written before it`() = runTest {
    db.workoutDao()
        .insertWorkout(
            WorkoutEntity(id = "w1", name = "Грудь", startedAt = 1_000, finishedAt = 2_000),
        )
    val target = File(context.cacheDir, "backup.db")

    val result = exporter.export(Uri.fromFile(target))

    assertEquals(ExportResult.Success, result)
    SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
      copy.rawQuery("SELECT COUNT(*) FROM workouts", null).use { cursor ->
        cursor.moveToFirst()
        assertEquals(1, cursor.getInt(0))
      }
    }
  }

  @Test
  fun `an unwritable target reports a failure instead of throwing`() = runTest {
    val target = Uri.fromFile(File("/nonexistent-dir/backup.db"))

    val result = exporter.export(target)

    assertTrue(result is ExportResult.Failure)
  }

  @Test
  fun `suggested file name carries the date`() {
    assertEquals(
        "valerochka-gym-backup-20260802.db",
        DatabaseExporter.suggestedFileName("20260802"),
    )
  }
}
