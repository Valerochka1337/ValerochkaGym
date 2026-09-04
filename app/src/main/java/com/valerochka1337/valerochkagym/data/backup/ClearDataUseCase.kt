package com.valerochka1337.valerochkagym.data.backup

import android.content.Context
import androidx.work.WorkManager
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.seedExercises
import com.valerochka1337.valerochkagym.data.db.seedMissingExerciseMuscles
import com.valerochka1337.valerochkagym.data.health.PrivateOriginalsLifecycleGate
import com.valerochka1337.valerochkagym.data.measurements.PendingInBodyCaptureRegistry
import com.valerochka1337.valerochkagym.di.ComputeDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Полная очистка данных тренировок; интерфейс — шов для тестов ViewModel. */
interface ClearDataUseCase {
    suspend operator fun invoke()
}

/** The database was cleared but a private path could not be removed; never report this as success. */
class PrivateOriginalsClearFailure internal constructor(paths: List<File>) : IllegalStateException(
    "Не удалось удалить локальные оригиналы: ${paths.joinToString { it.name }}",
)

/**
 * Все таблицы Room стираются, встроенный каталог упражнений с картами мышц сеется заново,
 * отложенные выгрузки отменяются (им больше нечего выгружать).
 *
 * Настройки (Google-аккаунт, таблица, акцент и пр.) не трогаются: «очистить данные» — это про
 * историю тренировок, а не про сброс приложения.
 */
@Singleton
class ClearDataUseCaseImpl @Inject constructor(
    private val database: GymDatabase,
    private val workManager: WorkManager,
    @param:ApplicationContext private val context: Context,
    private val lifecycleGate: PrivateOriginalsLifecycleGate,
    private val captureRegistry: PendingInBodyCaptureRegistry,
    @param:ComputeDispatcher private val dispatcher: CoroutineDispatcher,
) : ClearDataUseCase {

    override suspend operator fun invoke() = withContext(dispatcher + NonCancellable) {
        lifecycleGate.withLock {
            // Never let cancellation leave the clear half-complete after Room is erased.
            workManager.cancelAllWork()
            database.clearAllTables()
            val failures = mutableListOf<File>()
            failures += deleteTree(File(context.noBackupFilesDir, "health_documents"))
            failures += deleteTree(File(context.noBackupFilesDir, "measurement_documents"))
            failures += captureRegistry.clearAllWhileLocked(File(context.cacheDir, "inbody_imports"))
            val exerciseDao = database.exerciseDao()
            exerciseDao.insertAll(seedExercises)
            seedMissingExerciseMuscles(exerciseDao, database.exerciseMuscleDao())
            if (failures.isNotEmpty()) throw PrivateOriginalsClearFailure(failures)
        }
    }

    private fun deleteTree(root: File): List<File> {
        if (!root.exists()) return emptyList()
        val failures = mutableListOf<File>()
        root.listFiles()?.forEach { child -> failures += deleteTree(child) }
        if (root.exists() && !root.delete()) failures += root
        return failures
    }
}
