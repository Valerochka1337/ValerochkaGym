package com.valerochka1337.valerochkagym.data.measurements

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncConflictEntity
import com.valerochka1337.valerochkagym.data.db.entity.MeasurementSnapshotEntity
import com.valerochka1337.valerochkagym.data.db.entity.MeasurementDocumentEntity
import com.valerochka1337.valerochkagym.data.health.ConflictChoice
import com.valerochka1337.valerochkagym.data.health.SyncConflictResolver
import com.valerochka1337.valerochkagym.data.settings.HealthSyncCategory as SettingsCategory
import com.valerochka1337.valerochkagym.domain.measurements.ParsedMeasurementSnapshot
import com.valerochka1337.valerochkagym.worker.HealthSyncScheduler
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MeasurementDocumentRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: GymDatabase
    private lateinit var repository: LocalMeasurementDocumentRepository
    private lateinit var directory: File
    private lateinit var lifecycleGate: MeasurementDocumentLifecycleGate
    private lateinit var captureRegistry: PendingInBodyCaptureRegistry

    @Before fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).allowMainThreadQueries().build()
        database.bodyMeasurementDao().insert(BodyMeasurementEntity("m1", 1))
        database.bodyMeasurementDao().insert(BodyMeasurementEntity("m2", 2))
        lifecycleGate = MeasurementDocumentLifecycleGate()
        captureRegistry = PendingInBodyCaptureRegistry(context)
        repository = LocalMeasurementDocumentRepository(
            context, database, database.measurementDocumentDao(), UnconfinedTestDispatcher(),
            lifecycleGate = lifecycleGate, captureRegistry = captureRegistry,
        )
        directory = File(context.noBackupFilesDir, "measurement_documents").also { it.deleteRecursively(); it.mkdirs() }
    }

    @After fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test fun `store makes bounded InBody original ready with verified hash and sanitized metadata`() = runTest {
        val bytes = "private inbody".encodeToByteArray()
        val result = repository.store(
            MeasurementDocumentInput("m1", " scan/one.jpg ", "IMAGE/JPEG"),
            ByteArrayInputStream(bytes),
        ) as MeasurementDocumentStoreResult.Ready

        val file = repository.resolveReadyFile(result.documentId)
        assertTrue(file!!.isFile)
        assertEquals(hash(bytes), result.sha256)
        assertEquals(listOf(result.documentId), repository.observeReady("m1").first().map { it.id })
        assertEquals("scan_one.jpg", database.measurementDocumentDao().document(result.documentId)!!.displayName)
    }

    @Test fun `oversize and cancellation leave no pending metadata or temporary file`() = runTest {
        val oversize = ByteArray(6 * 1024 * 1024 + 1)
        assertTrue(
            repository.store(MeasurementDocumentInput("m1", "x.jpg", "image/jpeg"), ByteArrayInputStream(oversize))
                is MeasurementDocumentStoreResult.Failure,
        )
        try {
            repository.store(MeasurementDocumentInput("m1", "x.jpg", "image/jpeg"), CancellingInputStream())
        } catch (_: CancellationException) {
            // The cancellation is deliberately observable to the caller.
        }
        assertTrue(database.measurementDocumentDao().forMeasurement("m1").isEmpty())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test fun `direct stream ownership closes validation read failure oversize and cancellation streams`() = runTest {
        val invalid = TrackingInputStream("x".encodeToByteArray())
        assertTrue(repository.store(MeasurementDocumentInput("m1", "x.bin", "application/octet-stream"), invalid) is MeasurementDocumentStoreResult.Failure)
        assertTrue(invalid.closed)
        val oversize = TrackingInputStream(ByteArray(6 * 1024 * 1024 + 1))
        assertTrue(repository.store(MeasurementDocumentInput("m1", "x.jpg", "image/jpeg"), oversize) is MeasurementDocumentStoreResult.Failure)
        assertTrue(oversize.closed)
        val failing = ThrowingTrackingInputStream(IllegalStateException("read"))
        assertTrue(repository.store(MeasurementDocumentInput("m1", "x.jpg", "image/jpeg"), failing) is MeasurementDocumentStoreResult.Failure)
        assertTrue(failing.closed)
        val cancelled = ThrowingTrackingInputStream(CancellationException("cancel"))
        try {
            repository.store(MeasurementDocumentInput("m1", "x.jpg", "image/jpeg"), cancelled)
        } catch (_: CancellationException) {
            Unit
        }
        assertTrue(cancelled.closed)
    }

    @Test fun `post rename cancellation and parent FK loss remove pending temp and final bytes`() = runTest {
        val cancelling = LocalMeasurementDocumentRepository(
            context, database, database.measurementDocumentDao(), UnconfinedTestDispatcher(),
            lifecycleGate, captureRegistry,
            lifecycleHook = object : MeasurementDocumentLifecycleHook {
                override suspend fun afterRenameBeforeReady() { throw CancellationException("after rename") }
            },
        )
        try {
            cancelling.store(MeasurementDocumentInput("m1", "x.jpg", "image/jpeg"), ByteArrayInputStream("secret".encodeToByteArray()))
        } catch (_: CancellationException) {
            Unit
        }
        assertTrue(database.measurementDocumentDao().forMeasurement("m1").isEmpty())
        assertTrue(directory.listFiles().orEmpty().isEmpty())

        val parentDeleting = LocalMeasurementDocumentRepository(
            context, database, database.measurementDocumentDao(), UnconfinedTestDispatcher(),
            lifecycleGate, captureRegistry,
            lifecycleHook = object : MeasurementDocumentLifecycleHook {
                override suspend fun afterRenameBeforeReady() { database.bodyMeasurementDao().delete("m1") }
            },
        )
        assertTrue(parentDeleting.store(MeasurementDocumentInput("m1", "x.jpg", "image/jpeg"), ByteArrayInputStream("secret".encodeToByteArray())) is MeasurementDocumentStoreResult.Failure)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun `recovery removes pending temporary corrupt and orphan files but retains verified ready original`() = runTest {
        val readyBytes = "ready".encodeToByteArray()
        val readyHash = hash(readyBytes)
        database.measurementDocumentDao().upsert(MeasurementDocumentEntity("ready", "m1", readyHash, "READY", "r.jpg", "image/jpeg", readyBytes.size.toLong(), 1))
        File(directory, readyHash).writeBytes(readyBytes)
        val pendingHash = hash("pending".encodeToByteArray())
        database.measurementDocumentDao().upsert(MeasurementDocumentEntity("pending", "m1", pendingHash, "PENDING", "p.jpg", "image/jpeg", 7, 1))
        File(directory, "pending.$pendingHash.tmp").writeText("pending")
        val corruptHash = hash("expected".encodeToByteArray())
        database.measurementDocumentDao().upsert(MeasurementDocumentEntity("corrupt", "m2", corruptHash, "READY", "c.jpg", "image/jpeg", 8, 1))
        File(directory, corruptHash).writeText("wrong")
        File(directory, hash("orphan".encodeToByteArray())).writeText("orphan")

        repository.recoverInterruptedCopies()

        assertTrue(repository.resolveReadyFile("ready")!!.isFile)
        assertNull(database.measurementDocumentDao().document("pending"))
        assertNull(database.measurementDocumentDao().document("corrupt"))
        assertFalse(File(directory, "pending.$pendingHash.tmp").exists())
        assertFalse(File(directory, corruptHash).exists())
        assertEquals(1, directory.listFiles().orEmpty().size)
    }

    @Test fun `measurement delete cascades metadata keeps shared bytes then removes final unreferenced file`() = runTest {
        val bytes = "shared".encodeToByteArray()
        val first = repository.store(MeasurementDocumentInput("m1", "one.jpg", "image/jpeg"), ByteArrayInputStream(bytes))
            as MeasurementDocumentStoreResult.Ready
        repository.store(MeasurementDocumentInput("m2", "two.jpg", "image/jpeg"), ByteArrayInputStream(bytes))
        val measurements = MeasurementRepository(database, repository)

        assertTrue(measurements.delete("m1", now = 10))
        assertNull(database.measurementDocumentDao().document(first.documentId))
        assertTrue(File(directory, first.sha256).isFile)
        assertTrue(measurements.delete("m2", now = 20))
        assertFalse(File(directory, first.sha256).exists())
        val outboxPayloads = database.healthSyncOutboxDao().pending("MEASUREMENTS").map { it.canonicalPayload }
        assertTrue(outboxPayloads.none { it.contains("one.jpg") || it.contains(first.sha256) })
    }

    @Test fun `duplicate hash finalization and first measurement deletion cannot lose second ready file`() = runTest {
        val reachedRename = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var armed = false
        val sharedGate = MeasurementDocumentLifecycleGate()
        val firstRepository = LocalMeasurementDocumentRepository(
            context, database, database.measurementDocumentDao(), UnconfinedTestDispatcher(),
            lifecycleGate = sharedGate, captureRegistry = captureRegistry,
        )
        val secondRepository = LocalMeasurementDocumentRepository(
            context, database, database.measurementDocumentDao(), UnconfinedTestDispatcher(),
            sharedGate, captureRegistry,
            lifecycleHook = object : MeasurementDocumentLifecycleHook {
                override suspend fun afterRenameBeforeReady() {
                    if (armed) {
                        reachedRename.complete(Unit)
                        release.await()
                    }
                }
            },
        )
        val bytes = "shared race".encodeToByteArray()
        val first = firstRepository.store(MeasurementDocumentInput("m1", "one.jpg", "image/jpeg"), ByteArrayInputStream(bytes))
            as MeasurementDocumentStoreResult.Ready
        armed = true
        val second = async { secondRepository.store(MeasurementDocumentInput("m2", "two.jpg", "image/jpeg"), ByteArrayInputStream(bytes)) }
        reachedRename.await()
        val deleting = async { MeasurementRepository(database, secondRepository).delete("m1", now = 10) }
        release.complete(Unit)
        assertTrue(second.await() is MeasurementDocumentStoreResult.Ready)
        assertTrue(deleting.await())
        assertTrue(File(directory, first.sha256).isFile)
        assertEquals(first.sha256, secondRepository.readyForMeasurement("m2").single().sha256)
    }

    @Test fun `imported tombstones immediately remove only unshared ready originals`() = runTest {
        val bytes = "shared imported".encodeToByteArray()
        val first = repository.store(MeasurementDocumentInput("m1", "one.jpg", "image/jpeg"), ByteArrayInputStream(bytes))
            as MeasurementDocumentStoreResult.Ready
        repository.store(MeasurementDocumentInput("m2", "two.jpg", "image/jpeg"), ByteArrayInputStream(bytes))
        val measurements = MeasurementRepository(database, repository)

        assertTrue(measurements.applyImported(tombstone("m1", 1)))
        assertNull(database.measurementDocumentDao().document(first.documentId))
        assertTrue(File(directory, first.sha256).isFile)
        assertTrue(measurements.applyImported(tombstone("m2", 1)))
        assertFalse(File(directory, first.sha256).exists())
    }

    @Test fun `conflict resolved tombstone removes an unshared ready original after successor commit`() = runTest {
        val bytes = "conflict original".encodeToByteArray()
        val ready = repository.store(MeasurementDocumentInput("m1", "one.jpg", "image/jpeg"), ByteArrayInputStream(bytes))
            as MeasurementDocumentStoreResult.Ready
        val local = database.bodyMeasurementDao().getById("m1")!!
        val localPayload = MeasurementRepository.canonicalPayload(local)
        val tombstonePayload = MeasurementRepository.canonicalPayload(local, isTombstone = true)
        database.healthDao().insertMeasurementSnapshot(
            MeasurementSnapshotEntity("m1", 1, 1, false, localPayload, MeasurementRepository.sha256(localPayload)),
        )
        database.healthDao().upsertConflict(
            HealthSyncConflictEntity(
                HealthSyncCategory.MEASUREMENTS, "m1", 1, localPayload,
                MeasurementRepository.sha256(localPayload), tombstonePayload,
                MeasurementRepository.sha256(tombstonePayload), 2,
            ),
        )

        SyncConflictResolver(database, NoOpScheduler, repository).resolve(
            HealthSyncCategory.MEASUREMENTS, "m1", 1, ConflictChoice.REMOTE, now = 10,
        )

        assertNull(database.measurementDocumentDao().document(ready.documentId))
        assertFalse(File(directory, ready.sha256).exists())
    }

    @Test fun `startup recovery waits for an active finalization and retains its verified ready file`() = runTest {
        val reachedRename = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = LocalMeasurementDocumentRepository(
            context, database, database.measurementDocumentDao(), UnconfinedTestDispatcher(),
            lifecycleGate, captureRegistry,
            lifecycleHook = object : MeasurementDocumentLifecycleHook {
                override suspend fun afterRenameBeforeReady() {
                    reachedRename.complete(Unit)
                    release.await()
                }
            },
        )
        val saving = async {
            gated.store(MeasurementDocumentInput("m1", "one.jpg", "image/jpeg"), ByteArrayInputStream("race".encodeToByteArray()))
        }
        reachedRename.await()
        val recovering = async { gated.recoverInterruptedCopies() }
        release.complete(Unit)
        val ready = saving.await() as MeasurementDocumentStoreResult.Ready
        recovering.await()
        assertTrue(gated.resolveReadyFile(ready.documentId)!!.isFile)
    }

    @Test fun `cold start camera recovery removes unclaimed import cache files`() = runTest {
        val cameraDirectory = File(context.cacheDir, "inbody_imports").apply { mkdirs() }
        val stale = File(cameraDirectory, "inbody-stale.jpg").also { it.writeText("private") }

        repository.recoverCameraImports()

        assertFalse(stale.exists())
    }

    @Test fun `camera recovery retains an old registered capture and removes only unregistered cache files`() = runTest {
        val cameraDirectory = File(context.cacheDir, "inbody_imports").apply { mkdirs() }
        val activeToken = "inbody-active.jpg"
        val active = File(cameraDirectory, activeToken).also { it.writeText("active private") }
        val orphan = File(cameraDirectory, "inbody-orphan.jpg").also { it.writeText("orphan private") }
        captureRegistry.register(activeToken, 0L)

        // A fresh repository instance observes the app-scoped no-backup token registry.
        PendingInBodyCaptureRegistry(context).recoverCameraImports(cameraDirectory)

        assertTrue(active.exists())
        assertFalse(orphan.exists())
    }

    @Test fun `starting a replacement camera capture abandons prior registered file`() {
        val cameraDirectory = File(context.cacheDir, "inbody_imports").apply { mkdirs() }
        val old = File(cameraDirectory, "inbody-old.jpg").also { it.writeText("private") }
        captureRegistry.register(old.name, 0L)

        captureRegistry.replaceWith("inbody-new.jpg", cameraDirectory, now = 1)
        File(cameraDirectory, "inbody-new.jpg").writeText("new private")
        captureRegistry.recoverCameraImports(cameraDirectory)

        assertFalse(old.exists())
        assertTrue(File(cameraDirectory, "inbody-new.jpg").exists())
        captureRegistry.abandon("inbody-new.jpg", cameraDirectory)
        assertFalse(File(cameraDirectory, "inbody-new.jpg").exists())
    }

    @Test fun `removing ready originals leaves pending metadata untouched`() = runTest {
        val pending = MeasurementDocumentEntity(
            id = "pending", measurementId = "m1", sha256 = "pending-hash", state = "PENDING",
            displayName = "pending.jpg", mimeType = "image/jpeg", byteSize = 1, createdAt = 1,
        )
        database.measurementDocumentDao().upsert(pending)
        repository.store(
            MeasurementDocumentInput("m1", "ready.jpg", "image/jpeg"),
            ByteArrayInputStream("ready".encodeToByteArray()),
        )

        assertEquals(1, repository.deleteAll("m1"))
        assertEquals(pending, database.measurementDocumentDao().document("pending"))
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun tombstone(id: String, version: Long): ParsedMeasurementSnapshot {
        val measurement = BodyMeasurementEntity(id, version)
        return ParsedMeasurementSnapshot(
            measurement = measurement,
            version = version,
            updatedAt = version,
            isDeleted = true,
            payloadHash = null,
            idempotencyKey = "$id:$version",
            canonicalPayload = MeasurementRepository.canonicalPayload(measurement, isTombstone = true),
        )
    }

    private object NoOpScheduler : HealthSyncScheduler {
        override suspend fun schedule(entry: com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity) = Unit
        override suspend fun schedulePending(category: SettingsCategory) = 0
        override suspend fun onCategoryChanged(category: SettingsCategory, enabled: Boolean) = Unit
    }

    private class CancellingInputStream : InputStream() {
        override fun read(): Int = throw CancellationException("cancel")
    }

    private open class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }

    private class ThrowingTrackingInputStream(private val error: Throwable) : TrackingInputStream(byteArrayOf()) {
        override fun read(): Int = throw error
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw error
    }
}
