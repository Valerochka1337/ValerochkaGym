package com.valerochka1337.valerochkagym.data.health

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.HealthDao
import com.valerochka1337.valerochkagym.data.db.entity.HealthDocumentEntity
import com.valerochka1337.valerochkagym.di.ComputeDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class HealthDocumentInput(
    val reportSyncId: String,
    val displayName: String,
    val mimeType: String,
    val sourcePage: Int? = null,
)

sealed interface HealthDocumentStoreResult {
    data class Ready(val documentId: String, val sha256: String, val byteSize: Long) : HealthDocumentStoreResult
    data class Failure(val message: String) : HealthDocumentStoreResult
}

/** T-005 owns private-file IO; UI and sync workers never receive a document path. */
interface HealthDocumentRepository {
    suspend fun store(input: HealthDocumentInput, source: InputStream): HealthDocumentStoreResult
    suspend fun storeUri(input: HealthDocumentInput, uri: Uri): HealthDocumentStoreResult
    suspend fun hasReadyDocument(documentId: String): Boolean
    suspend fun delete(documentId: String): Boolean
    suspend fun recoverInterruptedCopies()
}

@Singleton
class LocalHealthDocumentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: GymDatabase,
    private val healthDao: HealthDao,
    @param:ComputeDispatcher private val dispatcher: CoroutineDispatcher,
) : HealthDocumentRepository {
    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)

    override suspend fun store(input: HealthDocumentInput, source: InputStream): HealthDocumentStoreResult =
        withContext(dispatcher) {
            val bytes = try {
                source.readBounded(MAX_SOURCE_BYTES)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext HealthDocumentStoreResult.Failure("Не удалось прочитать документ")
            }
            val digest = sha256(bytes)
            val documentId = UUID.randomUUID().toString()
            val pending = HealthDocumentEntity(
                id = documentId,
                reportSyncId = input.reportSyncId,
                sha256 = digest,
                state = STATE_PENDING,
                displayName = input.displayName,
                mimeType = input.mimeType,
                byteSize = bytes.size.toLong(),
                sourcePage = input.sourcePage,
                createdAt = System.currentTimeMillis(),
            )
            try {
                database.withTransaction { healthDao.upsertDocument(pending) }
                directory.mkdirs()
                val temporary = File(directory, "$digest.tmp")
                val final = File(directory, digest)
                temporary.outputStream().use { output -> output.write(bytes) }
                coroutineContext.ensureActive()
                if (sha256(temporary.readBytes()) != digest) {
                    temporary.delete()
                    database.withTransaction { healthDao.deleteDocument(documentId) }
                    return@withContext HealthDocumentStoreResult.Failure("Не удалось проверить документ")
                }
                if (!temporary.renameTo(final) && !final.isFile) {
                    temporary.delete()
                    database.withTransaction { healthDao.deleteDocument(documentId) }
                    return@withContext HealthDocumentStoreResult.Failure("Не удалось сохранить документ")
                }
                database.withTransaction { healthDao.upsertDocument(pending.copy(state = STATE_READY)) }
                HealthDocumentStoreResult.Ready(documentId, digest, bytes.size.toLong())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                File(directory, "$digest.tmp").delete()
                database.withTransaction { healthDao.deleteDocument(documentId) }
                HealthDocumentStoreResult.Failure("Не удалось сохранить документ")
            }
        }

    override suspend fun storeUri(input: HealthDocumentInput, uri: Uri): HealthDocumentStoreResult {
        val stream = context.contentResolver.openInputStream(uri)
            ?: return HealthDocumentStoreResult.Failure("Не удалось открыть оригинал")
        return stream.use { store(input, it) }
    }

    override suspend fun hasReadyDocument(documentId: String): Boolean = withContext(dispatcher) {
        val document = healthDao.document(documentId) ?: return@withContext false
        document.state == STATE_READY && File(directory, document.sha256).isFile
    }

    override suspend fun delete(documentId: String): Boolean = withContext(dispatcher) {
        val document = healthDao.document(documentId) ?: return@withContext false
        database.withTransaction { healthDao.deleteDocument(documentId) }
        // Same digest may be deliberately referenced by another report; retain its shared private file.
        if (healthDao.documentsInState(STATE_READY).none { it.sha256 == document.sha256 }) {
            File(directory, document.sha256).delete()
        }
        true
    }

    override suspend fun recoverInterruptedCopies() = withContext(dispatcher) {
        directory.mkdirs()
        healthDao.documentsInState(STATE_PENDING).forEach { pending ->
            File(directory, "${pending.sha256}.tmp").delete()
            healthDao.deleteDocument(pending.id)
        }
        directory.listFiles { file -> file.name.endsWith(".tmp") }?.forEach(File::delete) ?: Unit
    }

    private suspend fun InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            coroutineContext.ensureActive()
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > limit) throw IllegalArgumentException("Document is too large")
            output.write(buffer, 0, count)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DIRECTORY_NAME = "health_documents"
        const val STATE_PENDING = "PENDING"
        const val STATE_READY = "READY"
        const val MAX_SOURCE_BYTES = 20 * 1024 * 1024
    }
}

object NoOpHealthDocumentRepository : HealthDocumentRepository {
    override suspend fun store(input: HealthDocumentInput, source: InputStream) =
        HealthDocumentStoreResult.Failure("Хранилище документов недоступно")
    override suspend fun hasReadyDocument(documentId: String): Boolean = false
    override suspend fun storeUri(input: HealthDocumentInput, uri: Uri) = HealthDocumentStoreResult.Failure("Хранилище документов недоступно")
    override suspend fun delete(documentId: String): Boolean = false
    override suspend fun recoverInterruptedCopies() = Unit
}
