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

/**
 * Проверяет апгрейд v2 → v3 (появление `exercise_muscles`) на файловой базе, собранной по точному
 * DDL версии 2 (см. `schemas/2.json`).
 *
 * Главная проверка — не «таблица появилась», а то, что после цепочки миграций базу открывает
 * **сам Room**: он сверяет фактическую схему с текущей ожидаемой версией и падает на любом
 * расхождении в типах, ключах или индексах. Это ловит расхождение
 * [GymDatabase.MIGRATION_2_3] с сгенерированной схемой, которое отдельными `SELECT`-ами не
 * увидеть.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration2To3Test {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Файловая (не in-memory) база: миграция версии срабатывает только при повторном открытии. */
    private val dbName = "migration-2-3.db"

    /** Полная схема версии 2 — то, что лежит в `schemas/2.json`, с подставленными именами таблиц. */
    private val v2Schema = listOf(
        "CREATE TABLE IF NOT EXISTS `exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, `muscleGroup` TEXT NOT NULL, `type` TEXT NOT NULL, " +
            "`isCustom` INTEGER NOT NULL)",
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

    @After
    fun deleteDatabase() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 2 to 3 creates exercise_muscles and keeps existing rows`() {
        createV2Database().use { db ->
            db.execSQL(
                "INSERT INTO exercises (name, muscleGroup, type, isCustom) VALUES ('Жим', 'CHEST', 'STRENGTH', 0)",
            )

            GymDatabase.MIGRATION_2_3.migrate(db)

            db.query("SELECT COUNT(*) FROM exercises").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0)) // строки версии 2 переживают апгрейд
            }
            db.query("SELECT COUNT(*) FROM exercise_muscles").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0)) // таблица создаётся пустой — её заполняет сеятель
            }
        }
    }

    @Test
    fun `room opens a migrated v2 database`() = runTest {
        createV2Database().use { db ->
            db.execSQL(
                "INSERT INTO exercises (name, muscleGroup, type, isCustom) VALUES ('Жим', 'CHEST', 'STRENGTH', 0)",
            )
        }

        // Room сам прогонит всю цепочку до текущей версии и сверит итоговую схему.
        val database = Room.databaseBuilder(context, GymDatabase::class.java, dbName)
            .addMigrations(
                GymDatabase.MIGRATION_1_2,
                GymDatabase.MIGRATION_2_3,
                GymDatabase.MIGRATION_3_4,
                GymDatabase.MIGRATION_4_5,
                GymDatabase.MIGRATION_5_6,
                GymDatabase.MIGRATION_6_7,
                GymDatabase.MIGRATION_7_8,
            )
            .allowMainThreadQueries()
            .build()

        val exercises = database.exerciseDao().getAllOnce()
        assertEquals(1, exercises.size)
        assertTrue(database.exerciseMuscleDao().getMappedExerciseIds().isEmpty())
        database.close()
    }

    /** Открывает файловую базу со схемой v2 и `user_version = 2`. */
    private fun createV2Database(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                v2Schema.forEach(db::execSQL)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(callback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }
}
