package com.valerochka1337.valerochkagym.data.google

import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.dao.HealthDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncConflictEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.data.measurements.MeasurementRepository
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.RoutineRowMapper
import com.valerochka1337.valerochkagym.domain.WorkoutRowMapper
import com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementRowMapper
import com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementRowParser
import com.valerochka1337.valerochkagym.data.measurements.MeasurementSnapshotCodec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/**
 * Результат попытки выгрузки одной записи.
 *
 * [Success] — запись уже в таблице или только что добавлена.
 * [PermanentFailure] — повтор не поможет; у тренировок и замеров локальная запись уже стала
 * FAILED, у программ следующая правка или ручная выгрузка создаст новую попытку.
 * [TransientFailure] — сеть/429/5xx; окончательное решение о ретрае принимает воркер.
 */
sealed interface UploadResult {
    data object Success : UploadResult
    /** Delivery was deliberately suppressed after a fresh consent check; no remote call occurred. */
    data object NotAttemptedDisabled : UploadResult
    data class PermanentFailure(val reason: String) : UploadResult
    data class TransientFailure(val error: String) : UploadResult
}

/**
 * Outcome of a separately confirmed remote clear. [clearedRanges] makes a partial network
 * failure visible to the future settings UI without ever implying that local data changed.
 */
sealed interface RemoteClearResult {
    data class Success(val clearedRanges: List<String>) : RemoteClearResult
    data class Failure(
        val message: String,
        val clearedRanges: List<String> = emptyList(),
        /** The validated managed range that was not cleared. Never contains remote response data. */
        val failedRange: String? = null,
        /** Further validated managed ranges which were deliberately not attempted. */
        val remainingRanges: List<String> = emptyList(),
    ) : RemoteClearResult
}

/** Выгрузка тренировок, замеров и пользовательских программ в выбранную Google-таблицу. */
interface SheetsRepository {
    suspend fun uploadWorkout(workoutId: String): UploadResult
    suspend fun uploadMeasurement(measurementId: String): UploadResult
    /** Snapshot identity, not a mutable UUID, makes a lost response safe to retry. */
    suspend fun uploadMeasurementSnapshot(snapshot: HealthSyncOutboxEntity): UploadResult =
        uploadMeasurement(snapshot.syncId)
    suspend fun uploadRoutine(routineSyncId: String): UploadResult
    suspend fun uploadRoutineDeletion(routineSyncId: String, updatedAt: Long): UploadResult
    /** Invoked only after a separate UI confirmation; it never changes local measurements. */
    suspend fun clearMeasurementsAfterConfirmation(): RemoteClearResult =
        RemoteClearResult.Failure("Недоступно")
    /** Invoked only after a separate UI confirmation; it never changes local workouts/configuration. */
    suspend fun clearWorkoutsAndConfigurationAfterConfirmation(): RemoteClearResult =
        RemoteClearResult.Failure("Недоступно")
}

/**
 * Реализация append-only экспорта в Google Sheets.
 *
 * Тренировки занимают лист `Workouts`, замеры — `Measurements`, программы — `Routines`.
 * Каждая запись имеет стабильный UUID, а у программ ещё и монотонную версию: это делает
 * повтор WorkManager безопасным без обновления строк. В частности, локальные правки и удаление
 * замера не переписывают историческую строку `Measurements`, а удаление программы добавляет
 * tombstone в `Routines`.
 */
class SheetsRepositoryImpl @Inject constructor(
    private val api: SheetsApi,
    private val googleAuth: GoogleAuth,
    private val settingsRepository: SettingsRepository,
    private val workoutDao: WorkoutDao,
    private val bodyMeasurementDao: BodyMeasurementDao,
    private val healthDao: HealthDao,
    private val routineDao: RoutineDao,
) : SheetsRepository {

    override suspend fun clearMeasurementsAfterConfirmation(): RemoteClearResult {
        val spreadsheetId = settingsRepository.settings.first().spreadsheetId
            ?: return RemoteClearResult.Failure("Укажите таблицу в настройках")
        val token = when (val result = googleAuth.getAccessToken()) {
            is TokenResult.Success -> result.token
            TokenResult.NeedsConsent -> return RemoteClearResult.Failure("Настройте доступ к Google в настройках")
            is TokenResult.Failed -> return RemoteClearResult.Failure(GoogleErrorMessages.NO_CONNECTION)
        }
        val bearer = "Bearer $token"
        return try {
            val existing = api.getSpreadsheet(bearer, spreadsheetId).sheets
                .any { it.properties.title == MEASUREMENTS_SHEET }
            if (!existing) return RemoteClearResult.Success(emptyList())
            val header = api.getValues(bearer, spreadsheetId, MEASUREMENT_HEADER_RANGE)
                .values
                ?.firstOrNull()
                .orEmpty()
            val range = measurementClearRange(header)
                ?: return RemoteClearResult.Failure(
                    "Заголовок листа Measurements изменён вручную — очистка отменена",
                )
            // Clearing never creates/upgrades a sheet: preserving unknown/user-owned columns wins.
            val currentHeader = api.getValues(bearer, spreadsheetId, MEASUREMENT_HEADER_RANGE)
                .values
                ?.firstOrNull()
                .orEmpty()
            if (currentHeader != header || measurementClearRange(currentHeader) != range) {
                return RemoteClearResult.Failure(
                    "Заголовок листа Measurements изменён вручную — очистка отменена",
                    failedRange = range,
                )
            }
            api.clearValues(bearer, spreadsheetId, range)
            RemoteClearResult.Success(listOf(range))
        } catch (_: HttpException) {
            RemoteClearResult.Failure("Не удалось очистить замеры в Google Sheets")
        } catch (_: IOException) {
            RemoteClearResult.Failure(GoogleErrorMessages.NO_NETWORK)
        }
    }

    override suspend fun clearWorkoutsAndConfigurationAfterConfirmation(): RemoteClearResult {
        val spreadsheetId = settingsRepository.settings.first().spreadsheetId
            ?: return RemoteClearResult.Failure("Укажите таблицу в настройках")
        val token = when (val result = googleAuth.getAccessToken()) {
            is TokenResult.Success -> result.token
            TokenResult.NeedsConsent -> return RemoteClearResult.Failure("Настройте доступ к Google в настройках")
            is TokenResult.Failed -> return RemoteClearResult.Failure(GoogleErrorMessages.NO_CONNECTION)
        }
        val bearer = "Bearer $token"
        return try {
            val titles = api.getSpreadsheet(bearer, spreadsheetId).sheets
                .map { it.properties.title }
                .toSet()
            // Read and validate every existing managed sheet before clearing any one of them.
            val validated = WORKOUTS_AND_CONFIGURATION_CLEAR_DEFINITIONS.mapNotNull { definition ->
                if (definition.sheet !in titles) return@mapNotNull null
                val header = api.getValues(bearer, spreadsheetId, "${definition.sheet}!1:1")
                    .values
                    ?.firstOrNull()
                    .orEmpty()
                definition.rangeFor(header)?.let { range -> ValidatedClear(definition, header, range) }
                    ?: return RemoteClearResult.Failure(
                        "Заголовок листа ${definition.sheet} изменён вручную — очистка отменена",
                    )
            }
            val cleared = mutableListOf<String>()
            validated.forEachIndexed { index, validatedClear ->
                val remaining = validated.drop(index + 1).map(ValidatedClear::range)
                val currentHeader = try {
                    api.getValues(bearer, spreadsheetId, "${validatedClear.definition.sheet}!1:1")
                        .values
                        ?.firstOrNull()
                        .orEmpty()
                } catch (_: IOException) {
                    return RemoteClearResult.Failure(
                        "Нет сети при повторной проверке листа",
                        cleared,
                        validatedClear.range,
                        remaining,
                    )
                } catch (_: HttpException) {
                    return RemoteClearResult.Failure(
                        "Не удалось повторно проверить структуру листа",
                        cleared,
                        validatedClear.range,
                        remaining,
                    )
                }
                if (currentHeader != validatedClear.header ||
                    validatedClear.definition.rangeFor(currentHeader) != validatedClear.range
                ) {
                    return RemoteClearResult.Failure(
                        "Заголовок листа ${validatedClear.definition.sheet} изменён вручную — очистка отменена",
                        cleared,
                        validatedClear.range,
                        remaining,
                    )
                }
                try {
                    api.clearValues(bearer, spreadsheetId, validatedClear.range)
                    cleared += validatedClear.range
                } catch (error: HttpException) {
                    return RemoteClearResult.Failure(
                        "Google Sheets вернул HTTP ${error.code()} при очистке",
                        cleared,
                        validatedClear.range,
                        remaining,
                    )
                } catch (_: IOException) {
                    return RemoteClearResult.Failure(
                        "Нет сети при очистке Google Sheets",
                        cleared,
                        validatedClear.range,
                        remaining,
                    )
                }
            }
            RemoteClearResult.Success(cleared)
        } catch (error: HttpException) {
            RemoteClearResult.Failure("Google Sheets вернул HTTP ${error.code()} при проверке")
        } catch (_: IOException) {
            RemoteClearResult.Failure("Нет сети")
        }
    }

    override suspend fun uploadMeasurementSnapshot(snapshot: HealthSyncOutboxEntity): UploadResult {
        val decoded = MeasurementSnapshotCodec.decode(snapshot.canonicalPayload)
            ?: return UploadResult.PermanentFailure("Снимок замера повреждён")
        if (decoded.measurement.id != snapshot.syncId ||
            (snapshot.payloadHash != null && snapshot.payloadHash != MeasurementRepository.sha256(snapshot.canonicalPayload))
        ) {
            return UploadResult.PermanentFailure("Снимок замера не прошёл проверку целостности")
        }
        val spreadsheetId = settingsRepository.settings.first().spreadsheetId
            ?: return UploadResult.PermanentFailure("Укажите таблицу в настройках")
        val token = when (val result = googleAuth.getAccessToken()) {
            is TokenResult.Success -> result.token
            TokenResult.NeedsConsent -> return UploadResult.PermanentFailure("Настройте доступ к Google в настройках")
            is TokenResult.Failed -> return UploadResult.TransientFailure(GoogleErrorMessages.NO_CONNECTION)
        }
        val expectedRow = BodyMeasurementRowMapper.versionedRow(
            measurement = decoded.measurement,
            version = snapshot.version,
            updatedAt = snapshot.createdAt,
            isDeleted = decoded.isTombstone,
            payloadHash = snapshot.payloadHash,
            idempotencyKey = snapshot.idempotencyKey,
            includeConditions = decoded.payloadFormat == MeasurementSnapshotCodec.PayloadFormat.V2,
        )
        val bearer = "Bearer $token"
        return try {
            val sheet = ensureMeasurementsSheet(bearer, spreadsheetId)
            ensureMeasurementsHeader(bearer, spreadsheetId, sheet)?.let { return UploadResult.PermanentFailure(it) }
            val existing = api.getValues(bearer, spreadsheetId, MEASUREMENT_APPEND_RANGE).values.orEmpty()
            val sameVersion = existing.filter { row ->
                row.getOrNull(MEASUREMENT_ID_COLUMN) == snapshot.syncId &&
                    (row.getOrNull(MEASUREMENT_VERSION_COLUMN)?.toLongOrNull() == snapshot.version ||
                        (snapshot.version == 1L && row.getOrNull(MEASUREMENT_VERSION_COLUMN).isNullOrBlank()))
            }
            if (sameVersion.isNotEmpty()) {
                if (sameVersion.any { row -> !row.matchesMeasurementSnapshot(decoded, snapshot, expectedRow) }) {
                    val divergent = sameVersion.first { row -> !row.matchesMeasurementSnapshot(decoded, snapshot, expectedRow) }
                    val remotePayload = BodyMeasurementRowParser.parse(
                        listOf(BodyMeasurementRowMapper.HEADER_ROW, divergent),
                        BodyMeasurementRowParser.Schema.FULL,
                    ).snapshots.singleOrNull()?.canonicalPayload
                        ?: return UploadResult.PermanentFailure("Строка замера в Google Sheets повреждена")
                    healthDao.upsertConflict(
                        HealthSyncConflictEntity(
                            category = HealthSyncCategory.MEASUREMENTS,
                            syncId = snapshot.syncId,
                            version = snapshot.version,
                            localPayload = snapshot.canonicalPayload,
                            localPayloadHash = snapshot.payloadHash,
                            remotePayload = remotePayload,
                            remotePayloadHash = divergent.getOrNull(MEASUREMENT_HASH_COLUMN),
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                    return UploadResult.PermanentFailure("Конфликт версии замера в таблице")
                }
                markCurrentSnapshotUploaded(snapshot, decoded.isTombstone)
                return UploadResult.Success
            }
            appendRows(
                bearer, spreadsheetId, MEASUREMENT_APPEND_RANGE,
                if (existing.isEmpty()) listOf(BodyMeasurementRowMapper.HEADER_ROW, expectedRow)
                else listOf(expectedRow),
            )
            markCurrentSnapshotUploaded(snapshot, decoded.isTombstone)
            UploadResult.Success
        } catch (e: HttpException) {
            classifyMeasurementHttp(snapshot.syncId, e.code())
        } catch (e: IOException) {
            UploadResult.TransientFailure(GoogleErrorMessages.NO_NETWORK)
        }
    }

    /** A delayed ACK for v1 must never mark a v2 live projection as uploaded. */
    private suspend fun markCurrentSnapshotUploaded(snapshot: HealthSyncOutboxEntity, isTombstone: Boolean) {
        if (isTombstone) return
        val latest = healthDao.measurementSnapshots(snapshot.syncId).firstOrNull() ?: return
        if (latest.version == snapshot.version &&
            latest.canonicalPayload == snapshot.canonicalPayload &&
            latest.payloadHash == snapshot.payloadHash
        ) {
            bodyMeasurementDao.getById(snapshot.syncId)?.let {
                bodyMeasurementDao.setUploadStatus(snapshot.syncId, UploadStatus.UPLOADED, null)
            }
        }
    }

    override suspend fun uploadWorkout(workoutId: String): UploadResult {
        val spreadsheetId = settingsRepository.settings.first().spreadsheetId
            ?: return permanentWorkout(workoutId, "Укажите таблицу в настройках")

        val token = when (val result = googleAuth.getAccessToken()) {
            is TokenResult.Success -> result.token
            TokenResult.NeedsConsent -> return permanentWorkout(workoutId, "Настройте доступ к Google в настройках")
            is TokenResult.Failed -> return UploadResult.TransientFailure(GoogleErrorMessages.NO_CONNECTION)
        }

        val workout = workoutDao.getWorkoutFull(workoutId)
        if (workout == null || workout.workout.finishedAt == null) {
            return permanentWorkout(workoutId, "Тренировка не найдена")
        }

        val bearer = "Bearer $token"
        return try {
            ensureWorkoutsSheet(bearer, spreadsheetId)
            val workoutIdColumn = readIdColumn(bearer, spreadsheetId, WORKOUT_ID_RANGE)
            if (workoutIdColumn.any { it.firstOrNull() == workoutId }) {
                workoutDao.setUploadStatus(workoutId, UploadStatus.UPLOADED, null)
                return UploadResult.Success
            }
            // Если колонка пуста, шапка и данные уходят одним append без окна между ними.
            val dataRows = WorkoutRowMapper.rows(workout)
            val rows = if (workoutIdColumn.isEmpty()) {
                listOf(WorkoutRowMapper.HEADER_ROW) + dataRows
            } else {
                dataRows
            }
            appendRows(bearer, spreadsheetId, WORKOUT_APPEND_RANGE, rows)
            workoutDao.setUploadStatus(workoutId, UploadStatus.UPLOADED, null)
            UploadResult.Success
        } catch (e: HttpException) {
            classifyWorkoutHttp(workoutId, e.code())
        } catch (e: IOException) {
            UploadResult.TransientFailure(GoogleErrorMessages.NO_NETWORK)
        }
    }

    override suspend fun uploadMeasurement(measurementId: String): UploadResult {
        val spreadsheetId = settingsRepository.settings.first().spreadsheetId
            ?: return permanentMeasurement(measurementId, "Укажите таблицу в настройках")

        val token = when (val result = googleAuth.getAccessToken()) {
            is TokenResult.Success -> result.token
            TokenResult.NeedsConsent -> return permanentMeasurement(
                measurementId,
                "Настройте доступ к Google в настройках",
            )
            is TokenResult.Failed -> return UploadResult.TransientFailure(GoogleErrorMessages.NO_CONNECTION)
        }

        val measurement = bodyMeasurementDao.getById(measurementId)
            ?: return permanentMeasurement(measurementId, "Замер не найден")

        val bearer = "Bearer $token"
        return try {
            // Workouts может ещё не существовать, если человек начинает приложение с замеров.
            // Создаём его первым, чтобы Measurements гарантированно встал сразу после него.
            ensureWorkoutsSheet(bearer, spreadsheetId)
            val measurementsSheet = ensureMeasurementsSheet(bearer, spreadsheetId)
            ensureMeasurementsHeader(bearer, spreadsheetId, measurementsSheet)?.let { error ->
                return permanentMeasurement(measurementId, error)
            }
            val measurementIdColumn = readIdColumn(bearer, spreadsheetId, MEASUREMENT_ID_RANGE)
            if (measurementIdColumn.any { it.firstOrNull() == measurementId }) {
                bodyMeasurementDao.setUploadStatus(measurementId, UploadStatus.UPLOADED, null)
                return UploadResult.Success
            }
            val rows = if (measurementIdColumn.isEmpty()) {
                listOf(
                    BodyMeasurementRowMapper.HEADER_ROW,
                    BodyMeasurementRowMapper.versionedRow(
                        measurement, version = 1, updatedAt = measurement.measuredAt, isDeleted = false,
                        payloadHash = null, idempotencyKey = "${measurement.id}:1",
                    ),
                )
            } else {
                listOf(
                    BodyMeasurementRowMapper.versionedRow(
                        measurement, version = 1, updatedAt = measurement.measuredAt, isDeleted = false,
                        payloadHash = null, idempotencyKey = "${measurement.id}:1",
                    ),
                )
            }
            appendRows(bearer, spreadsheetId, MEASUREMENT_APPEND_RANGE, rows)
            bodyMeasurementDao.setUploadStatus(measurementId, UploadStatus.UPLOADED, null)
            UploadResult.Success
        } catch (e: HttpException) {
            classifyMeasurementHttp(measurementId, e.code())
        } catch (e: IOException) {
            UploadResult.TransientFailure(GoogleErrorMessages.NO_NETWORK)
        }
    }

    override suspend fun uploadRoutine(routineSyncId: String): UploadResult {
        val routine = routineDao.observeRoutinesFull().first()
            .firstOrNull { it.routine.syncId == routineSyncId }
            ?: return UploadResult.PermanentFailure("Программа не найдена")
        return uploadRoutineSnapshot(
            routineSyncId = routine.routine.syncId,
            updatedAt = routine.routine.updatedAt,
            isDeleted = false,
            rows = RoutineRowMapper.rows(routine),
        )
    }

    override suspend fun uploadRoutineDeletion(routineSyncId: String, updatedAt: Long): UploadResult =
        uploadRoutineSnapshot(
            routineSyncId = routineSyncId,
            updatedAt = updatedAt,
            isDeleted = true,
            rows = listOf(RoutineRowMapper.deletion(routineSyncId, updatedAt)),
        )

    /** Выгружает один неизменяемый снимок программы или tombstone-строку удаления. */
    private suspend fun uploadRoutineSnapshot(
        routineSyncId: String,
        updatedAt: Long,
        isDeleted: Boolean,
        rows: List<List<Any?>>,
    ): UploadResult {
        val spreadsheetId = settingsRepository.settings.first().spreadsheetId
            ?: return UploadResult.PermanentFailure("Укажите таблицу в настройках")
        val token = when (val result = googleAuth.getAccessToken()) {
            is TokenResult.Success -> result.token
            TokenResult.NeedsConsent -> return UploadResult.PermanentFailure(
                "Настройте доступ к Google в настройках",
            )
            is TokenResult.Failed -> return UploadResult.TransientFailure(GoogleErrorMessages.NO_CONNECTION)
        }

        val bearer = "Bearer $token"
        return try {
            ensureRoutinesSheet(bearer, spreadsheetId)
            val existing = readRows(bearer, spreadsheetId, ROUTINES_RANGE)
            if (existing.isNotEmpty()) {
                when (existing.first()) {
                    RoutineRowMapper.HEADER_ROW -> Unit
                    RoutineRowMapper.LEGACY_HEADER_ROW -> api.updateValues(
                        bearer = bearer,
                        spreadsheetId = spreadsheetId,
                        range = ROUTINE_EXERCISE_ID_HEADER_RANGE,
                        body = UpdateValuesDto(jsonRows(listOf(listOf("exercise_id")))),
                    )
                    else -> return UploadResult.PermanentFailure(
                        "Заголовок листа Routines изменён вручную — не удалось безопасно выгрузить программу",
                    )
                }
            }
            if (existing.any {
                    it.isRoutineVersion(routineSyncId, updatedAt, isDeleted) &&
                        (isDeleted || it.hasStableRoutineExerciseReference())
                }
            ) {
                return UploadResult.Success
            }
            val rowsToAppend = if (existing.isEmpty()) {
                listOf(RoutineRowMapper.HEADER_ROW) + rows
            } else {
                rows
            }
            appendRows(bearer, spreadsheetId, ROUTINE_APPEND_RANGE, rowsToAppend)
            UploadResult.Success
        } catch (e: HttpException) {
            classifyRoutineHttp(e.code())
        } catch (e: IOException) {
            UploadResult.TransientFailure(GoogleErrorMessages.NO_NETWORK)
        }
    }

    /** Создаёт лист `Workouts`, если его ещё нет (шапку добавляет первая выгрузка тренировки). */
    private suspend fun ensureWorkoutsSheet(bearer: String, spreadsheetId: String) {
        if (workoutsSheetExists(bearer, spreadsheetId)) return
        try {
            api.batchUpdate(
                bearer,
                spreadsheetId,
                BatchUpdateRequestDto(
                    requests = listOf(BatchRequestDto(AddSheetDto(SheetPropertiesDto(WORKOUTS_SHEET)))),
                ),
            )
        } catch (e: HttpException) {
            // Гонка: другой воркер мог создать лист между проверкой и addSheet.
            if (e.code() == ADD_SHEET_CONFLICT && workoutsSheetExists(bearer, spreadsheetId)) return
            throw e
        }
    }

    /** Создаёт `Measurements` непосредственно после `Workouts`, не меняя уже существующие листы. */
    private suspend fun ensureMeasurementsSheet(
        bearer: String,
        spreadsheetId: String,
    ): SheetPropertiesDto {
        val spreadsheet = api.getSpreadsheet(bearer, spreadsheetId)
        spreadsheet.sheets.firstOrNull { it.properties.title == MEASUREMENTS_SHEET }
            ?.let { return it.properties }

        // После ensureWorkoutsSheet этот лист обязан быть; fallback на конец делает метод
        // безопасным для нестандартного/частично обновлённого ответа API.
        val workouts = spreadsheet.sheets.withIndex().firstOrNull { it.value.properties.title == WORKOUTS_SHEET }
        val indexAfterWorkouts = workouts?.let { indexed ->
            (indexed.value.properties.index ?: indexed.index) + 1
        }
        try {
            api.batchUpdate(
                bearer,
                spreadsheetId,
                BatchUpdateRequestDto(
                    requests = listOf(
                        BatchRequestDto(
                            addSheet = AddSheetDto(SheetPropertiesDto(MEASUREMENTS_SHEET, indexAfterWorkouts)),
                        ),
                    ),
                ),
            )
        } catch (e: HttpException) {
            if (e.code() == ADD_SHEET_CONFLICT && measurementsSheetExists(bearer, spreadsheetId)) {
                return api.getSpreadsheet(bearer, spreadsheetId).sheets
                    .first { it.properties.title == MEASUREMENTS_SHEET }
                    .properties
            }
            throw e
        }
        return api.getSpreadsheet(bearer, spreadsheetId).sheets
            .first { it.properties.title == MEASUREMENTS_SHEET }
            .properties
    }

    /** Создаёт независимый append-only лист со снимками пользовательских программ. */
    private suspend fun ensureRoutinesSheet(bearer: String, spreadsheetId: String) {
        if (routinesSheetExists(bearer, spreadsheetId)) return
        try {
            api.batchUpdate(
                bearer,
                spreadsheetId,
                BatchUpdateRequestDto(
                    requests = listOf(BatchRequestDto(AddSheetDto(SheetPropertiesDto(ROUTINES_SHEET)))),
                ),
            )
        } catch (e: HttpException) {
            if (e.code() == ADD_SHEET_CONFLICT && routinesSheetExists(bearer, spreadsheetId)) return
            throw e
        }
    }

    /**
     * v1 of `Measurements` had A:N. Add the v5 report columns only to a known app-managed
     * legacy header, inserting columns first so any user content to the right is shifted intact.
     * Historical data rows intentionally keep empty cells in the new fields.
     */
    private suspend fun ensureMeasurementsHeader(
        bearer: String,
        spreadsheetId: String,
        sheet: SheetPropertiesDto,
    ): String? {
        val header = api.getValues(bearer, spreadsheetId, MEASUREMENT_HEADER_RANGE)
            .values
            ?.firstOrNull()
            .orEmpty()
        if (header.isEmpty() || header.take(BodyMeasurementRowMapper.HEADER_ROW.size) == BodyMeasurementRowMapper.HEADER_ROW) return null
        val legacy = BodyMeasurementRowMapper.LEGACY_HEADER_ROW
        val interim = BodyMeasurementRowMapper.INTERIM_HEADER_ROW
        val oldSize = when {
            header.take(interim.size) == interim -> interim.size
            header == legacy -> legacy.size
            header.take(legacy.size) == legacy -> legacy.size
            header.take(LEGACY_MEASUREMENT_COLUMN_COUNT) == legacy.take(LEGACY_MEASUREMENT_COLUMN_COUNT) -> LEGACY_MEASUREMENT_COLUMN_COUNT
            else -> return "Заголовок листа Measurements изменён вручную — не удалось безопасно расширить его"
        }
        val knownPrefix = if (oldSize == interim.size) interim else legacy.take(oldSize)
        if (header.take(oldSize) != knownPrefix) {
            return "Заголовок листа Measurements изменён вручную — не удалось безопасно расширить его"
        }
        val sheetId = sheet.sheetId
            ?: return "Не удалось определить лист Measurements для обновления заголовка"
        api.batchUpdate(
            bearer,
            spreadsheetId,
            BatchUpdateRequestDto(
                requests = listOf(
                    BatchRequestDto(
                        insertDimension = InsertDimensionDto(
                            range = DimensionRangeDto(
                                sheetId = sheetId,
                                dimension = "COLUMNS",
                                startIndex = oldSize,
                                endIndex = BodyMeasurementRowMapper.HEADER_ROW.size,
                            ),
                        ),
                    ),
                ),
            ),
        )
        api.updateValues(
            bearer = bearer,
            spreadsheetId = spreadsheetId,
            range = "Measurements!${columnName(oldSize)}1:${columnName(BodyMeasurementRowMapper.HEADER_ROW.lastIndex)}1",
            body = UpdateValuesDto(jsonRows(listOf(BodyMeasurementRowMapper.HEADER_ROW.drop(oldSize)))),
        )
        val reread = api.getValues(bearer, spreadsheetId, MEASUREMENT_HEADER_RANGE).values?.firstOrNull().orEmpty()
        return if (reread.take(BodyMeasurementRowMapper.HEADER_ROW.size) == BodyMeasurementRowMapper.HEADER_ROW) null
        else "Не удалось безопасно обновить заголовок Measurements"
    }

    private suspend fun workoutsSheetExists(bearer: String, spreadsheetId: String): Boolean =
        api.getSpreadsheet(bearer, spreadsheetId).sheets.any { it.properties.title == WORKOUTS_SHEET }

    private suspend fun measurementsSheetExists(bearer: String, spreadsheetId: String): Boolean =
        api.getSpreadsheet(bearer, spreadsheetId).sheets.any { it.properties.title == MEASUREMENTS_SHEET }

    /** Zero-based column index in A1 notation: 0 = A, 25 = Z, 26 = AA. */
    private fun columnName(index: Int): String {
        require(index >= 0)
        var value = index + 1
        return buildString {
            while (value > 0) {
                value--
                append(('A'.code + value % 26).toChar())
                value /= 26
            }
        }.reversed()
    }

    private suspend fun routinesSheetExists(bearer: String, spreadsheetId: String): Boolean =
        api.getSpreadsheet(bearer, spreadsheetId).sheets.any { it.properties.title == ROUTINES_SHEET }

    /** Колонка A содержит UUID записи; отсутствующее поле values означает пустой лист. */
    private suspend fun readIdColumn(
        bearer: String,
        spreadsheetId: String,
        range: String,
    ): List<List<String>> = api.getValues(bearer, spreadsheetId, range).values ?: emptyList()

    private suspend fun readRows(
        bearer: String,
        spreadsheetId: String,
        range: String,
    ): List<List<String>> = api.getValues(bearer, spreadsheetId, range).values ?: emptyList()

    private suspend fun appendRows(
        bearer: String,
        spreadsheetId: String,
        range: String,
        rows: List<List<Any?>>,
    ) {
        api.appendValues(bearer, spreadsheetId, range, AppendValuesDto(jsonRows(rows)))
    }

    private fun jsonRows(rows: List<List<Any?>>): JsonArray = buildJsonArray {
        rows.forEach { row ->
            add(
                buildJsonArray {
                    row.forEach { cell -> add(cellToJson(cell)) }
                },
            )
        }
    }

    private suspend fun classifyWorkoutHttp(workoutId: String, code: Int): UploadResult =
        if (HttpErrorClassifier.isPermanent(code)) {
            permanentWorkout(workoutId, HttpErrorClassifier.message(code))
        } else {
            UploadResult.TransientFailure(HttpErrorClassifier.message(code))
        }

    private suspend fun classifyMeasurementHttp(measurementId: String, code: Int): UploadResult =
        if (HttpErrorClassifier.isPermanent(code)) {
            permanentMeasurement(measurementId, HttpErrorClassifier.message(code))
        } else {
            UploadResult.TransientFailure(HttpErrorClassifier.message(code))
        }

    private fun classifyRoutineHttp(code: Int): UploadResult =
        if (HttpErrorClassifier.isPermanent(code)) {
            UploadResult.PermanentFailure(HttpErrorClassifier.message(code))
        } else {
            UploadResult.TransientFailure(HttpErrorClassifier.message(code))
        }

    private suspend fun permanentWorkout(workoutId: String, reason: String): UploadResult {
        workoutDao.setUploadStatus(workoutId, UploadStatus.FAILED, reason)
        return UploadResult.PermanentFailure(reason)
    }

    private suspend fun permanentMeasurement(measurementId: String, reason: String): UploadResult {
        bodyMeasurementDao.setUploadStatus(measurementId, UploadStatus.FAILED, reason)
        return UploadResult.PermanentFailure(reason)
    }

    private fun cellToJson(cell: Any?): JsonPrimitive = when (cell) {
        null -> EMPTY_CELL
        is Number -> JsonPrimitive(cell)
        is String -> JsonPrimitive(cell)
        else -> JsonPrimitive(cell.toString())
    }

    private fun List<String>.isRoutineVersion(
        syncId: String,
        updatedAt: Long,
        isDeleted: Boolean,
    ): Boolean =
        getOrNull(ROUTINE_ID_COLUMN) == syncId &&
            getOrNull(ROUTINE_UPDATED_AT_COLUMN)?.toLongOrNull() == updatedAt &&
            getOrNull(ROUTINE_DELETED_COLUMN).toBoolean() == isDeleted

    /** Пустой routine не требует exercise id; непустой v2-снимок обязан иметь UUID в L. */
    private fun List<String>.hasStableRoutineExerciseReference(): Boolean {
        if (getOrNull(ROUTINE_EXERCISE_NAME_COLUMN).isNullOrBlank()) return true
        val raw = getOrNull(ROUTINE_EXERCISE_ID_COLUMN) ?: return false
        return runCatching { java.util.UUID.fromString(raw) }.getOrNull()?.toString()
            ?.equals(raw, ignoreCase = true) == true
    }

    /**
     * Sheets may render the same numeric value as 70, 70.0 or an exponent.  Equality is based on
     * parsed immutable state, not presentation. A no-version v1 row is compared against the old
     * primary presentation because that format legitimately calculated two display cells.
     */
    private fun List<String>.matchesMeasurementSnapshot(
        decoded: MeasurementSnapshotCodec.Decoded,
        snapshot: HealthSyncOutboxEntity,
        expected: List<Any?>,
    ): Boolean {
        val legacy = getOrNull(MEASUREMENT_VERSION_COLUMN).isNullOrBlank()
        if (legacy && snapshot.version == 1L && decoded.payloadFormat == MeasurementSnapshotCodec.PayloadFormat.V1) {
            val legacyExpected = BodyMeasurementRowMapper.row(decoded.measurement)
            return legacyExpected.indices.all { index -> semanticallyEqualCell(getOrNull(index), legacyExpected[index]) }
        }
        val parsed = BodyMeasurementRowParser.parse(
            listOf(BodyMeasurementRowMapper.HEADER_ROW, this),
            BodyMeasurementRowParser.Schema.FULL,
        ).snapshots.singleOrNull() ?: return false
        return parsed.version == snapshot.version && parsed.isDeleted == decoded.isTombstone &&
            parsed.canonicalPayload == snapshot.canonicalPayload
    }

    private fun semanticallyEqualCell(remote: String?, expected: Any?): Boolean {
        val actual = remote?.trim().orEmpty()
        if (expected == null) return actual.isEmpty()
        return when (expected) {
            is Number -> actual.replace(',', '.').toDoubleOrNull()?.let { it == expected.toDouble() } == true
            else -> actual == expected.toString()
        }
    }

    private fun measurementClearRange(header: List<String>): String? = when {
        header.startsWithHeader(BodyMeasurementRowMapper.HEADER_ROW) -> MEASUREMENTS_MANAGED_CLEAR_RANGE
        header.startsWithHeader(BodyMeasurementRowMapper.INTERIM_HEADER_ROW) -> "Measurements!A2:AU"
        header.startsWithHeader(BodyMeasurementRowMapper.LEGACY_HEADER_ROW) ->
            "Measurements!A2:AP"
        header.startsWithHeader(BodyMeasurementRowMapper.LEGACY_HEADER_ROW.take(LEGACY_MEASUREMENT_COLUMN_COUNT)) ->
            "Measurements!A2:N"
        else -> null
    }

    private fun List<String>.startsWithHeader(expected: List<String>): Boolean =
        size >= expected.size && take(expected.size) == expected

    private companion object {
        const val WORKOUTS_SHEET = "Workouts"
        const val MEASUREMENTS_SHEET = "Measurements"
        const val ROUTINES_SHEET = "Routines"

        /** Sheets отвечает 400 на addSheet, если лист с таким title уже существует. */
        const val ADD_SHEET_CONFLICT = 400

        const val WORKOUT_ID_RANGE = "Workouts!A:A"
        const val MEASUREMENT_ID_RANGE = "Measurements!A:A"
        const val ROUTINES_RANGE = "Routines!A:M"

        /** Workouts keeps the compatible v9 A:S shape; full InBody occupies A:AP. */
        const val WORKOUT_APPEND_RANGE = "Workouts!A:S"
        const val MEASUREMENT_APPEND_RANGE = "Measurements!A:AY"
        const val ROUTINE_APPEND_RANGE = "Routines!A:M"
        const val ROUTINE_EXERCISE_ID_HEADER_RANGE = "Routines!L1"
        const val MEASUREMENT_HEADER_RANGE = "Measurements!1:1"
        const val MEASUREMENTS_MANAGED_CLEAR_RANGE = "Measurements!A2:AY"
        const val LEGACY_MEASUREMENT_COLUMN_COUNT = 14
        const val MEASUREMENT_ID_COLUMN = 0
        const val MEASUREMENT_VERSION_COLUMN = 42
        const val MEASUREMENT_HASH_COLUMN = 45

        const val ROUTINE_ID_COLUMN = 0
        const val ROUTINE_UPDATED_AT_COLUMN = 1
        const val ROUTINE_DELETED_COLUMN = 2
        const val ROUTINE_EXERCISE_NAME_COLUMN = 6
        const val ROUTINE_EXERCISE_ID_COLUMN = 11

        val EMPTY_CELL = JsonPrimitive("")

        val WORKOUTS_AND_CONFIGURATION_CLEAR_DEFINITIONS = listOf(
            ClearDefinition(
                sheet = WORKOUTS_SHEET,
                headers = listOf(
                    WorkoutRowMapper.HEADER_ROW to "S",
                    WorkoutRowMapper.HEADER_ROW.take(14) to "N",
                ),
            ),
            ClearDefinition(
                sheet = ROUTINES_SHEET,
                headers = listOf(
                    RoutineRowMapper.HEADER_ROW to "M",
                    RoutineRowMapper.LEGACY_HEADER_ROW to "K",
                ),
            ),
            ClearDefinition("Exercises", listOf(
                com.valerochka1337.valerochkagym.domain.ExerciseSheetRowMapper.HEADER_ROW to "I",
            )),
            ClearDefinition("Gyms", listOf(
                com.valerochka1337.valerochkagym.domain.GymSheetRowMapper.HEADER_ROW to "E",
            )),
            ClearDefinition("RoutineGyms", listOf(
                com.valerochka1337.valerochkagym.domain.RoutineGymsSheetRowMapper.HEADER_ROW to "D",
            )),
        )
    }

    private data class ClearDefinition(
        val sheet: String,
        val headers: List<Pair<List<String>, String>>,
    ) {
        fun rangeFor(header: List<String>): String? = headers.firstOrNull { (expected, _) ->
            header.startsWithHeader(expected)
        }?.second?.let { "$sheet!A2:$it" }

        private fun List<String>.startsWithHeader(expected: List<String>): Boolean =
            size >= expected.size && take(expected.size) == expected
    }

    private data class ValidatedClear(
        val definition: ClearDefinition,
        val header: List<String>,
        val range: String,
    )
}
