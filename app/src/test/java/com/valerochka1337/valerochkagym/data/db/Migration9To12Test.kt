package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Proves the shipping v9 → v10 → v12 → v13 → v14 route using the production registry. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration9To12Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-9-12.db"

    @After fun tearDown() { context.deleteDatabase(name) }

    @Test
    fun `room opens v9 data through v14 preserving base rows sets and health backfill`() {
        MigrationRecoveryFixtures.createV13Database(context, name).use { sql ->
            MigrationRecoveryFixtures.prepareVariantSchema(sql, version = 9, includeV11Additions = false)
            MigrationRecoveryFixtures.seedVariantData(sql)
        }

        val db = MigrationRecoveryFixtures.openThroughProductionList(context, name)
        try {
            MigrationRecoveryFixtures.assertBaseOnlyRecovery(db.openHelper.writableDatabase)
        } finally {
            db.close()
        }
    }
}

internal object MigrationRecoveryFixtures {
    /**
     * The recovery fixtures start from the last published pre-health schema. They then set an
     * older user_version only to exercise the historical variant recovery paths; Room still has
     * to finish through v14 and validate health's v13→v14 migration.
     */
    fun createV13Database(context: Context, name: String): SupportSQLiteDatabase {
        context.deleteDatabase(name)
        return MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            GymDatabase::class.java,
        ).createDatabase(name, 13)
    }

    fun prepareVariantSchema(
        db: SupportSQLiteDatabase,
        version: Int,
        includeV11Additions: Boolean,
    ) {
        db.execSQL(
            "CREATE TABLE `exercise_variants` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `syncId` TEXT NOT NULL, " +
                "`exerciseId` INTEGER NOT NULL, `name` TEXT NOT NULL, `normalizedName` TEXT NOT NULL, " +
                "`isArchived` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL" +
                (if (includeV11Additions) ", `selectionKey` TEXT" else "") +
                ", FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE UNIQUE INDEX `index_exercise_variants_syncId` ON `exercise_variants` (`syncId`)")
        db.execSQL("CREATE UNIQUE INDEX `index_exercise_variants_exerciseId_syncId` ON `exercise_variants` (`exerciseId`,`syncId`)")
        db.execSQL("CREATE UNIQUE INDEX `index_exercise_variants_exerciseId_normalizedName` ON `exercise_variants` (`exerciseId`,`normalizedName`)")
        db.execSQL("CREATE INDEX `index_exercise_variants_exerciseId_isArchived` ON `exercise_variants` (`exerciseId`,`isArchived`)")
        if (includeV11Additions) {
            db.execSQL("CREATE UNIQUE INDEX `index_exercise_variants_exerciseId_selectionKey` ON `exercise_variants` (`exerciseId`, `selectionKey`)")
            db.execSQL(
                "CREATE TABLE `exercise_variant_muscles` (`variantSyncId` TEXT NOT NULL, `muscle` TEXT NOT NULL, " +
                    "`contribution` INTEGER NOT NULL, PRIMARY KEY(`variantSyncId`,`muscle`), " +
                    "FOREIGN KEY(`variantSyncId`) REFERENCES `exercise_variants`(`syncId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL("CREATE INDEX `index_exercise_variant_muscles_variantSyncId` ON `exercise_variant_muscles` (`variantSyncId`)")
        }

        db.execSQL(
            "CREATE TABLE `routine_exercises_variant` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `routineId` INTEGER NOT NULL, " +
                "`exerciseId` INTEGER NOT NULL, `variantSyncId` TEXT, `position` INTEGER NOT NULL, " +
                "`restSeconds` INTEGER, `plannedSetsJson` TEXT NOT NULL, " +
                "FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION, " +
                "FOREIGN KEY(`exerciseId`,`variantSyncId`) REFERENCES `exercise_variants`(`exerciseId`,`syncId`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        )
        db.execSQL("DROP TABLE `routine_exercises`")
        db.execSQL("ALTER TABLE `routine_exercises_variant` RENAME TO `routine_exercises`")
        db.execSQL("CREATE INDEX `index_routine_exercises_routineId` ON `routine_exercises` (`routineId`)")
        db.execSQL("CREATE INDEX `index_routine_exercises_exerciseId` ON `routine_exercises` (`exerciseId`)")
        db.execSQL("CREATE INDEX `index_routine_exercises_exerciseId_variantSyncId` ON `routine_exercises` (`exerciseId`,`variantSyncId`)")

        db.execSQL(
            "CREATE TABLE `workout_exercises_variant` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workoutId` TEXT NOT NULL, " +
                "`exerciseId` INTEGER NOT NULL, `sectionId` TEXT NOT NULL, `variantSyncId` TEXT, " +
                "`variantNameSnapshot` TEXT, `position` INTEGER NOT NULL, " +
                "FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
        )
        db.execSQL("DROP TABLE `workout_exercises`")
        db.execSQL("ALTER TABLE `workout_exercises_variant` RENAME TO `workout_exercises`")
        db.execSQL("CREATE INDEX `index_workout_exercises_workoutId` ON `workout_exercises` (`workoutId`)")
        db.execSQL("CREATE INDEX `index_workout_exercises_exerciseId` ON `workout_exercises` (`exerciseId`)")
        db.execSQL("CREATE UNIQUE INDEX `index_workout_exercises_sectionId` ON `workout_exercises` (`sectionId`)")
        db.execSQL("PRAGMA user_version = $version")
    }

    fun seedVariantData(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO exercises VALUES (1, 'Жим', 'CHEST', 'STRENGTH', 1, 'exercise-sync', 1, 0)")
        db.execSQL("INSERT INTO routines VALUES (1, 'routine-sync', 2, 'A', '')")
        db.execSQL("INSERT INTO workouts VALUES ('complete', 1, 'Готовая', 1, 2, '', 'UPLOADED', NULL)")
        db.execSQL("INSERT INTO workouts VALUES ('active', 1, 'Активная', 3, NULL, '', 'PENDING', NULL)")
        db.execSQL("INSERT INTO exercise_variants (id, syncId, exerciseId, name, normalizedName, isArchived, updatedAt) VALUES (1, 'variant-sync', 1, 'Узкий хват', 'узкий хват', 0, 3)")
        db.execSQL("INSERT INTO routine_exercises VALUES (2, 1, 1, 'variant-sync', 4, 90, '[\"8x8\"]')")
        db.execSQL("INSERT INTO workout_exercises VALUES (10, 'complete', 1, 'completed-section', 'variant-sync', 'Узкий хват', 0)")
        db.execSQL("INSERT INTO workout_exercises VALUES (11, 'active', 1, 'active-section', NULL, NULL, 1)")
        db.execSQL("INSERT INTO workout_sets VALUES (20, 10, 0, 70.0, 10, 30, 9.5, 4.0, 1, 1000)")
        db.execSQL("INSERT INTO workout_sets VALUES (21, 11, 1, 72.5, 8, 45, 8.0, 2.0, 0, NULL)")
        db.execSQL(
            "INSERT INTO body_measurements(id,measuredAt,weightKg,uploadStatus,uploadError) " +
                "VALUES('legacy-measurement',999,71.5,'UPLOADED',NULL)",
        )
    }

    fun openThroughProductionList(context: Context, name: String): GymDatabase =
        Room.databaseBuilder(context, GymDatabase::class.java, name)
            .addMigrations(*GymDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    fun assertBaseOnlyRecovery(sql: SupportSQLiteDatabase) {
        sql.query("PRAGMA user_version").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(14, cursor.getInt(0))
        }
        sql.query("SELECT id, routineId, exerciseId, position, restSeconds, plannedSetsJson FROM routine_exercises").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2L, cursor.getLong(0)); assertEquals(1L, cursor.getLong(1)); assertEquals(1L, cursor.getLong(2))
            assertEquals(4, cursor.getInt(3)); assertEquals(90, cursor.getInt(4)); assertEquals("[\"8x8\"]", cursor.getString(5))
        }
        sql.query("SELECT id, workoutId, exerciseId, sectionId, position FROM workout_exercises ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals(10L, cursor.getLong(0)); assertEquals("complete", cursor.getString(1)); assertEquals("completed-section", cursor.getString(3)); assertEquals(0, cursor.getInt(4))
            assertTrue(cursor.moveToNext()); assertEquals(11L, cursor.getLong(0)); assertEquals("active", cursor.getString(1)); assertEquals("active-section", cursor.getString(3)); assertEquals(1, cursor.getInt(4))
        }
        sql.query("SELECT id, workoutExerciseId, setIndex, weightKg, reps, durationSec, speedKmh, inclinePct, isCompleted, completedAt FROM workout_sets ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals(20L, cursor.getLong(0)); assertEquals(10L, cursor.getLong(1)); assertEquals(0, cursor.getInt(2)); assertEquals(70.0, cursor.getDouble(3), 0.0); assertEquals(10, cursor.getInt(4)); assertEquals(30, cursor.getInt(5)); assertEquals(9.5, cursor.getDouble(6), 0.0); assertEquals(4.0, cursor.getDouble(7), 0.0); assertEquals(1, cursor.getInt(8)); assertEquals(1000L, cursor.getLong(9))
            assertTrue(cursor.moveToNext()); assertEquals(21L, cursor.getLong(0)); assertEquals(11L, cursor.getLong(1)); assertEquals(1, cursor.getInt(2)); assertEquals(72.5, cursor.getDouble(3), 0.0); assertEquals(8, cursor.getInt(4)); assertEquals(45, cursor.getInt(5)); assertEquals(8.0, cursor.getDouble(6), 0.0); assertEquals(2.0, cursor.getDouble(7), 0.0); assertEquals(0, cursor.getInt(8)); assertTrue(cursor.isNull(9))
        }
        sql.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('exercise_variants', 'exercise_variant_muscles')").use { cursor -> assertFalse(cursor.moveToFirst()) }
        assertColumnMissing(sql, "routine_exercises", "variantSyncId")
        assertColumnMissing(sql, "workout_exercises", "variantSyncId")
        assertColumnMissing(sql, "workout_exercises", "variantNameSnapshot")
        assertIndexes(sql, "routine_exercises", setOf("index_routine_exercises_routineId", "index_routine_exercises_exerciseId"))
        assertIndexes(sql, "workout_exercises", setOf("index_workout_exercises_workoutId", "index_workout_exercises_exerciseId", "index_workout_exercises_sectionId"))
        assertForeignKeyParents(sql, "routine_exercises", setOf("routines", "exercises"))
        assertForeignKeyParents(sql, "workout_exercises", setOf("workouts", "exercises"))
        sql.query(
            "SELECT afterMeal,afterWorkout,unusualHydration,conditionNote FROM body_measurements " +
                "WHERE id = 'legacy-measurement'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(0, cursor.getInt(2))
            assertTrue(cursor.isNull(3))
        }
        sql.query(
            "SELECT syncId,version,updatedAt,isTombstone FROM measurement_snapshots " +
                "WHERE syncId = 'legacy-measurement'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy-measurement", cursor.getString(0))
            assertEquals(1L, cursor.getLong(1))
            assertEquals(999L, cursor.getLong(2))
            assertEquals(0, cursor.getInt(3))
        }
        sql.query(
            "SELECT category,syncId,version,idempotencyKey FROM health_sync_outbox " +
                "WHERE syncId = 'legacy-measurement'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("MEASUREMENTS", cursor.getString(0))
            assertEquals("legacy-measurement", cursor.getString(1))
            assertEquals(1L, cursor.getLong(2))
            assertEquals("legacy-measurement:1", cursor.getString(3))
        }
        sql.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN " +
                "('health_reports','health_observations','health_restrictions','health_documents')",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(4, cursor.getInt(0))
        }
        sql.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }
    }

    private fun assertColumnMissing(sql: SupportSQLiteDatabase, table: String, column: String) {
        sql.query("PRAGMA table_info('$table')").use { cursor ->
            while (cursor.moveToNext()) assertFalse(column == cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
    }

    private fun assertIndexes(sql: SupportSQLiteDatabase, table: String, expected: Set<String>) {
        sql.query("PRAGMA index_list('$table')").use { cursor ->
            val actual = mutableSetOf<String>()
            while (cursor.moveToNext()) actual += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            assertTrue(actual.containsAll(expected))
        }
    }

    private fun assertForeignKeyParents(sql: SupportSQLiteDatabase, table: String, expected: Set<String>) {
        sql.query("PRAGMA foreign_key_list('$table')").use { cursor ->
            val actual = mutableSetOf<String>()
            while (cursor.moveToNext()) actual += cursor.getString(cursor.getColumnIndexOrThrow("table"))
            assertEquals(expected, actual)
        }
    }
}
