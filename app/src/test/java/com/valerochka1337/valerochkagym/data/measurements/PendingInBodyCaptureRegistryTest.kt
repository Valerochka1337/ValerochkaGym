package com.valerochka1337.valerochkagym.data.measurements

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.health.PrivateOriginalsLifecycleGate
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PendingInBodyCaptureRegistryTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var registry: PendingInBodyCaptureRegistry

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        directory = File(context.cacheDir, "inbody_imports").also { it.deleteRecursively(); it.mkdirs() }
        File(context.noBackupFilesDir, "inbody_capture_registry").deleteRecursively()
        registry = PendingInBodyCaptureRegistry(context, PrivateOriginalsLifecycleGate())
    }

    @After fun tearDown() {
        directory.deleteRecursively()
        File(context.noBackupFilesDir, "inbody_capture_registry").deleteRecursively()
    }

    @Test fun `registered callback survives recovery until explicit abandon removes both marker and cache`() {
        val token = "camera.jpg"
        val capture = File(directory, token).also { it.writeText("private") }

        registry.register(token)
        registry.recoverCameraImports(directory)

        assertTrue(capture.isFile)
        registry.abandon(token, directory)
        assertFalse(capture.exists())
        assertFalse(File(context.noBackupFilesDir, "inbody_capture_registry").resolve(token).exists())
    }
}
