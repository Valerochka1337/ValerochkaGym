package com.valerochka1337.valerochkagym.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Base for DAO tests that run against a fresh in-memory [GymDatabase] with main-thread queries
 * allowed. Subclasses grab their DAOs off [db] and reuse [tableCount] for raw row-count assertions.
 * Seeding is intentionally absent — the database opens empty so each test controls its own data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
abstract class RoomDaoTest {

    protected lateinit var db: GymDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    protected fun tableCount(table: String): Int =
        db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM $table")).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
