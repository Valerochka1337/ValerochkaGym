package com.valerochka1337.valerochkagym.data.google

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.ParsedWorkout
import com.valerochka1337.valerochkagym.domain.WorkoutRowParser
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/**
 * Результат разового импорта истории из листа `Workouts`.
 *
 * [Success] — импортировано [imported] новых тренировок. [NothingToImport] — импортировать
 * нечего (нет листа/строк, или все тренировки уже есть локально). [Failure] — ошибка
 * ([reason] показывается пользователю).
 */
sealed interface ImportResult {
    data class Success(val imported: Int) : ImportResult
    data object NothingToImport : ImportResult
    data class Failure(val reason: String) : ImportResult
}

/** Разовый импорт истории тренировок из целевой Google-таблицы (обратный к выгрузке). */
interface WorkoutImportRepository {
    suspend fun importAll(): ImportResult
}

/**
 * Читает лист `Workouts` целевой таблицы и вставляет в БД только те тренировки, которых ещё
 * нет локально (дедуп по `workout_id`). Владеет всем флоу (настройки → токен → чтение →
 * разбор → вставка), как [SheetsRepositoryImpl] владеет `uploadWorkout`. Упражнения матчатся
 * по имени, отсутствующие создаются как `isCustom = true`. Импортированные тренировки
 * помечаются [UploadStatus.UPLOADED] — они уже в таблице и не должны выгружаться обратно.
 */
class WorkoutImportRepositoryImpl @Inject constructor(
    private val api: SheetsApi,
    private val googleAuth: GoogleAuth,
    private val settingsRepository: SettingsRepository,
    private val database: GymDatabase,
    private val workoutDao: WorkoutDao,
    private val exerciseDao: ExerciseDao,
) : WorkoutImportRepository {

    override suspend fun importAll(): ImportResult {
        val spreadsheetId = settingsRepository.settings.first().spreadsheetId
            ?: return ImportResult.Failure("Укажите таблицу в настройках")

        val token = when (val result = googleAuth.getAccessToken()) {
            is TokenResult.Success -> result.token
            TokenResult.NeedsConsent -> return ImportResult.Failure("Настройте доступ к Google в настройках")
            is TokenResult.Failed -> return ImportResult.Failure(GoogleErrorMessages.NO_CONNECTION)
        }

        val bearer = "Bearer $token"
        return try {
            if (!workoutsSheetExists(bearer, spreadsheetId)) return ImportResult.NothingToImport
            val values = api.getValues(bearer, spreadsheetId, WORKOUTS_RANGE).values
                ?: return ImportResult.NothingToImport
            val parsed = WorkoutRowParser.parse(values)
            if (parsed.isEmpty()) return ImportResult.NothingToImport

            val existing = workoutDao.getExistingWorkoutIds().toSet()
            val fresh = parsed.filter { it.id !in existing }
            if (fresh.isEmpty()) return ImportResult.NothingToImport

            fresh.forEach { insertWorkout(it) }
            ImportResult.Success(fresh.size)
        } catch (e: HttpException) {
            ImportResult.Failure(classifyHttp(e.code()))
        } catch (e: IOException) {
            ImportResult.Failure(GoogleErrorMessages.NO_NETWORK)
        }
    }

    private suspend fun workoutsSheetExists(bearer: String, spreadsheetId: String): Boolean =
        api.getSpreadsheet(bearer, spreadsheetId).sheets.any { it.properties.title == WORKOUTS_SHEET }

    private suspend fun insertWorkout(parsed: ParsedWorkout) = database.withTransaction {
        workoutDao.insertWorkout(
            WorkoutEntity(
                id = parsed.id,
                routineId = null,
                name = parsed.name,
                startedAt = parsed.startedAt,
                finishedAt = parsed.finishedAt,
                uploadStatus = UploadStatus.UPLOADED,
                uploadError = null,
            ),
        )
        for (exercise in parsed.exercises) {
            val exerciseId = exerciseDao.findByName(exercise.name)?.id
                ?: exerciseDao.insert(
                    ExerciseEntity(
                        name = exercise.name,
                        muscleGroup = exercise.muscleGroup,
                        type = exercise.type,
                        isCustom = true,
                    ),
                )
            val workoutExerciseId = workoutDao.insertWorkoutExercise(
                WorkoutExerciseEntity(
                    workoutId = parsed.id,
                    exerciseId = exerciseId,
                    position = exercise.position,
                ),
            )
            if (exercise.sets.isNotEmpty()) {
                workoutDao.insertSets(
                    exercise.sets.map { set ->
                        WorkoutSetEntity(
                            workoutExerciseId = workoutExerciseId,
                            setIndex = set.setIndex,
                            weightKg = set.weightKg,
                            reps = set.reps,
                            durationSec = set.durationSec,
                            speedKmh = set.speedKmh,
                            inclinePct = set.inclinePct,
                            isCompleted = true,
                            completedAt = set.completedAt,
                        )
                    },
                )
            }
        }
    }

    /** Те же формулировки, что при выгрузке (см. `SheetsRepositoryImpl.classifyHttp`). */
    private fun classifyHttp(code: Int): String = when (code) {
        401, 403 -> "Нет доступа к таблице — проверьте вход и права"
        404 -> "Таблица не найдена — проверьте ссылку"
        429 -> "Слишком много запросов (HTTP 429)"
        in 500..599 -> "Ошибка сервера (HTTP $code)"
        else -> "Ошибка запроса (HTTP $code)"
    }

    private companion object {
        const val WORKOUTS_SHEET = "Workouts"
        /** Весь лист «Workouts» (14 колонок A–N, см. WorkoutRowMapper.HEADER_ROW). */
        const val WORKOUTS_RANGE = "Workouts!A:N"
    }
}
