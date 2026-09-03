package com.valerochka1337.valerochkagym.data.measurements

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.MeasurementDocumentDao
import com.valerochka1337.valerochkagym.data.db.entity.MeasurementDocumentEntity
import com.valerochka1337.valerochkagym.di.ComputeDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class MeasurementDocumentInput(
    val measurementId: String,
    val displayName: String,
    val mimeType: String,
)

sealed interface MeasurementDocumentStoreResult {
    data class Ready(val documentId: String, val sha256: String, val byteSize: Long) : MeasurementDocumentStoreResult
    data class Failure(val message: String) : MeasurementDocumentStoreResult
}

/** Testable boundary immediately after rename and before READY metadata is committed. */
interface MeasurementDocumentLifecycleHook {
    suspend fun afterRenameBeforeReady()
}

@Singleton
class NoOpMeasurementDocumentLifecycleHook @Inject constructor() : MeasurementDocumentLifecycleHook {
    override suspend fun afterRenameBeforeReady() = Unit
}

/**
 * Process-wide serialization for state changes that share private original bytes.
 *
 * A SHA-256 file can be referenced by several measurements, so a separate repository instance
 * must never race another instance's finalization, deletion or recovery sweep.
 */
@Singleton
class MeasurementDocumentLifecycleGate @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

/**
 * Private originals for InBody photos. Files are never part of a snapshot or Sheets payload.
 * [store] owns and always closes its [InputStream], including validation, read and cancellation
 * exits. Callers that already own an URI must use [storeUri] to avoid a second close.
 */
interface MeasurementDocumentRepository {
    suspend fun store(input: MeasurementDocumentInput, source: InputStream): MeasurementDocumentStoreResult
    suspend fun storeUri(input: MeasurementDocumentInput, uri: Uri): MeasurementDocumentStoreResult
    fun observeReady(measurementId: String): Flow<List<MeasurementDocumentEntity>>
    suspend fun readyForMeasurement(measurementId: String): List<MeasurementDocumentEntity>
    suspend fun resolveReadyFile(documentId: String): File?
    suspend fun delete(documentId: String): Boolean
    suspend fun deleteAll(measurementId: String): Int
    suspend fun cleanupUnreferenced(hashes: Set<String>)
    suspend fun recoverInterruptedCopies()
    /** Cold-start-only sweep of unclaimed camera imports; active captures are represented by a VM token. */
    suspend fun recoverCameraImports() = Unit
}

@Singleton
class LocalMeasurementDocumentRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: GymDatabase,
    private val documentDao: MeasurementDocumentDao,
    @param:ComputeDispatcher private val dispatcher: CoroutineDispatcher,
    private val lifecycleGate: MeasurementDocumentLifecycleGate,
    private val captureRegistry: PendingInBodyCaptureRegistry,
    private val lifecycleHook: MeasurementDocumentLifecycleHook = NoOpMeasurementDocumentLifecycleHook(),
) : MeasurementDocumentRepository {
    private val directory get() = File(context.noBackupFilesDir, DIRECTORY_NAME)

    override suspend fun store(input: MeasurementDocumentInput, source: InputStream): MeasurementDocumentStoreResult =
        withContext(dispatcher) {
            if (!input.mimeType.lowercase().startsWith("image/")) {
                runCatching { source.close() }
                return@withContext MeasurementDocumentStoreResult.Failure("Поддерживаются только изображения InBody")
            }
            val bytes = try {
                source.use { it.readBounded(MAX_IMAGE_BYTES) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext MeasurementDocumentStoreResult.Failure("Не удалось прочитать оригинал")
            }
            val digest = sha256(bytes)
            val id = UUID.randomUUID().toString()
            val pending = MeasurementDocumentEntity(
                id = id,
                measurementId = input.measurementId,
                sha256 = digest,
                state = STATE_PENDING,
                displayName = input.displayName.sanitizeName(),
                mimeType = input.mimeType.sanitizeMimeType(),
                byteSize = bytes.size.toLong(),
                createdAt = System.currentTimeMillis(),
            )
            val temporary = File(directory, "$id.$digest.tmp")
            lifecycleGate.withLock {
                if (database.bodyMeasurementDao().getById(input.measurementId) == null) {
                    return@withLock MeasurementDocumentStoreResult.Failure("Замер не найден")
                }
                try {
                    database.withTransaction { documentDao.upsert(pending) }
                    directory.mkdirs()
                    temporary.outputStream().use { it.write(bytes) }
                    coroutineContext.ensureActive()
                    if (sha256(temporary.readBytes()) != digest) {
                        return@withLock failStoreLocked(id, temporary, digest, "Не удалось проверить оригинал")
                    }
                    val final = File(directory, digest)
                    if (final.isFile) {
                        if (sha256(final.readBytes()) != digest) {
                            return@withLock failStoreLocked(id, temporary, digest, "Не удалось сохранить оригинал")
                        }
                        temporary.delete()
                    } else if (!temporary.renameTo(final)) {
                        return@withLock failStoreLocked(id, temporary, digest, "Не удалось сохранить оригинал")
                    }
                    lifecycleHook.afterRenameBeforeReady()
                    // A delete may have committed while the source was read. Never publish READY
                    // metadata for a missing parent; the catch also covers a racing FK failure.
                    if (database.bodyMeasurementDao().getById(input.measurementId) == null) {
                        return@withLock failStoreLocked(id, temporary, digest, "Замер был удалён")
                    }
                    database.withTransaction { documentDao.upsert(pending.copy(state = STATE_READY)) }
                    MeasurementDocumentStoreResult.Ready(id, digest, bytes.size.toLong())
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { cleanupFailedStoreLocked(id, temporary, digest) }
                    throw e
                } catch (_: Exception) {
                    failStoreLocked(id, temporary, digest, "Не удалось сохранить оригинал")
                }
            }
        }

    override suspend fun storeUri(input: MeasurementDocumentInput, uri: Uri): MeasurementDocumentStoreResult {
        val stream = context.contentResolver.openInputStream(uri)
            ?: return MeasurementDocumentStoreResult.Failure("Не удалось открыть оригинал")
        return store(input, stream)
    }

    override fun observeReady(measurementId: String): Flow<List<MeasurementDocumentEntity>> =
        documentDao.observeReadyForMeasurement(measurementId)

    override suspend fun readyForMeasurement(measurementId: String): List<MeasurementDocumentEntity> =
        withContext(dispatcher) { documentDao.forMeasurement(measurementId).filter { it.state == STATE_READY && readyFile(it) != null } }

    override suspend fun resolveReadyFile(documentId: String): File? = withContext(dispatcher) {
        documentDao.document(documentId)?.let(::readyFile)
    }

    override suspend fun delete(documentId: String): Boolean = withContext(dispatcher) {
        lifecycleGate.withLock {
            val document = documentDao.document(documentId) ?: return@withLock false
            database.withTransaction { documentDao.delete(documentId) }
            cleanupUnreferencedLocked(setOf(document.sha256))
            true
        }
    }

    override suspend fun deleteAll(measurementId: String): Int = withContext(dispatcher) {
        lifecycleGate.withLock {
            val documents = documentDao.forMeasurement(measurementId).filter { it.state == STATE_READY }
            if (documents.isEmpty()) return@withLock 0
            database.withTransaction { documents.forEach { documentDao.delete(it.id) } }
            cleanupUnreferencedLocked(documents.mapTo(linkedSetOf()) { it.sha256 })
            documents.size
        }
    }

    /** Called only after the measurement transaction committed and FK metadata has disappeared. */
    override suspend fun cleanupUnreferenced(hashes: Set<String>) = withContext(dispatcher) {
        lifecycleGate.withLock { cleanupUnreferencedLocked(hashes) }
    }

    override suspend fun recoverInterruptedCopies() = withContext(dispatcher) {
        lifecycleGate.withLock {
        directory.mkdirs()
        documentDao.byState(STATE_PENDING).forEach { pending ->
            File(directory, "${pending.id}.${pending.sha256}.tmp").delete()
            documentDao.delete(pending.id)
        }
        directory.listFiles { file -> file.name.endsWith(".tmp") }?.forEach(File::delete)
        val ready = documentDao.byState(STATE_READY)
        val validHashes = ready.filter { readyFile(it) != null }.mapTo(mutableSetOf()) { it.sha256 }
        ready.filter { it.sha256 !in validHashes }.forEach { documentDao.delete(it.id) }
        directory.listFiles { file -> file.isFile && !file.name.endsWith(".tmp") }
            ?.filter { it.name !in validHashes }
            ?.forEach(File::delete)
            ?: Unit
        }
    }

    override suspend fun recoverCameraImports() = withContext(dispatcher) {
        lifecycleGate.withLock {
            captureRegistry.recoverCameraImports(File(context.cacheDir, CAMERA_IMPORT_DIRECTORY))
        }
    }

    private suspend fun failStoreLocked(
        id: String,
        temporary: File,
        digest: String,
        message: String,
    ): MeasurementDocumentStoreResult.Failure {
        cleanupFailedStoreLocked(id, temporary, digest)
        return MeasurementDocumentStoreResult.Failure(message)
    }

    private suspend fun cleanupFailedStoreLocked(id: String, temporary: File, digest: String) {
        temporary.delete()
        database.withTransaction { documentDao.delete(id) }
        cleanupUnreferencedLocked(setOf(digest))
    }

    private suspend fun cleanupUnreferencedLocked(hashes: Set<String>) {
        hashes.forEach { hash ->
            if (documentDao.readyReferenceCount(hash) == 0) File(directory, hash).delete()
        }
    }

    private fun readyFile(document: MeasurementDocumentEntity): File? {
        if (document.state != STATE_READY) return null
        val file = File(directory, document.sha256)
        return file.takeIf { it.isFile && it.length() == document.byteSize && sha256(it.readBytes()) == document.sha256 }
    }

    private suspend fun InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            coroutineContext.ensureActive()
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > limit) throw IllegalArgumentException("image too large")
            output.write(buffer, 0, count)
        }
    }

    private fun String.sanitizeName(): String = replace(Regex("[\\p{Cntrl}/\\\\]"), "_")
        .trim().take(128).ifBlank { "InBody" }

    private fun String.sanitizeMimeType(): String = lowercase().trim()
        .takeIf { it.matches(Regex("image/[a-z0-9.+-]+")) } ?: "image/*"

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val DIRECTORY_NAME = "measurement_documents"
        const val CAMERA_IMPORT_DIRECTORY = "inbody_imports"
        const val STATE_PENDING = "PENDING"
        const val STATE_READY = "READY"
        const val MAX_IMAGE_BYTES = 6 * 1024 * 1024
    }
}

object NoOpMeasurementDocumentRepository : MeasurementDocumentRepository {
    override suspend fun store(input: MeasurementDocumentInput, source: InputStream) =
        MeasurementDocumentStoreResult.Failure("Хранилище оригиналов недоступно")
    override suspend fun storeUri(input: MeasurementDocumentInput, uri: Uri) =
        MeasurementDocumentStoreResult.Failure("Хранилище оригиналов недоступно")
    override fun observeReady(measurementId: String) = kotlinx.coroutines.flow.emptyFlow<List<MeasurementDocumentEntity>>()
    override suspend fun readyForMeasurement(measurementId: String) = emptyList<MeasurementDocumentEntity>()
    override suspend fun resolveReadyFile(documentId: String): File? = null
    override suspend fun delete(documentId: String) = false
    override suspend fun deleteAll(measurementId: String) = 0
    override suspend fun cleanupUnreferenced(hashes: Set<String>) = Unit
    override suspend fun recoverInterruptedCopies() = Unit
    override suspend fun recoverCameraImports() = Unit
}
