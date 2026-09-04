package com.valerochka1337.valerochkagym.data.backup

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import com.valerochka1337.valerochkagym.data.health.HealthDocumentInput
import com.valerochka1337.valerochkagym.data.health.LocalHealthDocumentRepository
import com.valerochka1337.valerochkagym.data.health.PrivateOriginalsLifecycleGate
import com.valerochka1337.valerochkagym.data.measurements.LocalMeasurementDocumentRepository
import com.valerochka1337.valerochkagym.data.measurements.MeasurementDocumentInput
import com.valerochka1337.valerochkagym.data.measurements.PendingInBodyCaptureRegistry
import java.io.InputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClearDataUseCaseRaceTest {
    private lateinit var context: Context
    private lateinit var database: GymDatabase
    private lateinit var gate: PrivateOriginalsLifecycleGate
    private lateinit var registry: PendingInBodyCaptureRegistry

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        database = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).allowMainThreadQueries().build()
        gate = PrivateOriginalsLifecycleGate()
        registry = PendingInBodyCaptureRegistry(context, gate)
        roots().forEach { it.deleteRecursively() }
    }

    @After fun tearDown() {
        if (::database.isInitialized) database.close()
        roots().forEach { it.deleteRecursively() }
    }

    @Test fun `health document copy begun before clear cannot publish an original afterward`() = runTest {
        database.healthDao().upsertReport(HealthReportEntity("report", 1, 1, false, "FINAL", "LAB", 1, "CBC"))
        val repository = LocalHealthDocumentRepository(
            context, database, database.healthDao(), Dispatchers.Default, gate,
        )
        val source = BlockingInputStream("medical bytes".encodeToByteArray())
        val storing = async(Dispatchers.Default) {
            repository.store(HealthDocumentInput("report", "report.pdf", "application/pdf"), source)
        }

        source.awaitStarted()
        clearData()
        source.release()
        storing.await()

        assertFalse(File(context.noBackupFilesDir, "health_documents").exists())
    }

    @Test fun `measurement copy and registered camera callback begun before clear cannot recreate private state afterward`() = runTest {
        database.bodyMeasurementDao().insert(BodyMeasurementEntity("measurement", 1))
        val repository = LocalMeasurementDocumentRepository(
            context, database, database.measurementDocumentDao(), Dispatchers.Default, gate, registry,
        )
        registry.register("camera.jpg")
        File(context.cacheDir, "inbody_imports").apply { mkdirs() }.resolve("camera.jpg").writeText("camera")
        val source = BlockingInputStream("measurement bytes".encodeToByteArray())
        val storing = async(Dispatchers.Default) {
            repository.store(MeasurementDocumentInput("measurement", "scan.jpg", "image/jpeg"), source)
        }

        source.awaitStarted()
        clearData()
        source.release()
        storing.await()

        assertFalse(File(context.noBackupFilesDir, "measurement_documents").exists())
        assertFalse(File(context.noBackupFilesDir, "inbody_capture_registry").exists())
        assertFalse(File(context.cacheDir, "inbody_imports").exists())
    }

    private suspend fun clearData() {
        ClearDataUseCaseImpl(database, WorkManager.getInstance(context), context, gate, registry, UnconfinedTestDispatcher())()
    }

    private fun roots() = listOf(
        File(context.noBackupFilesDir, "health_documents"),
        File(context.noBackupFilesDir, "measurement_documents"),
        File(context.noBackupFilesDir, "inbody_capture_registry"),
        File(context.cacheDir, "inbody_imports"),
    )

    private class BlockingInputStream(private val bytes: ByteArray) : InputStream() {
        private val started = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private var offset = 0

        fun awaitStarted() = started.await()
        fun release() = release.countDown()

        override fun read(): Int = read(ByteArray(1), 0, 1).takeIf { it > 0 }?.let { bytes[offset - 1].toInt() and 0xff } ?: -1

        override fun read(buffer: ByteArray, off: Int, length: Int): Int {
            started.countDown()
            release.await()
            if (offset >= bytes.size) return -1
            val count = minOf(length, bytes.size - offset)
            bytes.copyInto(buffer, off, offset, offset + count)
            offset += count
            return count
        }
    }
}
