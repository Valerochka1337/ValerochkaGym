package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.Room
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

/** Opens a real v1 file through every handwritten migration and lets Room validate v9. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration1To9Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-1-9.db"

    @After fun tearDown() { context.deleteDatabase(name) }

    @Test
    fun `room opens v1 data through v9 with legacy section and sets intact`() {
        createV1().use { db ->
            db.execSQL("INSERT INTO exercises VALUES (1, 'Жим', 'CHEST', 'STRENGTH', 1)")
            db.execSQL("INSERT INTO workouts VALUES ('w', NULL, 'Тренировка', 1, NULL, '', 'PENDING', NULL)")
            db.execSQL("INSERT INTO workout_exercises VALUES (1, 'w', 1, 0)")
            db.execSQL("INSERT INTO workout_sets VALUES (1, 1, 0, 70.0, 10, NULL, NULL, NULL, 1)")
        }
        val db = Room.databaseBuilder(context, GymDatabase::class.java, name)
            .addMigrations(
                GymDatabase.MIGRATION_1_2, GymDatabase.MIGRATION_2_3,
                GymDatabase.MIGRATION_3_4, GymDatabase.MIGRATION_4_5,
                GymDatabase.MIGRATION_5_6, GymDatabase.MIGRATION_6_7,
                GymDatabase.MIGRATION_7_8, GymDatabase.MIGRATION_8_9,
            )
            .allowMainThreadQueries()
            .build()

        db.openHelper.writableDatabase.query(
            "SELECT sectionId, variantSyncId, variantNameSnapshot FROM workout_exercises WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(0).isNotBlank())
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM workout_sets WHERE workoutExerciseId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.close()
    }

    private fun createV1(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) = v1Schema.forEach(db::execSQL)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        ).writableDatabase
    }

    private companion object {
        val v1Schema = listOf(
            "CREATE TABLE exercises (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, muscleGroup TEXT NOT NULL, type TEXT NOT NULL, isCustom INTEGER NOT NULL)",
            "CREATE TABLE routines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, note TEXT NOT NULL)",
            "CREATE TABLE routine_exercises (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, routineId INTEGER NOT NULL, exerciseId INTEGER NOT NULL, position INTEGER NOT NULL, restSeconds INTEGER, plannedSetsJson TEXT NOT NULL, FOREIGN KEY(routineId) REFERENCES routines(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
            "CREATE INDEX index_routine_exercises_routineId ON routine_exercises(routineId)",
            "CREATE INDEX index_routine_exercises_exerciseId ON routine_exercises(exerciseId)",
            "CREATE TABLE scheduled_workouts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, routineId INTEGER NOT NULL, dateTimeMillis INTEGER NOT NULL, calendarEventId TEXT NOT NULL, FOREIGN KEY(routineId) REFERENCES routines(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_scheduled_workouts_routineId ON scheduled_workouts(routineId)",
            "CREATE TABLE workouts (id TEXT NOT NULL PRIMARY KEY, routineId INTEGER, name TEXT NOT NULL, startedAt INTEGER NOT NULL, finishedAt INTEGER, note TEXT NOT NULL, uploadStatus TEXT NOT NULL, uploadError TEXT, FOREIGN KEY(routineId) REFERENCES routines(id) ON UPDATE NO ACTION ON DELETE SET NULL)",
            "CREATE INDEX index_workouts_routineId ON workouts(routineId)",
            "CREATE INDEX index_workouts_finishedAt ON workouts(finishedAt)",
            "CREATE TABLE workout_exercises (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, workoutId TEXT NOT NULL, exerciseId INTEGER NOT NULL, position INTEGER NOT NULL, FOREIGN KEY(workoutId) REFERENCES workouts(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
            "CREATE INDEX index_workout_exercises_workoutId ON workout_exercises(workoutId)",
            "CREATE INDEX index_workout_exercises_exerciseId ON workout_exercises(exerciseId)",
            "CREATE TABLE workout_sets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, workoutExerciseId INTEGER NOT NULL, setIndex INTEGER NOT NULL, weightKg REAL, reps INTEGER, durationSec INTEGER, speedKmh REAL, inclinePct REAL, isCompleted INTEGER NOT NULL, FOREIGN KEY(workoutExerciseId) REFERENCES workout_exercises(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_workout_sets_workoutExerciseId ON workout_sets(workoutExerciseId)",
        )
    }
}
