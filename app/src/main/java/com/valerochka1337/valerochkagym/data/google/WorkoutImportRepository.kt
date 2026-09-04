package com.valerochka1337.valerochkagym.data.google

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.CanonicalExerciseRegistry
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.GymDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ConfigurationTombstoneKind
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
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
import com.valerochka1337.valerochkagym.domain.ExerciseSheetRecord
import com.valerochka1337.valerochkagym.domain.ExerciseSheetRowMapper
import com.valerochka1337.valerochkagym.domain.ExerciseSheetRowParser
import com.valerochka1337.valerochkagym.domain.GymSheetRecord
import com.valerochka1337.valerochkagym.domain.GymSheetRowMapper
import com.valerochka1337.valerochkagym.domain.GymSheetRowParser
import com.valerochka1337.valerochkagym.domain.ParsedExerciseSheetRows
import com.valerochka1337.valerochkagym.domain.ParsedGymSheetRows
import com.valerochka1337.valerochkagym.domain.ParsedRoutineGymsSheetRows
import com.valerochka1337.valerochkagym.domain.RoutineGymsSheetRecord
import com.valerochka1337.valerochkagym.domain.RoutineGymsSheetRowMapper
import com.valerochka1337.valerochkagym.domain.RoutineGymsSheetRowParser
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
    private val gymDao: GymDao = database.gymDao(),
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
            workouts.fatalError?.let { return ImportResult.Failure(it) }
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
            val exercises = if (ExerciseSheetRowMapper.SHEET_NAME in sheetTitles) {
                ExerciseSheetRowParser.parse(
                    api.getValues(bearer, spreadsheetId, ExerciseSheetRowMapper.RANGE).values.orEmpty(),
                )
            } else {
                ParsedExerciseSheetRows(emptyList(), 0)
            }
            val gyms = if (GymSheetRowMapper.SHEET_NAME in sheetTitles) {
                GymSheetRowParser.parse(
                    api.getValues(bearer, spreadsheetId, GymSheetRowMapper.RANGE).values.orEmpty(),
                )
            } else {
                ParsedGymSheetRows(emptyList(), 0)
            }
            val routineGyms = if (RoutineGymsSheetRowMapper.SHEET_NAME in sheetTitles) {
                RoutineGymsSheetRowParser.parse(
                    api.getValues(bearer, spreadsheetId, RoutineGymsSheetRowMapper.RANGE).values.orEmpty(),
                )
            } else {
                ParsedRoutineGymsSheetRows(emptyList(), 0)
            }
            var skippedRows = workouts.skippedRows + measurements.skippedRows + routines.skippedRows +
                exercises.skippedRows + gyms.skippedRows + routineGyms.skippedRows
            if (
                workouts.workouts.isEmpty() &&
                measurements.measurements.isEmpty() &&
                routines.routines.isEmpty() &&
                exercises.records.isEmpty() &&
                gyms.records.isEmpty() &&
                routineGyms.records.isEmpty()
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
            var byName = exerciseDao.getAllOnce().associateTo(mutableMapOf()) { it.name.lowercase() to it.id }
            var bySyncId = exerciseDao.getAllOnce().associateTo(mutableMapOf()) { it.syncId to it.id }
            var importedRoutines = 0
            var importedExercises = 0
            var importedGyms = 0
            var importedRoutineGyms = 0
            database.withTransaction {
                exercises.records.forEach { record ->
                    if (applyExercise(record)) importedExercises++
                }
                byName = exerciseDao.getAllOnce()
                    .associateTo(mutableMapOf()) { it.name.lowercase() to it.id }
                bySyncId = exerciseDao.getAllOnce()
                    .associateTo(mutableMapOf()) { it.syncId to it.id }
                gyms.records.filterIsInstance<GymSheetRecord.Snapshot>().forEach { record ->
                    when (applyGym(record)) {
                        ApplyConfigurationResult.Applied -> importedGyms++
                        ApplyConfigurationResult.InvalidReference -> skippedRows++
                        ApplyConfigurationResult.Ignored -> Unit
                    }
                }
                routines.routines.forEach { routine ->
                    if (applyRoutine(routine, existingRoutines[routine.syncId], byName, bySyncId)) {
                        importedRoutines++
                    }
                }
                routineGyms.records.forEach { record ->
                    when (applyRoutineGyms(record)) {
                        ApplyConfigurationResult.Applied -> importedRoutineGyms++
                        ApplyConfigurationResult.InvalidReference -> skippedRows++
                        ApplyConfigurationResult.Ignored -> Unit
                    }
                }
                // Удаление идёт после Routines/RoutineGyms: актуальный снимок может сначала
                // отвязать зал, и тогда tombstone применяется за один импорт, а не за два.
                gyms.records.filterIsInstance<GymSheetRecord.Tombstone>().forEach { record ->
                    when (applyGym(record)) {
                        ApplyConfigurationResult.Applied -> importedGyms++
                        ApplyConfigurationResult.InvalidReference -> skippedRows++
                        ApplyConfigurationResult.Ignored -> Unit
                    }
                }
                validateConfigurationAggregate()
                freshWorkouts.forEach { insertWorkout(it, byName, bySyncId) }
                val bodyMeasurementDao = database.bodyMeasurementDao()
                freshMeasurements.forEach { bodyMeasurementDao.insert(it) }
            }
            val importedWorkouts = freshWorkouts.size
            val importedMeasurements = freshMeasurements.size
            if (
                importedWorkouts + importedMeasurements + importedRoutines +
                importedExercises + importedGyms == 0 && skippedRows == 0
                && importedRoutineGyms == 0
            ) {
                ImportResult.NothingToImport
            } else {
                ImportResult.Success(
                    imported = importedWorkouts,
                    skippedRows = skippedRows,
                    importedMeasurements = importedMeasurements,
                    importedRoutines = importedRoutines,
                    importedExercises = importedExercises,
                    importedGyms = importedGyms,
                    importedRoutineGyms = importedRoutineGyms,
                )
            }
        } catch (conflict: ConfigurationImportConflictException) {
            ImportResult.Failure(conflict.message ?: "Импорт создаёт конфликт конфигурации залов")
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

    private suspend fun applyExercise(record: ExerciseSheetRecord): Boolean {
        val existing = exerciseDao.getAllOnce().firstOrNull { it.syncId == record.syncId }
        return when (record) {
            is ExerciseSheetRecord.Tombstone -> false
            is ExerciseSheetRecord.Snapshot -> {
                // Built-ins are local registry authority even if an older cloud row calls itself custom.
                if (existing?.let(CanonicalExerciseRegistry::isBuiltIn) == true ||
                    CanonicalExerciseRegistry.entries.any { it.exercise.syncId == record.syncId }) return false
                if (existing != null && record.updatedAt <= existing.updatedAt) return false
                val entity = ExerciseEntity(
                    id = existing?.id ?: 0,
                    syncId = record.syncId,
                    updatedAt = record.updatedAt,
                    name = record.name,
                    muscleGroup = record.muscleGroup,
                    type = record.type,
                    isCustom = true,
                    needsMuscleMapReview = record.needsMuscleMapReview,
                )
                val exerciseId = if (existing == null) exerciseDao.insert(entity) else existing.id.also {
                    exerciseDao.update(entity)
                }
                exerciseMuscleDao.replaceForExercise(
                    exerciseId,
                    record.muscleLoads.map { (muscle, contribution) ->
                        ExerciseMuscleEntity(exerciseId, muscle, contribution)
                    },
                )
                true
            }
        }
    }

    private suspend fun applyGym(record: GymSheetRecord): ApplyConfigurationResult {
        val localTombstone = database.configurationTombstoneDao().get(
            ConfigurationTombstoneKind.GYM,
            record.syncId,
        )
        if (localTombstone != null && record.updatedAt <= localTombstone.updatedAt) {
            return ApplyConfigurationResult.Ignored
        }
        val existing = gymDao.getGymBySyncId(record.syncId)
        if (existing != null && record.updatedAt <= existing.updatedAt) {
            return ApplyConfigurationResult.Ignored
        }
        return when (record) {
            is GymSheetRecord.Tombstone -> {
                if (existing == null) {
                    localTombstone?.let {
                        database.configurationTombstoneDao().delete(it.kind, it.syncId, it.updatedAt)
                    }
                    return if (localTombstone == null) {
                        ApplyConfigurationResult.Ignored
                    } else {
                        ApplyConfigurationResult.Applied
                    }
                }
                if (
                    gymDao.getLinkedRoutines(existing.id).isNotEmpty() ||
                    gymDao.getLinkedActiveWorkouts(existing.id).isNotEmpty()
                ) {
                    ApplyConfigurationResult.InvalidReference
                } else {
                    gymDao.deleteGym(existing.id)
                    ApplyConfigurationResult.Applied
                }
            }
            is GymSheetRecord.Snapshot -> {
                val exercisesBySyncId = exerciseDao.getAllOnce().associateBy { it.syncId }
                val linkedExercises = record.exerciseSyncIds.mapNotNull(exercisesBySyncId::get)
                if (linkedExercises.size != record.exerciseSyncIds.size) {
                    return ApplyConfigurationResult.InvalidReference
                }
                val entity = GymEntity(
                    id = existing?.id ?: 0,
                    syncId = record.syncId,
                    updatedAt = record.updatedAt,
                    name = record.name,
                )
                val gymId = if (existing == null) gymDao.insertGym(entity) else existing.id.also {
                    gymDao.updateGym(entity)
                }
                gymDao.replaceGymExercises(gymId, linkedExercises.map { it.id })
                localTombstone?.let {
                    database.configurationTombstoneDao().delete(it.kind, it.syncId, it.updatedAt)
                }
                ApplyConfigurationResult.Applied
            }
        }
    }

    /** `false` означает повреждённую ссылку/конфликт; валидный старый снимок просто игнорируется. */
    private suspend fun applyRoutineGyms(record: RoutineGymsSheetRecord): ApplyConfigurationResult {
        val routine = database.routineDao().observeRoutinesFull().first()
            .firstOrNull { it.routine.syncId == record.routineSyncId }
            ?: return if (record is RoutineGymsSheetRecord.Tombstone) {
                ApplyConfigurationResult.Ignored
            } else {
                ApplyConfigurationResult.InvalidReference
            }
        if (record.updatedAt < routine.routine.updatedAt) return ApplyConfigurationResult.Ignored
        val snapshot = record as? RoutineGymsSheetRecord.Snapshot
            ?: return ApplyConfigurationResult.Ignored

        val gymsBySyncId = gymDao.getGyms().associateBy { it.syncId }
        val gyms = snapshot.gymSyncIds.mapNotNull(gymsBySyncId::get)
        if (gyms.size != snapshot.gymSyncIds.size) return ApplyConfigurationResult.InvalidReference
        if (gyms.isNotEmpty()) {
            val availableIds = gymDao.getAvailableExercises(gyms.map(GymEntity::id), gyms.size)
                .mapTo(hashSetOf()) { it.id }
            if (routine.exercises.any { it.exercise.id !in availableIds }) {
                return ApplyConfigurationResult.InvalidReference
            }
        }
        val currentGymIds = routine.gyms.mapTo(hashSetOf()) { it.syncId }
        if (currentGymIds == snapshot.gymSyncIds) return ApplyConfigurationResult.Ignored
        gymDao.replaceRoutineGyms(routine.routine.id, gyms.map(GymEntity::id))
        return ApplyConfigurationResult.Applied
    }

    /**
     * Последний барьер транзакции импорта. Промежуточный порядок применения удалённых снимков
     * не важен: наружу попадёт только целиком совместимый aggregate, иначе Room откатит всё.
     */
    private suspend fun validateConfigurationAggregate() {
        val conflicts = mutableListOf<String>()
        database.routineDao().observeRoutinesFull().first().forEach { routine ->
            val gyms = routine.gyms
            if (gyms.isEmpty()) return@forEach
            val availableIds = gymDao.getAvailableExercises(gyms.map(GymEntity::id), gyms.size)
                .mapTo(hashSetOf(), ExerciseEntity::id)
            val unavailable = routine.exercises
                .map { it.exercise }
                .filter { it.id !in availableIds }
                .distinctBy(ExerciseEntity::id)
            if (unavailable.isNotEmpty()) {
                conflicts += "Программа «${routine.routine.name}»: " +
                    unavailable.joinToString { it.name }
            }
        }

        workoutDao.getActiveWorkoutId()?.let { workoutId ->
            val workout = workoutDao.getWorkoutFull(workoutId) ?: return@let
            val gyms = gymDao.getGymsForWorkout(workoutId)
            if (gyms.isEmpty()) return@let
            val availableIds = gymDao.getAvailableExercises(gyms.map(GymEntity::id), gyms.size)
                .mapTo(hashSetOf(), ExerciseEntity::id)
            val unavailable = workout.exercises
                .map { it.exercise }
                .filter { it.id !in availableIds }
                .distinctBy(ExerciseEntity::id)
            if (unavailable.isNotEmpty()) {
                conflicts += "Активная тренировка «${workout.workout.name}»: " +
                    unavailable.joinToString { it.name }
            }
        }

        if (conflicts.isNotEmpty()) {
            throw ConfigurationImportConflictException(
                "Импорт заблокирован: упражнения отсутствуют в выбранных залах. " +
                    conflicts.joinToString("; "),
            )
        }
    }

    private suspend fun insertWorkout(
        parsed: ParsedWorkout,
        byName: MutableMap<String, Long>,
        bySyncId: MutableMap<String, Long>,
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
            val exerciseId = exercise.exerciseSyncId?.let { syncId -> bySyncId[syncId] } ?: byName[key] ?: createExercise(
                name = exercise.name,
                muscleGroup = exercise.muscleGroup,
                type = exercise.type,
                syncId = exercise.exerciseSyncId,
            ).also { createdId ->
                byName[key] = createdId
                exercise.exerciseSyncId?.let { bySyncId[it] = createdId }
            }
            val workoutExerciseId = workoutDao.insertWorkoutExercise(
                WorkoutExerciseEntity(
                    workoutId = parsed.id,
                    exerciseId = exerciseId,
                    sectionId = exercise.sectionId ?: java.util.UUID.randomUUID().toString(),
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
        bySyncId: MutableMap<String, Long>,
    ): Boolean {
        val localTombstone = database.configurationTombstoneDao().get(
            ConfigurationTombstoneKind.ROUTINE,
            parsed.syncId,
        )
        if (localTombstone != null && parsed.updatedAt <= localTombstone.updatedAt) return false
        if (local != null && parsed.updatedAt <= local.routine.updatedAt) return false
        val routineDao = database.routineDao()
        if (parsed.isDeleted) {
            if (local != null) routineDao.deleteRoutine(local.routine.id)
            localTombstone?.let {
                database.configurationTombstoneDao().delete(it.kind, it.syncId, it.updatedAt)
            }
            return local != null || localTombstone != null
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
            val exerciseId = exercise.syncId?.let { stableId ->
                bySyncId[stableId] ?: createExercise(
                    name = exercise.name,
                    muscleGroup = exercise.muscleGroup,
                    type = exercise.type,
                    syncId = stableId,
                ).also { createdId ->
                    bySyncId[stableId] = createdId
                    byName.putIfAbsent(key, createdId)
                }
            } ?: byName[key] ?: createExercise(
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
        localTombstone?.let {
            database.configurationTombstoneDao().delete(it.kind, it.syncId, it.updatedAt)
        }
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
        syncId: String? = null,
    ): Long {
        val entity = ExerciseEntity(
            name = name,
            muscleGroup = muscleGroup,
            type = type,
            isCustom = true,
            syncId = syncId ?: java.util.UUID.randomUUID().toString(),
            // Временный fallback из legacy Routines всегда уступает полноценному Exercises snapshot.
            updatedAt = 1,
        )
        val id = exerciseDao.insert(entity)
        exerciseMuscleDao.upsertAll(entity.copy(id = id).muscleRows())
        return id
    }

    private companion object {
        const val WORKOUTS_SHEET = "Workouts"
        const val MEASUREMENTS_SHEET = "Measurements"
        const val ROUTINES_SHEET = "Routines"
        /** v9-compatible snapshot shape: A:S; variant cells are intentionally ignored. */
        const val WORKOUTS_RANGE = "Workouts!A:S"
        const val MEASUREMENTS_RANGE = "Measurements!A:AP"
        const val ROUTINES_RANGE = "Routines!A:M"
    }
}

private enum class ApplyConfigurationResult {
    Applied,
    Ignored,
    InvalidReference,
}

private class ConfigurationImportConflictException(message: String) : IllegalStateException(message)
