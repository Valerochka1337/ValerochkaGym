package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A file-backed v10 base-only schema validates through the production no-op v10 → v12. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration10To12Test {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val name = "migration-10-12.db"

  @After
  fun tearDown() {
    context.deleteDatabase(name)
  }

  @Test
  fun `room opens file backed v10 schema through the no op migration`() {
    MigrationRecoveryFixtures.createCurrentDatabase(context, name).use { sql ->
      MigrationRecoveryFixtures.removeV14EquipmentSchema(sql)
      sql.execSQL("PRAGMA user_version = 10")
    }
    val db = MigrationRecoveryFixtures.openThroughProductionList(context, name)
    try {
      db.openHelper.writableDatabase.query("PRAGMA user_version").use { cursor ->
        assertEquals(
            14,
            cursor.run {
              moveToFirst()
              getInt(0)
            },
        )
      }
    } finally {
      db.close()
    }
  }
}
