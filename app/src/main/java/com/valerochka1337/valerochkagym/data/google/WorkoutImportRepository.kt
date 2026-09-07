package com.valerochka1337.valerochkagym.data.google

sealed interface ImportResult {
  /** [skippedRows] — строк с id, которые не удалось разобрать (см. ParsedRows.skippedRows). */
  data class Success(
      /** Число восстановленных тренировок (сохраняет контракт старого UI и тестов). */
      val imported: Int,
      val skippedRows: Int = 0,
      val importedMeasurements: Int = 0,
      val importedRoutines: Int = 0,
      val importedExercises: Int = 0,
      val importedGyms: Int = 0,
      val importedRoutineGyms: Int = 0,
  ) : ImportResult

  data object NothingToImport : ImportResult

  data class Failure(val reason: String) : ImportResult
}

/** Разовый импорт всех app-managed листов из целевой Google-таблицы. */
interface WorkoutImportRepository {
  suspend fun importAll(): ImportResult
}
