package com.valerochka1337.valerochkagym.data.measurements

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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
) {
    private val lock = Any()
    private val markerDirectory get() = File(context.noBackupFilesDir, DIRECTORY)

    fun register(token: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        markerDirectory.mkdirs()
        File(markerDirectory, token).writeText(now.toString())
    }

    fun clear(token: String) = synchronized(lock) { File(markerDirectory, token).delete() }

    /** Atomically replaces any abandoned capture ownership with the next external-camera token. */
    fun replaceWith(token: String, cameraDirectory: File, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        markerDirectory.mkdirs()
        markerDirectory.listFiles().orEmpty().forEach { marker ->
            cameraDirectory.resolve(marker.name).delete()
            marker.delete()
        }
        File(markerDirectory, token).writeText(now.toString())
    }

    /** Cancellation/back explicitly abandons the private camera file and its durable marker. */
    fun abandon(token: String, cameraDirectory: File) = synchronized(lock) {
        File(markerDirectory, token).delete()
        cameraDirectory.resolve(token).delete()
    }

    /** Removes only unregistered orphan cache files. Registered external captures always survive. */
    fun recoverCameraImports(cameraDirectory: File) = synchronized(lock) {
        markerDirectory.mkdirs()
        val active = markerDirectory.listFiles().orEmpty().map { it.name }.toSet()
        cameraDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.name !in active }
            .forEach(File::delete)
    }

    private companion object {
        const val DIRECTORY = "inbody_capture_registry"
    }
}
