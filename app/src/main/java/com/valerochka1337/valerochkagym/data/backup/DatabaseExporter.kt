package com.valerochka1337.valerochkagym.data.backup

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SimpleSQLiteQuery
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Итог экспорта: удача или причина, пригодная для снэкбара. */
sealed interface ExportResult {
    data object Success : ExportResult
    data class Failure(val reason: String) : ExportResult
}

/**
 * Экспорт локальной базы в выбранный пользователем документ. Интерфейс — шов для тестов
 * ViewModel (как [com.valerochka1337.valerochkagym.worker.UploadScheduler]).
 */
interface DatabaseExporter {

    suspend fun export(target: Uri): ExportResult

    companion object {
        /** Имя файла базы — то же, что в DataModule.provideDatabase. */
        const val DATABASE_NAME = "gym.db"

        /** Имя предлагаемого документа: дата подставляется экраном. */
        fun suggestedFileName(date: String): String = "valerochka-gym-backup-$date.db"
    }
}

/**
 * Копия базы через SAF `ACTION_CREATE_DOCUMENT`.
 *
 * Перед копированием WAL сбрасывается в основной файл (`wal_checkpoint(TRUNCATE)`) — иначе
 * копия `gym.db` без `-wal` потеряла бы последние записи. Копия — обычный SQLite-файл, который
 * открывается любым инструментом и восстанавливается заменой файла базы.
 */
@Singleton
class DatabaseExporterImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: GymDatabase,
) : DatabaseExporter {

    override suspend fun export(target: Uri): ExportResult = withContext(Dispatchers.IO) {
        try {
            database.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)")).use { it.moveToFirst() }
            val source = context.getDatabasePath(DATABASE_NAME)
            if (!source.exists()) return@withContext ExportResult.Failure("База данных не найдена")
            context.contentResolver.openOutputStream(target, "wt")
                ?.use { output -> source.inputStream().use { input -> input.copyTo(output) } }
                ?: return@withContext ExportResult.Failure("Не удалось открыть файл для записи")
            ExportResult.Success
        } catch (e: FileNotFoundException) {
            ExportResult.Failure("Не удалось открыть файл для записи")
        } catch (e: IOException) {
            ExportResult.Failure("Не удалось записать файл")
        }
    }

    private companion object {
        const val DATABASE_NAME = DatabaseExporter.DATABASE_NAME
    }
}
