package com.valerochka1337.valerochkagym.data.google

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.muscleRows
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.ParsedRoutine
import com.valerochka1337.valerochkagym.domain.ParsedWorkout
import com.valerochka1337.valerochkagym.domain.RoutineRowParser
import com.valerochka1337.valerochkagym.domain.WorkoutRowParser
import com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementRowParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/**
 * Результат разового импорта app-managed данных из Google Sheets.
 *
 * [Success] — импортировано [imported] новых тренировок и, при наличии, замеры/программы.
 * [NothingToImport] — импортировать нечего (нет листов/строк, или данные уже актуальны).
 * [Failure] — ошибка ([reason] показывается пользователю).
 */
sealed interface ImportResult {
    /** [skippedRows] — строк с id, которые не удалось разобрать (см. [ParsedRows.skippedRows]). */
    data class Success(
        /** Число восстановленных тренировок (сохраняет контракт старого UI и тестов). */
        val imported: Int,
        val skippedRows: Int = 0,
        val importedMeasurements: Int = 0,
        val importedRoutines: Int = 0,
    ) : ImportResult
    data object NothingToImport : ImportResult
    data class Failure(val reason: String) : ImportResult
}

/** Разовый импорт всех app-managed листов из целевой Google-таблицы. */
interface WorkoutImportRepository {
    suspend fun importAll(): ImportResult
}

/**
 * Читает `Workouts`, `Measurements` и `Routines` целевой таблицы и вставляет в БД только
 * отсутствующие/более новые записи. Упражнения матчатся по имени, отсутствующие создаются как
 * `isCustom = true` вместе с картой мышц. Импортированные тренировки и замеры получают
 * [UploadStatus.UPLOADED], поэтому не попадают в обратную выгрузку.
 */
class WorkoutImportRepositoryImpl @Inject constructor(
    private val api: SheetsApi,
    private val googleAuth: GoogleAuth,
    private val settingsRepository: SettingsRepository,
    private val database: GymDatabase,
    private val workoutDao: WorkoutDao,
    private val exerciseDao: ExerciseDao,
    private val exerciseMuscleDao: ExerciseMuscleDao,
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
            val sheetTitles = api.getSpreadsheet(bearer, spreadsheetId).sheets
                .map { it.properties.title }
                .toSet()
            val workouts = if (WORKOUTS_SHEET in sheetTitles) {
                WorkoutRowParser.parse(api.getValues(bearer, spreadsheetId, WORKOUTS_RANGE).values.orEmpty())
            } else {
                com.valerochka1337.valerochkagym.domain.ParsedRows(emptyList(), 0)
            }
            val measurements = if (MEASUREMENTS_SHEET in sheetTitles) {
                BodyMeasurementRowParser.parse(
                    api.getValues(bearer, spreadsheetId, MEASUREMENTS_RANGE).values.orEmpty(),
                )
            } else {
                com.valerochka1337.valerochkagym.domain.measurements.ParsedMeasurements(emptyList(), 0)
            }
            val routines = if (ROUTINES_SHEET in sheetTitles) {
                RoutineRowParser.parse(api.getValues(bearer, spreadsheetId, ROUTINES_RANGE).values.orEmpty())
            } else {
                com.valerochka1337.valerochkagym.domain.ParsedRoutineRows(emptyList(), 0)
            }
            val skippedRows = workouts.skippedRows + measurements.skippedRows + routines.skippedRows
            if (
                workouts.workouts.isEmpty() &&
                measurements.measurements.isEmpty() &&
                routines.routines.isEmpty()
            ) {
                return if (skippedRows > 0) {
                    ImportResult.Failure("Не удалось разобрать строки таблицы: $skippedRows")
                } else {
                    ImportResult.NothingToImport
                }
            }

            val existingWorkoutIds = workoutDao.getExistingWorkoutIds().toSet()
            val freshWorkouts = workouts.workouts.filterNot { it.id in existingWorkoutIds }
            val existingMeasurements = database.bodyMeasurementDao().observeAll().first().mapTo(mutableSetOf()) { it.id }
            val freshMeasurements = measurements.measurements.filterNot { it.id in existingMeasurements }
            val existingRoutines = database.routineDao().observeRoutinesFull().first()
                .associateBy { it.routine.syncId }

            // Один снимок каталога → матчинг по имени в памяти (без запроса на каждое упражнение).
            // Новые имена добавляются в карту, поэтому одно и то же custom-упражнение создаётся
            // ровно раз даже если встретилось в программе и истории тренировок.
            val byName = exerciseDao.getAllOnce().associateTo(mutableMapOf()) { it.name.lowercase() to it.id }
            var importedRoutines = 0
            database.withTransaction {
                routines.routines.forEach { routine ->
                    if (applyRoutine(routine, existingRoutines[routine.syncId], byName)) {
                        importedRoutines++
                    }
                }
                freshWorkouts.forEach { insertWorkout(it, byName) }
                val bodyMeasurementDao = database.bodyMeasurementDao()
                freshMeasurements.forEach { bodyMeasurementDao.insert(it) }
            }
            val importedWorkouts = freshWorkouts.size
            val importedMeasurements = freshMeasurements.size
            if (importedWorkouts + importedMeasurements + importedRoutines == 0 && skippedRows == 0) {
                ImportResult.NothingToImport
            } else {
                ImportResult.Success(
                    imported = importedWorkouts,
                    skippedRows = skippedRows,
                    importedMeasurements = importedMeasurements,
                    importedRoutines = importedRoutines,
                )
            }
        } catch (e: HttpException) {
            ImportResult.Failure(HttpErrorClassifier.message(e.code()))
        } catch (e: IOException) {
            ImportResult.Failure(GoogleErrorMessages.NO_NETWORK)
        } catch (e: CancellationException) {
            // Отмена корутины — не ошибка импорта: пробрасываем, иначе она осядет генерик-сообщением.
            throw e
        } catch (e: Exception) {
            ImportResult.Failure("Не удалось импортировать данные из таблицы")
        }
    }

    private suspend fun insertWorkout(
        parsed: ParsedWorkout,
        byName: MutableMap<String, Long>,
    ) {
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
            val key = exercise.name.lowercase()
            val exerciseId = byName[key] ?: createExercise(
                name = exercise.name,
                muscleGroup = exercise.muscleGroup,
                type = exercise.type,
            ).also { byName[key] = it }
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

    /** Восстанавливает более новую программу либо применяет tombstone удаления. */
    private suspend fun applyRoutine(
        parsed: ParsedRoutine,
        local: RoutineWithExercises?,
        byName: MutableMap<String, Long>,
    ): Boolean {
        if (local != null && parsed.updatedAt <= local.routine.updatedAt) return false
        val routineDao = database.routineDao()
        if (parsed.isDeleted) {
            if (local != null) routineDao.deleteRoutine(local.routine.id)
            return local != null
        }
        val entity = RoutineEntity(
            id = local?.routine?.id ?: 0,
            syncId = parsed.syncId,
            updatedAt = parsed.updatedAt,
            name = parsed.name,
            note = parsed.note,
        )
        val insertedId = routineDao.upsertRoutine(entity)
        val routineId = local?.routine?.id ?: insertedId
        val exercises = parsed.exercises.map { exercise ->
            val key = exercise.name.lowercase()
            val exerciseId = byName[key] ?: createExercise(
                name = exercise.name,
                muscleGroup = exercise.muscleGroup,
                type = exercise.type,
            ).also { byName[key] = it }
            RoutineExerciseEntity(
                routineId = routineId,
                exerciseId = exerciseId,
                position = exercise.position,
                restSeconds = exercise.restSeconds,
                plannedSets = exercise.plannedSets,
            )
        }
        routineDao.replaceRoutineExercises(routineId, exercises)
        return true
    }

    /**
     * Создаёт отсутствующее упражнение вместе с картой мышц. В Sheets есть только группа, поэтому
     * для знакомого имени карта точная, а для нового берётся типичная для группы (`muscleRows`).
     */
    private suspend fun createExercise(
        name: String,
        muscleGroup: com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup,
        type: com.valerochka1337.valerochkagym.data.db.entity.ExerciseType,
    ): Long {
        val entity = ExerciseEntity(
            name = name,
            muscleGroup = muscleGroup,
            type = type,
            isCustom = true,
        )
        val id = exerciseDao.insert(entity)
        exerciseMuscleDao.upsertAll(entity.copy(id = id).muscleRows())
        return id
    }

    private companion object {
        const val WORKOUTS_SHEET = "Workouts"
        const val MEASUREMENTS_SHEET = "Measurements"
        const val ROUTINES_SHEET = "Routines"
        /** Весь лист «Workouts» (14 колонок A–N, см. WorkoutRowMapper.HEADER_ROW). */
        const val WORKOUTS_RANGE = "Workouts!A:N"
        const val MEASUREMENTS_RANGE = "Measurements!A:AP"
        const val ROUTINES_RANGE = "Routines!A:K"
    }
}
