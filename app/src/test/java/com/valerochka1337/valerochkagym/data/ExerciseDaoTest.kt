package com.valerochka1337.valerochkagym.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.GymDatabaseCallback
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.seedExercises
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Provider

/**
 * Exercises the real [GymDatabaseCallback] wiring. Seeding runs asynchronously on a coroutine scope
 * through the single onOpen path, so each test triggers the open and then waits for the catalogue to
 * settle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ExerciseDaoTest {

    private val openDatabases = mutableListOf<GymDatabase>()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        openDatabases.forEach { it.close() }
        scopes.forEach { it.cancel() }
    }

    @Test
    fun `first open seeds the whole built-in catalogue`() {
        val db = buildSeedingDatabase(fileName = null)
        db.openHelper.writableDatabase // triggers onOpen on the freshly created database

        awaitExerciseCount(db.exerciseDao(), seedExercises.size)

        val all = runBlocking { db.exerciseDao().getAll().first() }
        assertEquals(seedExercises.size, all.size)
        assertEquals(MuscleGroup.entries.toSet(), all.map { it.muscleGroup }.toSet())
    }

    @Test
    fun `onOpen reseeds when the exercises table is empty`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("exercise-dao-reseed-test.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        // Create the schema with an empty exercises table (no seeding callback), simulating a
        // process death that interrupted the initial seed.
        val plain = Room.databaseBuilder(context, GymDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        plain.openHelper.writableDatabase
        assertEquals(0, runBlocking { plain.exerciseDao().count() })
        plain.close()

        // Reopen with the real callback: onOpen must re-seed because the table is empty.
        val db = buildSeedingDatabase(fileName = dbFile.absolutePath)
        db.openHelper.writableDatabase

        awaitExerciseCount(db.exerciseDao(), seedExercises.size)

        dbFile.delete()
    }

    @Test
    fun `onOpen keeps the catalogue intact when it is already seeded`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("exercise-dao-keep-test.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        val first = buildSeedingDatabase(fileName = dbFile.absolutePath)
        first.openHelper.writableDatabase // onOpen seeds the fresh database
        awaitExerciseCount(first.exerciseDao(), seedExercises.size)
        first.close()
        openDatabases.remove(first)

        // Reopen: onOpen sees a non-empty table and must not add duplicates.
        val second = buildSeedingDatabase(fileName = dbFile.absolutePath)
        second.openHelper.writableDatabase
        awaitExerciseCount(second.exerciseDao(), seedExercises.size)
        assertEquals(seedExercises.size, runBlocking { second.exerciseDao().count() })

        dbFile.delete()
    }

    private fun buildSeedingDatabase(fileName: String?): GymDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope

        lateinit var holder: GymDatabase
        val callback = GymDatabaseCallback(Provider { holder }, scope)
        val builder = if (fileName == null) {
            Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
        } else {
            Room.databaseBuilder(context, GymDatabase::class.java, fileName)
        }
        val db = builder
            .allowMainThreadQueries()
            .addCallback(callback)
            .build()
        holder = db
        openDatabases += db
        return db
    }

    private fun awaitExerciseCount(dao: ExerciseDao, expected: Int, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = -1
        while (System.currentTimeMillis() < deadline) {
            last = runBlocking { dao.count() }
            if (last == expected) return
            Thread.sleep(25)
        }
        throw AssertionError("Expected $expected exercises within ${timeoutMs}ms, last count was $last")
    }
}
