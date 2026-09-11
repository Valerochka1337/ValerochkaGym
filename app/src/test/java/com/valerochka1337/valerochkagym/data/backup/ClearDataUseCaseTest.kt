package com.valerochka1337.valerochkagym.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.data.backend.BackendSyncScheduler
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.db.LegacyCoachArchiveRegistry
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class ClearDataUseCaseTest : RoomDaoTest() {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Before
  fun initializeWorkManager() {
    WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
  }

  @Test
  fun `clear data removes every retained legacy coach archive`() = runTest {
    LegacyCoachArchiveRegistry.archiveTableNames().forEach { table ->
      db.openHelper.writableDatabase.execSQL("CREATE TABLE `$table` (id INTEGER PRIMARY KEY)")
      db.openHelper.writableDatabase.execSQL("INSERT INTO `$table` VALUES(1)")
    }
    val sync = BackendSync(db, OfflineTransport, Store())
    val clear =
        ClearDataUseCaseImpl(
            db,
            WorkManager.getInstance(context),
            sync,
            BackendSyncScheduler(context, db),
        )

    clear()

    LegacyCoachArchiveRegistry.archiveTableNames().forEach { table ->
      db.openHelper.writableDatabase
          .query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table))
          .use { assertFalse(it.moveToFirst()) }
    }
  }

  private class Store : BackendSessionStore {
    override val session = MutableStateFlow<BackendTokens?>(null)
    override val sessionEpoch: Long = 0

    override fun save(tokens: BackendTokens?) {
      session.value = tokens
    }
  }

  private object OfflineTransport : BackendTransport {
    override val json = Json

    override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement =
        throw IOException()

    override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement =
        throw IOException()
  }
}
