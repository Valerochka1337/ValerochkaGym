package com.valerochka1337.valerochkagym.data.backup

import androidx.room.withTransaction
import androidx.work.WorkManager
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.seedExercises
import com.valerochka1337.valerochkagym.data.db.seedMissingExerciseMuscles
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Полная очистка данных тренировок; интерфейс — шов для тестов ViewModel. */
interface ClearDataUseCase {
  suspend operator fun invoke()
}

/**
 * Все таблицы Room стираются, встроенный каталог упражнений с картами мышц сеется заново,
 * отложенные выгрузки отменяются (им больше нечего выгружать).
 *
 * Настройки (Google-аккаунт, таблица, акцент и пр.) не трогаются: «очистить данные» — это про
 * историю тренировок, а не про сброс приложения.
 */
@Singleton
class ClearDataUseCaseImpl
@Inject
constructor(
    private val database: GymDatabase,
    private val workManager: WorkManager,
    private val sync: com.valerochka1337.valerochkagym.data.backend.BackendSync,
    private val scheduler: com.valerochka1337.valerochkagym.data.backend.BackendSyncScheduler,
) : ClearDataUseCase {

  override suspend operator fun invoke() =
      withContext(Dispatchers.IO) {
        sync.mutex.withLock {
          if (sync.hasActiveWorkout())
              throw com.valerochka1337.valerochkagym.data.backend.BackendException(
                  409,
                  "workout_active",
                  "Сначала завершите тренировку",
              )
          database.withTransaction {
            // Keep acknowledged versions and any in-flight operation: deletions must reach the
            // server, including when an earlier successful upload lost its response.
            val sql = database.openHelper.writableDatabase
            listOf(
                    "scheduled_workouts",
                    "workouts",
                    "routines",
                    "gyms",
                    "exercises",
                    "body_measurements",
                    "configuration_tombstones",
                    "muscle_load_upgrade_notice",
                )
                .forEach { sql.execSQL("DELETE FROM $it") }
            val exerciseDao = database.exerciseDao()
            exerciseDao.insertAll(seedExercises)
            seedMissingExerciseMuscles(exerciseDao, database.exerciseMuscleDao())
          }
        }
        scheduler.enqueue()
      }
}
