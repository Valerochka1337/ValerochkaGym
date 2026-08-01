package com.valerochka1337.valerochkagym.data.google

import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.WorkoutRowMapper
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/**
 * Результат попытки выгрузки одной тренировки.
 *
 * [Success] — тренировка в таблице (загружена или уже была там).
 * [PermanentFailure] — повтор не поможет ([reason] показывается пользователю); воркер завершает
 * задачу без ретраев, статус тренировки уже выставлен в [UploadStatus.FAILED].
 * [TransientFailure] — временная ошибка (сеть, 429/5xx); имеет смысл повторить. Статус здесь
 * НЕ меняется — решение о ретрае/финальном FAILED принимает воркер.
 */
sealed interface UploadResult {
    data object Success : UploadResult
    data class PermanentFailure(val reason: String) : UploadResult
    data class TransientFailure(val error: String) : UploadResult
}

/** Выгрузка завершённой тренировки в целевую Google-таблицу. */
interface SheetsRepository {
    suspend fun uploadWorkout(workoutId: String): UploadResult
}

/**
 * Реализация выгрузки в лист «Workouts» целевой таблицы.
 *
 * Порядок: проверка настроек и токена → загрузка дерева тренировки → гарантия существования
 * листа с шапкой → проверка идемпотентности по колонке `workout_id` → append строк подходов.
 * HTTP-ошибки классифицируются на постоянные и временные (см. [classifyHttp]).
 */
class SheetsRepositoryImpl @Inject constructor(
    private val api: SheetsApi,
    private val googleAuth: GoogleAuth,
    private val settingsRepository: SettingsRepository,
    private val workoutDao: WorkoutDao,
) : SheetsRepository {

    override suspend fun uploadWorkout(workoutId: String): UploadResult {
        val spreadsheetId = settingsRepository.settings.first().spreadsheetId
            ?: return permanent(workoutId, "Укажите таблицу в настройках")

        val token = when (val result = googleAuth.getAccessToken()) {
            is TokenResult.Success -> result.token
            TokenResult.NeedsConsent -> return permanent(workoutId, "Настройте доступ к Google в настройках")
            is TokenResult.Failed -> return UploadResult.TransientFailure(
                result.error?.message ?: "Не удалось получить токен Google",
            )
        }

        val workout = workoutDao.getWorkoutFull(workoutId)
        if (workout == null || workout.workout.finishedAt == null) {
            return permanent(workoutId, "Тренировка не найдена")
        }

        val bearer = "Bearer $token"
        return try {
            ensureWorkoutsSheet(bearer, spreadsheetId)
            if (isAlreadyUploaded(bearer, spreadsheetId, workoutId)) {
                workoutDao.setUploadStatus(workoutId, UploadStatus.UPLOADED, null)
                return UploadResult.Success
            }
            appendRows(bearer, spreadsheetId, WorkoutRowMapper.rows(workout))
            workoutDao.setUploadStatus(workoutId, UploadStatus.UPLOADED, null)
            UploadResult.Success
        } catch (e: HttpException) {
            classifyHttp(workoutId, e.code())
        } catch (e: IOException) {
            UploadResult.TransientFailure(e.message ?: "Сетевая ошибка")
        }
    }

    /** Создаёт лист «Workouts» с шапкой, если его ещё нет. */
    private suspend fun ensureWorkoutsSheet(bearer: String, spreadsheetId: String) {
        val spreadsheet = api.getSpreadsheet(bearer, spreadsheetId)
        val exists = spreadsheet.sheets.any { it.properties.title == WORKOUTS_SHEET }
        if (exists) return
        api.batchUpdate(
            bearer,
            spreadsheetId,
            BatchUpdateRequestDto(
                requests = listOf(BatchRequestDto(AddSheetDto(SheetPropertiesDto(WORKOUTS_SHEET)))),
            ),
        )
        appendRows(bearer, spreadsheetId, listOf(WorkoutRowMapper.HEADER_ROW))
    }

    /** true, если `workout_id` уже есть в колонке A листа «Workouts». */
    private suspend fun isAlreadyUploaded(
        bearer: String,
        spreadsheetId: String,
        workoutId: String,
    ): Boolean {
        val values = api.getValues(bearer, spreadsheetId, WORKOUT_ID_RANGE).values ?: return false
        return values.any { it.firstOrNull() == workoutId }
    }

    private suspend fun appendRows(bearer: String, spreadsheetId: String, rows: List<List<Any?>>) {
        val values: JsonArray = buildJsonArray {
            rows.forEach { row ->
                add(
                    buildJsonArray {
                        row.forEach { cell -> add(cellToJson(cell)) }
                    },
                )
            }
        }
        api.appendValues(bearer, spreadsheetId, APPEND_RANGE, AppendValuesDto(values))
    }

    /**
     * HTTP-классификация: 401/403 — нет доступа, 404 — таблицы нет (постоянные, пользователь
     * должен вмешаться); 429 и 5xx — временные (повторить); прочие 4xx — постоянные с кодом.
     */
    private suspend fun classifyHttp(workoutId: String, code: Int): UploadResult = when (code) {
        401, 403 -> permanent(workoutId, "Нет доступа к таблице — проверьте вход и права")
        404 -> permanent(workoutId, "Таблица не найдена — проверьте ссылку")
        429 -> UploadResult.TransientFailure("Слишком много запросов (HTTP 429)")
        in 500..599 -> UploadResult.TransientFailure("Ошибка сервера (HTTP $code)")
        in 400..499 -> permanent(workoutId, "Ошибка запроса (HTTP $code)")
        else -> UploadResult.TransientFailure("Неожиданный ответ (HTTP $code)")
    }

    /** Помечает тренировку упавшей и возвращает постоянную ошибку с тем же текстом. */
    private suspend fun permanent(workoutId: String, reason: String): UploadResult {
        workoutDao.setUploadStatus(workoutId, UploadStatus.FAILED, reason)
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

        /** Колонка A листа «Workouts» — там лежат `workout_id`. */
        const val WORKOUT_ID_RANGE = "Workouts!A:A"

        /** Диапазон-таблица для append (14 колонок A–N, см. WorkoutRowMapper.HEADER_ROW). */
        const val APPEND_RANGE = "Workouts!A:N"

        val EMPTY_CELL = JsonPrimitive("")
    }
}
