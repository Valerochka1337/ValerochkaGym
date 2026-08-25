package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Проверяет одноразовый сброс старых карт каталога перед досевом глобальной шкалы. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration5To6Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-5-6.db"

    @After
    fun deleteDatabase() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration drops catalogue maps and preserves custom exercise maps`() {
        createV5Database().use { database ->
            insertExercise(database, id = 1, name = "Беговая дорожка", isCustom = false)
            insertExercise(database, id = 2, name = "Моё кардио", isCustom = true)
            database.execSQL("INSERT INTO exercise_muscles VALUES (1, 'QUADS', 100)")
            database.execSQL("INSERT INTO exercise_muscles VALUES (2, 'QUADS', 42)")

            GymDatabase.MIGRATION_5_6.migrate(database)

            assertEquals(0, countRows(database, 1))
            assertEquals(1, countRows(database, 2))
        }
    }

    private fun createV5Database(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(5) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE exercises (id INTEGER PRIMARY KEY, name TEXT NOT NULL, " +
                        "muscleGroup TEXT NOT NULL, type TEXT NOT NULL, isCustom INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE exercise_muscles (exerciseId INTEGER NOT NULL, muscle TEXT NOT NULL, " +
                        "contribution INTEGER NOT NULL, PRIMARY KEY(exerciseId, muscle))",
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
            "INSERT INTO exercises VALUES (?, ?, 'CARDIO', 'CARDIO', ?)",
            arrayOf<Any?>(id, name, if (isCustom) 1 else 0),
        )
    }

    private fun countRows(database: SupportSQLiteDatabase, exerciseId: Long): Int =
        database.query("SELECT COUNT(*) FROM exercise_muscles WHERE exerciseId = $exerciseId").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
