package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Проверяет ручную миграцию v3 → v4 с таблицей `body_measurements`. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration3To4Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-3-4.db"

    @After
    fun deleteDatabase() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 3 to 4 creates nullable measurement columns and indices`() {
        createV3Database().use { database ->
            GymDatabase.MIGRATION_3_4.migrate(database)

            database.execSQL(
                "INSERT INTO body_measurements (id, measuredAt, weightKg, uploadStatus) " +
                    "VALUES ('m1', 1000, 70.0, 'PENDING')",
            )
            database.query("SELECT weightKg, waistCm, uploadStatus FROM body_measurements WHERE id = 'm1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(70.0, cursor.getDouble(0), 1e-6)
                assertTrue(cursor.isNull(1))
                assertEquals("PENDING", cursor.getString(2))
            }
            database.query("PRAGMA index_list('body_measurements')").use { cursor ->
                val names = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(names.contains("index_body_measurements_measuredAt"))
                assertTrue(names.contains("index_body_measurements_uploadStatus"))
            }
        }
    }

    @Test
    fun `room opens a migrated v3 database`() = runTest {
        createV3Database().close()

        val database = Room.databaseBuilder(context, GymDatabase::class.java, dbName)
            .addMigrations(
                GymDatabase.MIGRATION_1_2,
                GymDatabase.MIGRATION_2_3,
                GymDatabase.MIGRATION_3_4,
                GymDatabase.MIGRATION_4_5,
                GymDatabase.MIGRATION_5_6,
                GymDatabase.MIGRATION_6_7,
                GymDatabase.MIGRATION_7_8,
                GymDatabase.MIGRATION_8_9,
            )
            .allowMainThreadQueries()
            .build()

        assertTrue(database.bodyMeasurementDao().getNotUploaded().isEmpty())
        database.close()
    }

    /** Полная схема v3 из `schemas/.../3.json`, чтобы Room мог проверить результат миграции. */
    private fun createV3Database(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                v3Schema.forEach(db::execSQL)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(callback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    private companion object {
        val v3Schema = listOf(
            "CREATE TABLE IF NOT EXISTS `exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `muscleGroup` TEXT NOT NULL, `type` TEXT NOT NULL, `isCustom` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `exercise_muscles` (`exerciseId` INTEGER NOT NULL, `muscle` TEXT NOT NULL, " +
                "`contribution` INTEGER NOT NULL, PRIMARY KEY(`exerciseId`, `muscle`), " +
                "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_exercise_muscles_exerciseId` ON `exercise_muscles` (`exerciseId`)",
            "CREATE TABLE IF NOT EXISTS `routines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `note` TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `routine_exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`routineId` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                "`restSeconds` INTEGER, `plannedSetsJson` TEXT NOT NULL, " +
                "FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX IF NOT EXISTS `index_routine_exercises_routineId` ON `routine_exercises` (`routineId`)",
            "CREATE INDEX IF NOT EXISTS `index_routine_exercises_exerciseId` ON `routine_exercises` (`exerciseId`)",
            "CREATE TABLE IF NOT EXISTS `scheduled_workouts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`routineId` INTEGER NOT NULL, `dateTimeMillis` INTEGER NOT NULL, `calendarEventId` TEXT NOT NULL, " +
                "FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_scheduled_workouts_routineId` ON `scheduled_workouts` (`routineId`)",
            "CREATE TABLE IF NOT EXISTS `workouts` (`id` TEXT NOT NULL, `routineId` INTEGER, `name` TEXT NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `note` TEXT NOT NULL, " +
                "`uploadStatus` TEXT NOT NULL, `uploadError` TEXT, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            "CREATE INDEX IF NOT EXISTS `index_workouts_routineId` ON `workouts` (`routineId`)",
            "CREATE INDEX IF NOT EXISTS `index_workouts_finishedAt` ON `workouts` (`finishedAt`)",
            "CREATE TABLE IF NOT EXISTS `workout_exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`workoutId` TEXT NOT NULL, `exerciseId` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                "FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE INDEX IF NOT EXISTS `index_workout_exercises_workoutId` ON `workout_exercises` (`workoutId`)",
            "CREATE INDEX IF NOT EXISTS `index_workout_exercises_exerciseId` ON `workout_exercises` (`exerciseId`)",
            "CREATE TABLE IF NOT EXISTS `workout_sets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`workoutExerciseId` INTEGER NOT NULL, `setIndex` INTEGER NOT NULL, `weightKg` REAL, `reps` INTEGER, " +
                "`durationSec` INTEGER, `speedKmh` REAL, `inclinePct` REAL, `isCompleted` INTEGER NOT NULL, " +
                "`completedAt` INTEGER, FOREIGN KEY(`workoutExerciseId`) REFERENCES `workout_exercises`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_workout_sets_workoutExerciseId` ON `workout_sets` (`workoutExerciseId`)",
        )
    }
}
