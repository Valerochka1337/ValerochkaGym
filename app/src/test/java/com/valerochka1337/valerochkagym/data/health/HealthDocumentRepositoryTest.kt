package com.valerochka1337.valerochkagym.data.health

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.HealthDocumentEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HealthDocumentRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: GymDatabase
    private lateinit var repository: LocalHealthDocumentRepository
    private lateinit var directory: File

    @Before fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).allowMainThreadQueries().build()
        database.healthDao().upsertReport(HealthReportEntity("report", 1, 1, false, "CONFIRMED", "lab", 1, "Report"))
        repository = LocalHealthDocumentRepository(
            context, database, database.healthDao(), UnconfinedTestDispatcher(), PrivateOriginalsLifecycleGate(),
        )
        directory = File(context.noBackupFilesDir, "health_documents").also { it.deleteRecursively(); it.mkdirs() }
    }
    @After fun tearDown() { if (::database.isInitialized) database.close(); if (::directory.isInitialized) directory.deleteRecursively() }

    @Test fun `store makes verified original ready and delete removes exact metadata and file`() = runTest {
        val bytes = "private document".encodeToByteArray()
        val result = repository.store(HealthDocumentInput("report", "r.pdf", "application/pdf"), ByteArrayInputStream(bytes))
        val ready = result as HealthDocumentStoreResult.Ready
        assertTrue(repository.hasReadyDocument(ready.documentId))
        assertTrue(File(directory, ready.sha256).isFile)
        assertFalse(File(directory, "${ready.sha256}.tmp").exists())
        assertTrue(repository.delete(ready.documentId))
        assertFalse(repository.hasReadyDocument(ready.documentId))
        assertFalse(File(directory, ready.sha256).exists())
    }

    @Test fun `recovery removes dangling pending copy and keeps ready original`() = runTest {
        val pendingHash = hash("partial".encodeToByteArray())
        database.healthDao().upsertDocument(HealthDocumentEntity("pending", "report", pendingHash, "PENDING", "p", "image/jpeg", 7, createdAt = 1))
        File(directory, "$pendingHash.tmp").writeText("partial")
        val readyHash = hash("ready".encodeToByteArray())
        database.healthDao().upsertDocument(HealthDocumentEntity("ready", "report", readyHash, "READY", "r", "image/jpeg", 5, createdAt = 1))
        File(directory, readyHash).writeText("ready")
        repository.recoverInterruptedCopies()
        repository.recoverInterruptedCopies()
        assertFalse(File(directory, "$pendingHash.tmp").exists())
        assertTrue(File(directory, readyHash).isFile)
        assertTrue(repository.hasReadyDocument("ready"))
    }

    @Test fun `recovery removes corrupt ready metadata and orphan private bytes`() = runTest {
        val digest = hash("expected".encodeToByteArray())
        database.healthDao().upsertDocument(
            HealthDocumentEntity("corrupt", "report", digest, "READY", "r", "image/jpeg", 8, createdAt = 1),
        )
        File(directory, digest).writeText("different")
        File(directory, "orphan").writeText("private")

        repository.recoverInterruptedCopies()

        assertFalse(repository.hasReadyDocument("corrupt"))
        assertFalse(File(directory, digest).exists())
        assertFalse(File(directory, "orphan").exists())
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
