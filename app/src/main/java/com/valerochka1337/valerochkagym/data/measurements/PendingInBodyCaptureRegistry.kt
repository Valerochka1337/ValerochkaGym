package com.valerochka1337.valerochkagym.data.measurements

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.valerochka1337.valerochkagym.data.health.PrivateOriginalsLifecycleGate
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

/**
 * Durable, private ownership markers for a camera result that can outlive a configuration change.
 * Markers contain only the random filename token and timestamp, live under no-backup storage and
 * are serialized with cleanup so an external camera result cannot be swept between launch and
 * callback. A registered capture deliberately has no time expiry: the camera app controls its
 * duration. A new capture explicitly abandons old markers instead.
 */
@Singleton
class PendingInBodyCaptureRegistry @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val lifecycleGate: PrivateOriginalsLifecycleGate = PrivateOriginalsLifecycleGate(),
) {
    private val markerDirectory get() = File(context.noBackupFilesDir, DIRECTORY)

    fun register(token: String, now: Long = System.currentTimeMillis()) = guarded {
        markerDirectory.mkdirs()
        File(markerDirectory, token).writeText(now.toString())
    }

    fun clear(token: String) = guarded { File(markerDirectory, token).delete() }

    /** Atomically replaces any abandoned capture ownership with the next external-camera token. */
    fun replaceWith(token: String, cameraDirectory: File, now: Long = System.currentTimeMillis()) = guarded {
        markerDirectory.mkdirs()
        markerDirectory.listFiles().orEmpty().forEach { marker ->
            cameraDirectory.resolve(marker.name).delete()
            marker.delete()
        }
        File(markerDirectory, token).writeText(now.toString())
    }

    /** Cancellation/back explicitly abandons the private camera file and its durable marker. */
    fun abandon(token: String, cameraDirectory: File) = guarded {
        File(markerDirectory, token).delete()
        cameraDirectory.resolve(token).delete()
    }

    /** Removes only unregistered orphan cache files. Registered external captures always survive. */
    fun recoverCameraImports(cameraDirectory: File) = guarded {
        markerDirectory.mkdirs()
        val active = markerDirectory.listFiles().orEmpty().map { it.name }.toSet()
        cameraDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.name !in active }
            .forEach(File::delete)
    }

    /** Called only by Clear Data while it already owns [PrivateOriginalsLifecycleGate]. */
    fun clearAllWhileLocked(cameraDirectory: File): List<File> {
        val failures = mutableListOf<File>()
        markerDirectory.listFiles().orEmpty().forEach { marker ->
            if (marker.isFile && !marker.delete()) failures += marker
        }
        if (markerDirectory.exists() && !markerDirectory.delete() && markerDirectory.exists()) failures += markerDirectory
        cameraDirectory.listFiles().orEmpty().forEach { image ->
            if (image.isFile && !image.delete()) failures += image
        }
        if (cameraDirectory.exists() && !cameraDirectory.delete() && cameraDirectory.exists()) failures += cameraDirectory
        return failures
    }

    private fun guarded(block: () -> Unit) = runBlocking { lifecycleGate.withLock { block() } }

    private companion object {
        const val DIRECTORY = "inbody_capture_registry"
    }
}
