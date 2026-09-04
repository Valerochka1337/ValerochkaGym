package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration1To2Test {

  /** Точный DDL таблицы `workout_sets` версии 1 (см. schemas/1.json), без завёртки TABLE_NAME. */
  private val v1WorkoutSets =
      "CREATE TABLE `workout_sets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
          "`workoutExerciseId` INTEGER NOT NULL, `setIndex` INTEGER NOT NULL, `weightKg` REAL, " +
          "`reps` INTEGER, `durationSec` INTEGER, `speedKmh` REAL, `inclinePct` REAL, " +
          "`isCompleted` INTEGER NOT NULL)"

  @Test
  fun `migration 1 to 2 adds nullable completedAt and keeps existing rows`() {
    val db = openInMemory { it.execSQL(v1WorkoutSets) }
    db.execSQL(
        "INSERT INTO workout_sets (workoutExerciseId, setIndex, isCompleted) VALUES (1, 0, 1)"
    )

    GymDatabase.MIGRATION_1_2.migrate(db)

    db.query("SELECT completedAt FROM workout_sets").use { cursor ->
      assertTrue(cursor.moveToFirst())
      assertTrue(cursor.isNull(0)) // старая строка получает NULL
    }
    db.close()
  }

  /** In-memory SupportSQLiteDatabase; [onCreate] строит стартовую схему v1. */
  private fun openInMemory(onCreate: (SupportSQLiteDatabase) -> Unit): SupportSQLiteDatabase {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val callback =
        object : SupportSQLiteOpenHelper.Callback(1) {
          override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)

          override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
    val config =
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(callback)
            .build()
    return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
  }
}
