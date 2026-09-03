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

/** Exercises the published v13 boundary where health storage is introduced. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration13To14Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-13-14.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun `migration preserves measurements and creates health projection with v1 delivery history`() {
        database().use { db ->
            db.execSQL(
                "INSERT INTO body_measurements (id, measuredAt, weightKg, waistCm, uploadStatus) " +
                    "VALUES ('legacy', 1700000000000, 70.5, 72.0, 'UPLOADED')",
            )

            GymDatabase.MIGRATION_13_14.migrate(db)

            db.query(
                "SELECT afterMeal, afterWorkout, unusualHydration, conditionNote " +
                    "FROM body_measurements WHERE id = 'legacy'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
                assertNull(cursor.getString(3))
            }
            db.query(
                "SELECT syncId, version, updatedAt, isTombstone, canonicalPayload, payloadHash " +
                    "FROM measurement_snapshots",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy", cursor.getString(0))
                assertEquals(1L, cursor.getLong(1))
                assertEquals(1_700_000_000_000L, cursor.getLong(2))
                assertEquals(0, cursor.getInt(3))
                assertTrue(cursor.getString(4).startsWith("v1|id='legacy'|measuredAt=1700000000000|"))
                assertTrue(cursor.getString(4).contains("|rightLegFatMassKg=NULL|rightLegFatPercentage=NULL"))
                assertNull(cursor.getString(5))
            }
            db.query(
                "SELECT category, syncId, version, idempotencyKey, payloadHash FROM health_sync_outbox",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("MEASUREMENTS", cursor.getString(0))
                assertEquals("legacy", cursor.getString(1))
                assertEquals(1L, cursor.getLong(2))
                assertEquals("legacy:1", cursor.getString(3))
                assertNull(cursor.getString(4))
            }
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN " +
                    "('health_reports', 'health_observations', 'health_restrictions', " +
                    "'health_documents', 'measurement_documents') ORDER BY name",
            ).use { cursor ->
                val tables = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
                assertEquals(
                    setOf(
                        "health_documents",
                        "health_observations",
                        "health_reports",
                        "health_restrictions",
                        "measurement_documents",
                    ),
                    tables,
                )
            }
        }
    }

    private fun database(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(13) {
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
