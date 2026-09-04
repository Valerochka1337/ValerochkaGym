package com.valerochka1337.valerochkagym.data.db

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration12To13Test {
  @Test
  fun `migration removes legacy zero keeps stabilizer and splits custom chest for review`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val helper =
        FrameworkSQLiteOpenHelperFactory()
            .create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(null)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(12) {
                          override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE exercises(id INTEGER PRIMARY KEY, name TEXT NOT NULL, muscleGroup TEXT NOT NULL, type TEXT NOT NULL, isCustom INTEGER NOT NULL, syncId TEXT NOT NULL, updatedAt INTEGER NOT NULL)"
                            )
                            db.execSQL(
                                "CREATE TABLE exercise_muscles(exerciseId INTEGER NOT NULL, muscle TEXT NOT NULL, contribution INTEGER NOT NULL, PRIMARY KEY(exerciseId,muscle))"
                            )
                          }

                          override fun onUpgrade(
                              db: androidx.sqlite.db.SupportSQLiteDatabase,
                              oldVersion: Int,
                              newVersion: Int,
                          ) = Unit
                        }
                    )
                    .build(),
            )
    helper.writableDatabase.use { db ->
      db.execSQL("INSERT INTO exercises VALUES(1,'Своё','CHEST','STRENGTH',1,'custom',7)")
      db.execSQL("INSERT INTO exercise_muscles VALUES(1,'CHEST',70)")
      db.execSQL("INSERT INTO exercise_muscles VALUES(1,'TRICEPS',10)")
      db.execSQL("INSERT INTO exercise_muscles VALUES(1,'ABS',0)")
      GymDatabase.MIGRATION_12_13.migrate(db)
      db.query(
              "SELECT muscle, contribution FROM exercise_muscles WHERE exerciseId=1 ORDER BY muscle"
          )
          .use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("LOWER_CHEST", rows.getString(0))
            assertEquals(100, rows.getInt(1))
            assertTrue(rows.moveToNext())
            assertEquals("TRICEPS", rows.getString(0))
            assertEquals(0, rows.getInt(1))
            assertTrue(rows.moveToNext())
            assertEquals("UPPER_CHEST", rows.getString(0))
            assertEquals(100, rows.getInt(1))
            assertTrue(!rows.moveToNext())
          }
      db.query("SELECT needsMuscleMapReview FROM exercises WHERE id=1").use { row ->
        assertTrue(row.moveToFirst())
        assertEquals(1, row.getInt(0))
      }
      db.query("SELECT id FROM muscle_load_upgrade_notice").use { row ->
        assertTrue(row.moveToFirst())
        assertEquals(1, row.getInt(0))
      }
    }
    helper.close()
  }
}
