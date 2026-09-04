package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.db.entity.builtInExerciseSyncId
import com.valerochka1337.valerochkagym.data.db.entity.migratedCustomExerciseSyncId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Проверяет стабильные exercise UUID и нормализованные таблицы залов миграции v7 → v8. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration7To8Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-7-8.db"

    @After
    fun deleteDatabase() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration gives exercises deterministic unique sync ids and creates gym tables`() {
        createV7Database().use { database ->
            insertExercise(database, id = 41, name = "Жим штанги лёжа", isCustom = false)
            insertExercise(database, id = 42, name = "Моё упражнение", isCustom = true)
            insertExercise(database, id = 43, name = "Моё упражнение", isCustom = true)

            GymDatabase.MIGRATION_7_8.migrate(database)

            val rows = mutableMapOf<Long, Pair<String, Long>>()
            database.query("SELECT id, syncId, updatedAt FROM exercises ORDER BY id").use { cursor ->
                while (cursor.moveToNext()) {
                    rows[cursor.getLong(0)] = cursor.getString(1) to cursor.getLong(2)
                }
            }
            assertEquals(builtInExerciseSyncId("Жим штанги лёжа"), rows.getValue(41).first)
            assertEquals(migratedCustomExerciseSyncId(42, "Моё упражнение"), rows.getValue(42).first)
            assertEquals(migratedCustomExerciseSyncId(43, "Моё упражнение"), rows.getValue(43).first)
            assertEquals(3, rows.values.map { it.first }.toSet().size)
            assertTrue(rows.values.all { it.second > 0L })
            assertEquals(1L, rows.getValue(41).second)
            assertEquals(rows.getValue(42).second, rows.getValue(43).second)
            assertTrue(rows.getValue(42).second > rows.getValue(41).second)

            val tables = database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN " +
                    "('gyms', 'gym_exercises', 'routine_gyms', 'workout_gyms', " +
                    "'configuration_tombstones')",
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            assertEquals(
                setOf(
                    "gyms",
                    "gym_exercises",
                    "routine_gyms",
                    "workout_gyms",
                    "configuration_tombstones",
                ),
                tables,
            )
            assertTrue(indexNames(database, "exercises").contains("index_exercises_syncId"))
            assertTrue(indexNames(database, "gyms").contains("index_gyms_syncId"))
        }
    }

    @Test
    fun `fresh seed and migrated catalogue use the same stable sync id`() {
        val legacyNames = legacySeedExercises.map { it.name }.toSet()
        seedExercises.filter { it.name in legacyNames }.forEach { seed ->
            assertEquals(builtInExerciseSyncId(seed.name), seed.syncId)
            assertTrue(seed.updatedAt > 0L)
        }
        assertEquals(seedExercises.size, seedExercises.map { it.syncId }.toSet().size)
    }

    private fun createV7Database(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(7) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE exercises (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, muscleGroup TEXT NOT NULL, type TEXT NOT NULL, " +
                        "isCustom INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE routines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "syncId TEXT NOT NULL, updatedAt INTEGER NOT NULL, name TEXT NOT NULL, note TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE workouts (id TEXT NOT NULL PRIMARY KEY, routineId INTEGER, " +
                        "name TEXT NOT NULL, startedAt INTEGER NOT NULL, finishedAt INTEGER, " +
                        "note TEXT NOT NULL, uploadStatus TEXT NOT NULL, uploadError TEXT)",
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(dbName).callback(callback).build(),
        ).writableDatabase
    }

    private fun insertExercise(database: SupportSQLiteDatabase, id: Long, name: String, isCustom: Boolean) {
        database.execSQL(
            "INSERT INTO exercises (id, name, muscleGroup, type, isCustom) VALUES (?, ?, 'FULL_BODY', 'STRENGTH', ?)",
            arrayOf<Any?>(id, name, if (isCustom) 1 else 0),
        )
    }

    private fun indexNames(database: SupportSQLiteDatabase, table: String): Set<String> =
        database.query("PRAGMA index_list('$table')").use { cursor ->
            buildSet {
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
        }
}
