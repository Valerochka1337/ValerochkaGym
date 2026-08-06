package com.valerochka1337.valerochkagym.data.google

import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
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
 * [PermanentFailure] — повтор не поможет; соответствующая локальная запись уже стала FAILED.
 * [TransientFailure] — сеть/429/5xx; окончательное решение о ретрае принимает воркер.
 */
sealed interface UploadResult {
    data object Success : UploadResult
    data class PermanentFailure(val reason: String) : UploadResult
    data class TransientFailure(val error: String) : UploadResult
}

/** Выгрузка тренировок и замеров в выбранную Google-таблицу. */
interface SheetsRepository {
    suspend fun uploadWorkout(workoutId: String): UploadResult
    suspend fun uploadMeasurement(measurementId: String): UploadResult
}

/**
 * Реализация append-only экспорта в Google Sheets.
 *
 * Тренировки занимают лист `Workouts`, замеры — `Measurements`. Для каждого типа первая колонка
 * — стабильный UUID локальной записи: это делает повтор WorkManager безопасным без обновления
 * строк. В частности, локальные правки и удаление замера не переписывают историческую строку
 * `Measurements`, что явно отражено в UI редактора.
 */
class SheetsRepositoryImpl @Inject constructor(
    private val api: SheetsApi,
    private val googleAuth: GoogleAuth,
    private val settingsRepository: SettingsRepository,
    private val workoutDao: WorkoutDao,
    private val bodyMeasurementDao: BodyMeasurementDao,
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
            ensureMeasurementsSheet(bearer, spreadsheetId)
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
    private suspend fun ensureMeasurementsSheet(bearer: String, spreadsheetId: String) {
        val spreadsheet = api.getSpreadsheet(bearer, spreadsheetId)
        if (spreadsheet.sheets.any { it.properties.title == MEASUREMENTS_SHEET }) return

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
                            AddSheetDto(SheetPropertiesDto(MEASUREMENTS_SHEET, indexAfterWorkouts)),
                        ),
                    ),
                ),
            )
        } catch (e: HttpException) {
            if (e.code() == ADD_SHEET_CONFLICT && measurementsSheetExists(bearer, spreadsheetId)) return
            throw e
        }
    }

    private suspend fun workoutsSheetExists(bearer: String, spreadsheetId: String): Boolean =
        api.getSpreadsheet(bearer, spreadsheetId).sheets.any { it.properties.title == WORKOUTS_SHEET }

    private suspend fun measurementsSheetExists(bearer: String, spreadsheetId: String): Boolean =
        api.getSpreadsheet(bearer, spreadsheetId).sheets.any { it.properties.title == MEASUREMENTS_SHEET }

    /** Колонка A содержит UUID записи; отсутствующее поле values означает пустой лист. */
    private suspend fun readIdColumn(
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
        val values: JsonArray = buildJsonArray {
            rows.forEach { row ->
                add(
                    buildJsonArray {
                        row.forEach { cell -> add(cellToJson(cell)) }
                    },
                )
            }
        }
        api.appendValues(bearer, spreadsheetId, range, AppendValuesDto(values))
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

    private companion object {
        const val WORKOUTS_SHEET = "Workouts"
        const val MEASUREMENTS_SHEET = "Measurements"

        /** Sheets отвечает 400 на addSheet, если лист с таким title уже существует. */
        const val ADD_SHEET_CONFLICT = 400

        const val WORKOUT_ID_RANGE = "Workouts!A:A"
        const val MEASUREMENT_ID_RANGE = "Measurements!A:A"

        /** Обе таблицы сейчас имеют 14 колонок A–N. */
        const val WORKOUT_APPEND_RANGE = "Workouts!A:N"
        const val MEASUREMENT_APPEND_RANGE = "Measurements!A:N"

        val EMPTY_CELL = JsonPrimitive("")
    }
}
