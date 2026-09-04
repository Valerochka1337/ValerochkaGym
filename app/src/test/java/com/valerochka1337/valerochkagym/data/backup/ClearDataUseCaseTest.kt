package com.valerochka1337.valerochkagym.data.backup

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.health.PrivateOriginalsLifecycleGate
import com.valerochka1337.valerochkagym.data.measurements.PendingInBodyCaptureRegistry
import java.io.File
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
class ClearDataUseCaseTest {
    private lateinit var context: Context
    private lateinit var database: GymDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        database = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).allowMainThreadQueries().build()
        privateRoots().forEach { it.deleteRecursively() }
    }

    @After fun tearDown() {
        if (::database.isInitialized) database.close()
        privateRoots().forEach { it.deleteRecursively() }
    }

    @Test fun `clear data removes health measurement camera and marker private roots together`() = runTest {
        val gate = PrivateOriginalsLifecycleGate()
        val registry = PendingInBodyCaptureRegistry(context, gate)
        val camera = File(context.cacheDir, "inbody_imports").apply { mkdirs() }
        File(context.noBackupFilesDir, "health_documents").apply { mkdirs() }.resolve("health.tmp").writeText("private")
        File(context.noBackupFilesDir, "measurement_documents").apply { mkdirs() }.resolve("measurement.tmp").writeText("private")
        File(camera, "camera.jpg").writeText("private")
        registry.register("camera.jpg")

        ClearDataUseCaseImpl(database, WorkManager.getInstance(context), context, gate, registry, UnconfinedTestDispatcher())()

        privateRoots().forEach { assertFalse(it.exists()) }
    }

    private fun privateRoots() = listOf(
        File(context.noBackupFilesDir, "health_documents"),
        File(context.noBackupFilesDir, "measurement_documents"),
        File(context.noBackupFilesDir, "inbody_capture_registry"),
        File(context.cacheDir, "inbody_imports"),
    )
}
