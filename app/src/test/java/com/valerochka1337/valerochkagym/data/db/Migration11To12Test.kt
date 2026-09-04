package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses the emulator's v11-only nullable key, unique index and muscle FK DDL. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration11To12Test {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val name = "migration-11-12.db"

  @After
  fun tearDown() {
    context.deleteDatabase(name)
  }

  @Test
  fun `room recovers exact v11 variant schema preserving completed and incomplete sets`() {
    MigrationRecoveryFixtures.createCurrentDatabase(context, name).use { sql ->
      MigrationRecoveryFixtures.prepareVariantSchema(sql, version = 11, includeV11Additions = true)
      MigrationRecoveryFixtures.seedVariantData(sql)
      sql.execSQL("UPDATE exercise_variants SET selectionKey = 'narrow' WHERE id = 1")
      sql.execSQL(
          "INSERT INTO exercise_variants VALUES (2, 'variant-sync-null', 1, 'Нейтральный', 'нейтральный', 0, 4, NULL)"
      )
      sql.execSQL("INSERT INTO exercise_variant_muscles VALUES ('variant-sync', 'CHEST', 100)")
      sql.execSQL(
          "INSERT INTO exercise_variant_muscles VALUES ('variant-sync-null', 'TRICEPS', 40)"
      )
    }

    val db = MigrationRecoveryFixtures.openThroughProductionList(context, name)
    try {
      MigrationRecoveryFixtures.assertBaseOnlyRecovery(db.openHelper.writableDatabase)
    } finally {
      db.close()
    }
  }
}
