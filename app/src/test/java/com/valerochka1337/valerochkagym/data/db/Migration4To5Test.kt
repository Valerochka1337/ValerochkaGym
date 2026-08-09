package com.valerochka1337.valerochkagym.data.db

import android.content.Context
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

/** Verifies the hand-written v4 → v5 DDL for the nullable full InBody report. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration4To5Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-4-5.db"

    @After
    fun deleteDatabase() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 4 to 5 keeps legacy measurements and adds nullable InBody report columns`() {
        createV4Database().use { database ->
            database.execSQL(
                "INSERT INTO body_measurements (id, measuredAt, weightKg, uploadStatus) " +
                    "VALUES ('manual', 1000, 70.0, 'PENDING')",
            )

            GymDatabase.MIGRATION_4_5.migrate(database)

            database.execSQL(
                "INSERT INTO body_measurements (id, measuredAt, bodyFatMassKg, inBodyScore, " +
                    "leftArmLeanMassKg, rightLegFatPercentage, uploadStatus) " +
                    "VALUES ('inbody', 2000, 14.8, 74, 1.99, 85.6, 'PENDING')",
            )
            database.query(
                "SELECT weightKg, bodyFatMassKg, inBodyScore, leftArmLeanMassKg, rightLegFatPercentage " +
                    "FROM body_measurements WHERE id = 'manual'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(70.0, cursor.getDouble(0), 1e-6)
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
                assertTrue(cursor.isNull(4))
            }
            database.query(
                "SELECT bodyFatMassKg, inBodyScore, leftArmLeanMassKg, rightLegFatPercentage " +
                    "FROM body_measurements WHERE id = 'inbody'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(14.8, cursor.getDouble(0), 1e-6)
                assertEquals(74, cursor.getInt(1))
                assertEquals(1.99, cursor.getDouble(2), 1e-6)
                assertEquals(85.6, cursor.getDouble(3), 1e-6)
            }
            database.query("PRAGMA table_info('body_measurements')").use { cursor ->
                val names = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(REPORT_COLUMNS.all(names::contains))
            }
        }
    }

    private fun createV4Database(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(4) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `body_measurements` (" +
                        "`id` TEXT NOT NULL, `measuredAt` INTEGER NOT NULL, " +
                        "`weightKg` REAL, `skeletalMuscleMassKg` REAL, " +
                        "`bodyFatPercentage` REAL, `visceralFatLevel` INTEGER, " +
                        "`waistHipRatio` REAL, `waistCm` REAL, `chestCm` REAL, " +
                        "`hipsCm` REAL, `rightRelaxedArmCm` REAL, `rightThighCm` REAL, " +
                        "`uploadStatus` TEXT NOT NULL, `uploadError` TEXT, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_body_measurements_measuredAt` ON `body_measurements` (`measuredAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_body_measurements_uploadStatus` ON `body_measurements` (`uploadStatus`)")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(dbName).callback(callback).build(),
        ).writableDatabase
    }

    private companion object {
        val REPORT_COLUMNS = setOf(
            "bodyFatMassKg",
            "inBodyScore",
            "totalBodyWaterLiters",
            "proteinKg",
            "mineralsKg",
            "bodyMassIndex",
            "fatFreeMassKg",
            "basalMetabolicRateKcal",
            "recommendedCalorieIntakeKcal",
            "leftArmLeanMassKg",
            "leftArmLeanPercentage",
            "rightArmLeanMassKg",
            "rightArmLeanPercentage",
            "trunkLeanMassKg",
            "trunkLeanPercentage",
            "leftLegLeanMassKg",
            "leftLegLeanPercentage",
            "rightLegLeanMassKg",
            "rightLegLeanPercentage",
            "leftArmFatMassKg",
            "leftArmFatPercentage",
            "rightArmFatMassKg",
            "rightArmFatPercentage",
            "trunkFatMassKg",
            "trunkFatPercentage",
            "leftLegFatMassKg",
            "leftLegFatPercentage",
            "rightLegFatMassKg",
            "rightLegFatPercentage",
        )
    }
}
