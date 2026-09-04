package com.valerochka1337.valerochkagym.data.backup

import androidx.work.WorkManager
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.seedExercises
import com.valerochka1337.valerochkagym.data.db.seedMissingExerciseMuscles
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
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
) : ClearDataUseCase {

  override suspend operator fun invoke() =
      withContext(Dispatchers.IO) {
        workManager.cancelAllWork()
        database.clearAllTables()
        val exerciseDao = database.exerciseDao()
        exerciseDao.insertAll(seedExercises)
        seedMissingExerciseMuscles(exerciseDao, database.exerciseMuscleDao())
      }
}
