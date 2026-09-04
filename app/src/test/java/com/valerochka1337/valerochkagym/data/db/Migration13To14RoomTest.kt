package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Validates v13→v14 against the committed v13 schema and Room's generated v14 schema. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration13To14RoomTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GymDatabase::class.java,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-13-14-room.db"

    @After
    fun cleanup() {
        context.deleteDatabase(name)
    }

    @Test
    fun `v13 database migrates through production list and preserves main and measurement records`() {
        helper.createDatabase(name, 13).use { database ->
            database.execSQL(
                "INSERT INTO exercises(id,name,muscleGroup,type,isCustom,syncId,updatedAt,needsMuscleMapReview) " +
                    "VALUES(99,'Своё','BACK','STRENGTH',1,'exercise-sync',123,1)",
            )
            database.execSQL(
                "INSERT INTO routines(id,syncId,updatedAt,name,note) " +
                    "VALUES(20,'routine-sync',456,'Программа','Заметка')",
            )
            database.execSQL(
                "INSERT INTO routine_exercises(id,routineId,exerciseId,position,restSeconds,plannedSetsJson) " +
                    "VALUES(30,20,99,2,90,'[{\"reps\":5}]')",
            )
            database.execSQL(
                "INSERT INTO body_measurements(id,measuredAt,weightKg,waistCm,uploadStatus,uploadError) " +
                    "VALUES('measurement-1',1700000000000,70.5,72.0,'UPLOADED',NULL)",
            )
        }

        helper.runMigrationsAndValidate(name, 14, true, *GymDatabase.ALL_MIGRATIONS).use { database ->
            assertEquals(
                listOf(99L, "Своё", "BACK", "STRENGTH", 1, "exercise-sync", 123L, 1),
                database.query(
                    "SELECT id,name,muscleGroup,type,isCustom,syncId,updatedAt,needsMuscleMapReview " +
                        "FROM exercises WHERE id = 99",
                ).singleRow {
                    listOf(
                        getLong(0), getString(1), getString(2), getString(3), getInt(4),
                        getString(5), getLong(6), getInt(7),
                    )
                },
            )
            assertEquals(
                listOf(20L, "routine-sync", 456L, "Программа", "Заметка"),
                database.query("SELECT id,syncId,updatedAt,name,note FROM routines WHERE id = 20")
                    .singleRow { listOf(getLong(0), getString(1), getLong(2), getString(3), getString(4)) },
            )
            assertEquals(
                listOf(30L, 20L, 99L, 2, 90, "[{\"reps\":5}]"),
                database.query(
                    "SELECT id,routineId,exerciseId,position,restSeconds,plannedSetsJson " +
                        "FROM routine_exercises WHERE id = 30",
                ).singleRow {
                    listOf(getLong(0), getLong(1), getLong(2), getInt(3), getInt(4), getString(5))
                },
            )
            database.query(
                "SELECT measuredAt,weightKg,waistCm,afterMeal,afterWorkout,unusualHydration,conditionNote " +
                    "FROM body_measurements WHERE id = 'measurement-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1_700_000_000_000L, cursor.getLong(0))
                assertEquals(70.5, cursor.getDouble(1), 0.0)
                assertEquals(72.0, cursor.getDouble(2), 0.0)
                assertEquals(0, cursor.getInt(3))
                assertEquals(0, cursor.getInt(4))
                assertEquals(0, cursor.getInt(5))
                assertNull(cursor.getString(6))
            }
            assertEquals(
                listOf("measurement-1", 1L, 1_700_000_000_000L, 0),
                database.query(
                    "SELECT syncId,version,updatedAt,isTombstone FROM measurement_snapshots " +
                        "WHERE syncId = 'measurement-1'",
                ).singleRow { listOf(getString(0), getLong(1), getLong(2), getInt(3)) },
            )
            assertEquals(
                listOf("MEASUREMENTS", "measurement-1", 1L, "measurement-1:1"),
                database.query(
                    "SELECT category,syncId,version,idempotencyKey FROM health_sync_outbox " +
                        "WHERE syncId = 'measurement-1'",
                ).singleRow { listOf(getString(0), getString(1), getLong(2), getString(3)) },
            )
            assertTrue(database.hasTable("health_reports"))
            assertTrue(database.hasTable("health_observations"))
            assertTrue(database.hasTable("health_restrictions"))
            assertTrue(database.hasTable("health_documents"))
            assertTrue(database.hasTable("measurement_documents"))
            database.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }

    private fun SupportSQLiteDatabase.hasTable(table: String): Boolean =
        query("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$table')")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0) == 1
            }

    private fun <T> android.database.Cursor.singleRow(transform: android.database.Cursor.() -> T): T {
        assertTrue(moveToFirst())
        val row = transform()
        assertFalse(moveToNext())
        return row
    }
}
