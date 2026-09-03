package com.valerochka1337.valerochkagym.data.google

import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.RoutineRowMapper
import com.valerochka1337.valerochkagym.domain.WorkoutRowMapper
import com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementRowMapper
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
    data class PermanentFailure(val reason: String) : UploadResult
    data class TransientFailure(val error: String) : UploadResult
}

/** Выгрузка тренировок, замеров и пользовательских программ в выбранную Google-таблицу. */
interface SheetsRepository {
    suspend fun uploadWorkout(workoutId: String): UploadResult
    suspend fun uploadMeasurement(measurementId: String): UploadResult
    suspend fun uploadRoutine(routineSyncId: String): UploadResult
    suspend fun uploadRoutineDeletion(routineSyncId: String, updatedAt: Long): UploadResult
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
    private val routineDao: RoutineDao,
) : SheetsRepository {

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
            ensureWorkoutsHeader(bearer, spreadsheetId)?.let { return permanentWorkout(workoutId, it) }
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
                listOf(BodyMeasurementRowMapper.HEADER_ROW, BodyMeasurementRowMapper.row(measurement))
            } else {
                listOf(BodyMeasurementRowMapper.row(measurement))
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
                    RoutineRowMapper.STABLE_EXERCISE_HEADER_ROW -> ensureRoutinesHeader(bearer, spreadsheetId, 12, listOf("variant_id"))
                    RoutineRowMapper.LEGACY_HEADER_ROW -> ensureRoutinesHeader(bearer, spreadsheetId, 11, listOf("exercise_id", "variant_id"))
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
     * Expands only recognised app headers. Inserting dimensions before writing protects any user
     * columns to the right; the re-read turns a retry/race into a no-op rather than a duplicate.
     */
    private suspend fun ensureRoutinesHeader(
        bearer: String,
        spreadsheetId: String,
        oldSize: Int,
        extension: List<String>,
    ) {
        // A retry may follow a lost HTTP response even though the previous request committed.
        // Read first: a complete recognised upgrade is a no-op and never shifts user columns twice.
        if (readRows(bearer, spreadsheetId, ROUTINES_RANGE).firstOrNull() == RoutineRowMapper.HEADER_ROW) return
        val sheet = api.getSpreadsheet(bearer, spreadsheetId).sheets
            .firstOrNull { it.properties.title == ROUTINES_SHEET }?.properties
            ?: throw IOException("Routines sheet is missing")
        val sheetId = requireNotNull(sheet.sheetId)
        try {
            api.batchUpdate(
                bearer, spreadsheetId,
                BatchUpdateRequestDto(listOf(BatchRequestDto(
                    insertDimension = InsertDimensionDto(
                        DimensionRangeDto(sheetId, "COLUMNS", oldSize, oldSize + extension.size),
                    ),
                ))),
            )
        } catch (error: IOException) {
            if (readRows(bearer, spreadsheetId, ROUTINES_RANGE).firstOrNull() == RoutineRowMapper.HEADER_ROW) return
            throw error
        }
        try {
            api.updateValues(
                bearer, spreadsheetId,
                "Routines!${columnName(oldSize)}1:M",
                UpdateValuesDto(jsonRows(listOf(extension))),
            )
        } catch (error: IOException) {
            if (readRows(bearer, spreadsheetId, ROUTINES_RANGE).firstOrNull() == RoutineRowMapper.HEADER_ROW) return
            throw error
        }
        val header = readRows(bearer, spreadsheetId, ROUTINES_RANGE).firstOrNull()
        check(header == RoutineRowMapper.HEADER_ROW) { "Routines header upgrade did not complete" }
    }

    private suspend fun ensureWorkoutsHeader(bearer: String, spreadsheetId: String): String? {
        val header = api.getValues(bearer, spreadsheetId, "Workouts!1:1").values?.firstOrNull().orEmpty()
        if (header.isEmpty() || header == WorkoutRowMapper.HEADER_ROW) return null
        val legacy = WorkoutRowMapper.HEADER_ROW.take(14)
        if (header != legacy) return "Заголовок листа Workouts изменён вручную — не удалось безопасно выгрузить тренировку"
        val sheet = api.getSpreadsheet(bearer, spreadsheetId).sheets
            .firstOrNull { it.properties.title == WORKOUTS_SHEET }?.properties
            ?: return "Не удалось определить лист Workouts для обновления заголовка"
        val sheetId = sheet.sheetId ?: return "Не удалось определить лист Workouts для обновления заголовка"
        try {
            api.batchUpdate(
                bearer, spreadsheetId,
                BatchUpdateRequestDto(listOf(BatchRequestDto(
                    insertDimension = InsertDimensionDto(DimensionRangeDto(sheetId, "COLUMNS", 14, 19)),
                ))),
            )
        } catch (error: IOException) {
            if (api.getValues(bearer, spreadsheetId, "Workouts!A:S").values?.firstOrNull() == WorkoutRowMapper.HEADER_ROW) return null
            throw error
        }
        try {
            api.updateValues(
                bearer, spreadsheetId, "Workouts!O1:S1",
                UpdateValuesDto(jsonRows(listOf(WorkoutRowMapper.HEADER_ROW.drop(14)))),
            )
        } catch (error: IOException) {
            if (api.getValues(bearer, spreadsheetId, "Workouts!A:S").values?.firstOrNull() == WorkoutRowMapper.HEADER_ROW) return null
            throw error
        }
        val reread = api.getValues(bearer, spreadsheetId, "Workouts!A:S").values?.firstOrNull()
        return if (reread == WorkoutRowMapper.HEADER_ROW) null else "Не удалось безопасно обновить заголовок Workouts"
    }

    private fun columnName(index: Int): String = ('A'.code + index).toChar().toString()

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
        if (header.isEmpty() || header == BodyMeasurementRowMapper.HEADER_ROW) return null
        val legacy = BodyMeasurementRowMapper.HEADER_ROW.take(LEGACY_MEASUREMENT_COLUMN_COUNT)
        if (header.take(LEGACY_MEASUREMENT_COLUMN_COUNT) != legacy) {
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
                                startIndex = LEGACY_MEASUREMENT_COLUMN_COUNT,
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
            range = MEASUREMENT_EXTENSION_HEADER_RANGE,
            body = UpdateValuesDto(jsonRows(listOf(BodyMeasurementRowMapper.HEADER_ROW.drop(LEGACY_MEASUREMENT_COLUMN_COUNT)))),
        )
        return null
    }

    private suspend fun workoutsSheetExists(bearer: String, spreadsheetId: String): Boolean =
        api.getSpreadsheet(bearer, spreadsheetId).sheets.any { it.properties.title == WORKOUTS_SHEET }

    private suspend fun measurementsSheetExists(bearer: String, spreadsheetId: String): Boolean =
        api.getSpreadsheet(bearer, spreadsheetId).sheets.any { it.properties.title == MEASUREMENTS_SHEET }

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

    private companion object {
        const val WORKOUTS_SHEET = "Workouts"
        const val MEASUREMENTS_SHEET = "Measurements"
        const val ROUTINES_SHEET = "Routines"

        /** Sheets отвечает 400 на addSheet, если лист с таким title уже существует. */
        const val ADD_SHEET_CONFLICT = 400

        const val WORKOUT_ID_RANGE = "Workouts!A:A"
        const val MEASUREMENT_ID_RANGE = "Measurements!A:A"
        const val ROUTINES_RANGE = "Routines!A:M"

        /** Workouts includes immutable section and execution snapshot columns A:S. */
        const val WORKOUT_APPEND_RANGE = "Workouts!A:S"
        const val MEASUREMENT_APPEND_RANGE = "Measurements!A:AP"
        const val ROUTINE_APPEND_RANGE = "Routines!A:M"
        const val MEASUREMENT_HEADER_RANGE = "Measurements!1:1"
        const val MEASUREMENT_EXTENSION_HEADER_RANGE = "Measurements!O1:AP1"
        const val LEGACY_MEASUREMENT_COLUMN_COUNT = 14

        const val ROUTINE_ID_COLUMN = 0
        const val ROUTINE_UPDATED_AT_COLUMN = 1
        const val ROUTINE_DELETED_COLUMN = 2
        const val ROUTINE_EXERCISE_NAME_COLUMN = 6
        const val ROUTINE_EXERCISE_ID_COLUMN = 11

        val EMPTY_CELL = JsonPrimitive("")
    }
}
