package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the direct upgrade boundary from a real v9 body_measurements definition. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration9To10Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-9-10.db"

    @After fun tearDown() { context.deleteDatabase(name) }

    @Test
    fun `migration preserves live measurement and creates its v1 snapshot and pending outbox`() {
        database().use { db ->
            db.execSQL(
                "INSERT INTO body_measurements (id, measuredAt, weightKg, waistCm, uploadStatus) " +
                    "VALUES ('legacy', 1700000000000, 70.5, 72.0, 'UPLOADED')",
            )

            GymDatabase.MIGRATION_9_10.migrate(db)

            db.query("SELECT id, measuredAt, weightKg, waistCm, uploadStatus FROM body_measurements").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy", cursor.getString(0))
                assertEquals(1_700_000_000_000L, cursor.getLong(1))
                assertEquals(70.5, cursor.getDouble(2), 0.0)
                assertEquals(72.0, cursor.getDouble(3), 0.0)
                assertEquals("UPLOADED", cursor.getString(4))
            }
            db.query("SELECT syncId, version, updatedAt, isTombstone, canonicalPayload, payloadHash FROM measurement_snapshots").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy", cursor.getString(0))
                assertEquals(1L, cursor.getLong(1))
                assertEquals(1_700_000_000_000L, cursor.getLong(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals(
                    "v1|id='legacy'|measuredAt=1700000000000|weightKg=70.5|skeletalMuscleMassKg=NULL" +
                        "|bodyFatPercentage=NULL|bodyFatMassKg=NULL|visceralFatLevel=NULL|waistHipRatio=NULL" +
                        "|waistCm=72.0|chestCm=NULL|hipsCm=NULL|rightRelaxedArmCm=NULL|rightThighCm=NULL" +
                        "|inBodyScore=NULL|totalBodyWaterLiters=NULL|proteinKg=NULL|mineralsKg=NULL" +
                        "|bodyMassIndex=NULL|fatFreeMassKg=NULL|basalMetabolicRateKcal=NULL" +
                        "|recommendedCalorieIntakeKcal=NULL|leftArmLeanMassKg=NULL|leftArmLeanPercentage=NULL" +
                        "|leftArmFatMassKg=NULL|leftArmFatPercentage=NULL|rightArmLeanMassKg=NULL" +
                        "|rightArmLeanPercentage=NULL|rightArmFatMassKg=NULL|rightArmFatPercentage=NULL" +
                        "|trunkLeanMassKg=NULL|trunkLeanPercentage=NULL|trunkFatMassKg=NULL" +
                        "|trunkFatPercentage=NULL|leftLegLeanMassKg=NULL|leftLegLeanPercentage=NULL" +
                        "|leftLegFatMassKg=NULL|leftLegFatPercentage=NULL|rightLegLeanMassKg=NULL" +
                        "|rightLegLeanPercentage=NULL|rightLegFatMassKg=NULL|rightLegFatPercentage=NULL",
                    cursor.getString(4),
                )
                assertNull(cursor.getString(5))
            }
            db.query("SELECT category, syncId, version, idempotencyKey, payloadHash FROM health_sync_outbox").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("MEASUREMENTS", cursor.getString(0))
                assertEquals("legacy", cursor.getString(1))
                assertEquals(1L, cursor.getLong(2))
                assertEquals("legacy:1", cursor.getString(3))
                assertNull(cursor.getString(4))
            }
            db.query("PRAGMA index_list(`health_sync_outbox`)").use { cursor ->
                val indexNames = generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.toList()
                assertTrue(indexNames.contains("sqlite_autoindex_health_sync_outbox_1"))
            }
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('health_report_snapshots', 'health_restriction_snapshots') ORDER BY name").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("health_report_snapshots", cursor.getString(0))
                assertTrue(cursor.moveToNext())
                assertEquals("health_restriction_snapshots", cursor.getString(0))
            }
            db.query("PRAGMA table_info(`health_reports`)").use { cursor ->
                val columns = generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.toSet()
                assertTrue(columns.contains("supersedesVersion"))
                assertTrue(columns.contains("correctionOfVersion"))
                assertTrue(columns.contains("collectedAt"))
                assertTrue(columns.contains("conditions"))
                assertTrue(columns.contains("originalExpected"))
            }
            db.query("PRAGMA table_info(`health_report_snapshots`)").use { cursor ->
                val columns = generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.toSet()
                assertTrue(columns.contains("operationId"))
            }
            db.query("PRAGMA table_info(`health_restrictions`)").use { cursor ->
                val columns = generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.toSet()
                assertTrue(columns.contains("originalText"))
            }
            db.query("PRAGMA table_info(`health_restriction_snapshots`)").use { cursor ->
                val columns = generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.toSet()
                assertTrue(columns.contains("originalText"))
            }
            db.query("SELECT afterMeal, afterWorkout, unusualHydration, conditionNote FROM body_measurements WHERE id = 'legacy'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
                assertNull(cursor.getString(3))
            }
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'measurement_documents'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("measurement_documents", cursor.getString(0))
            }
            db.query("PRAGMA foreign_key_list(`measurement_documents`)").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("body_measurements", cursor.getString(cursor.getColumnIndexOrThrow("table")))
                assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
            }
        }
    }

    @Test
    fun `migration snapshot keeps missing derived primary fields null`() {
        database().use { db ->
            db.execSQL(
                "INSERT INTO body_measurements (id, measuredAt, weightKg, bodyFatPercentage, waistCm, hipsCm, uploadStatus) " +
                    "VALUES ('nullable-primary', 1700000000000, 70.0, 20.0, 75.0, 100.0, 'UPLOADED')",
            )

            GymDatabase.MIGRATION_9_10.migrate(db)

            db.query("SELECT canonicalPayload FROM measurement_snapshots WHERE syncId = 'nullable-primary'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val payload = cursor.getString(0)
                assertTrue(payload.contains("|bodyFatPercentage=20.0|bodyFatMassKg=NULL|"))
                assertTrue(payload.contains("|waistHipRatio=NULL|waistCm=75.0|"))
            }
        }
    }

    private fun database(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(9) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE body_measurements (" +
                        "id TEXT NOT NULL, measuredAt INTEGER NOT NULL, weightKg REAL, skeletalMuscleMassKg REAL, " +
                        "bodyFatPercentage REAL, bodyFatMassKg REAL, visceralFatLevel INTEGER, waistHipRatio REAL, " +
                        "inBodyScore INTEGER, totalBodyWaterLiters REAL, proteinKg REAL, mineralsKg REAL, " +
                        "bodyMassIndex REAL, fatFreeMassKg REAL, basalMetabolicRateKcal INTEGER, " +
                        "recommendedCalorieIntakeKcal INTEGER, leftArmLeanMassKg REAL, leftArmLeanPercentage REAL, " +
                        "rightArmLeanMassKg REAL, rightArmLeanPercentage REAL, trunkLeanMassKg REAL, " +
                        "trunkLeanPercentage REAL, leftLegLeanMassKg REAL, leftLegLeanPercentage REAL, " +
                        "rightLegLeanMassKg REAL, rightLegLeanPercentage REAL, leftArmFatMassKg REAL, " +
                        "leftArmFatPercentage REAL, rightArmFatMassKg REAL, rightArmFatPercentage REAL, " +
                        "trunkFatMassKg REAL, trunkFatPercentage REAL, leftLegFatMassKg REAL, " +
                        "leftLegFatPercentage REAL, rightLegFatMassKg REAL, rightLegFatPercentage REAL, " +
                        "waistCm REAL, chestCm REAL, hipsCm REAL, rightRelaxedArmCm REAL, rightThighCm REAL, " +
                        "uploadStatus TEXT NOT NULL, uploadError TEXT, PRIMARY KEY(id))",
                )
                db.execSQL("CREATE INDEX index_body_measurements_measuredAt ON body_measurements(measuredAt)")
                db.execSQL("CREATE INDEX index_body_measurements_uploadStatus ON body_measurements(uploadStatus)")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        ).writableDatabase
    }
}
