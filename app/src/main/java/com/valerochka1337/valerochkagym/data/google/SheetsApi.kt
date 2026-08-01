package com.valerochka1337.valerochkagym.data.google

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Минимальный клиент Google Sheets API v4 для выгрузки тренировок. Токен передаётся явным
 * заголовком `Authorization` в каждом методе (а не через OkHttp-интерсептор), потому что
 * получение access-токена — suspend-операция ([GoogleAuth.getAccessToken]), которую нельзя
 * выполнить внутри синхронного интерсептора.
 *
 * Base URL — `https://sheets.googleapis.com/`. Неизвестные поля ответов игнорируются
 * (см. настройку `Json` в DI), поэтому DTO описывают только нужные части.
 */
interface SheetsApi {

    /** Свойства листов таблицы (нужны только `title`) — чтобы понять, есть ли лист «Workouts». */
    @GET("v4/spreadsheets/{spreadsheetId}")
    suspend fun getSpreadsheet(
        @Header("Authorization") bearer: String,
        @Path("spreadsheetId") spreadsheetId: String,
        @Query("fields") fields: String = "sheets.properties",
    ): SpreadsheetDto

    /** Пакетное изменение структуры таблицы — здесь используется только для `addSheet`. */
    @POST("v4/spreadsheets/{spreadsheetId}:batchUpdate")
    suspend fun batchUpdate(
        @Header("Authorization") bearer: String,
        @Path("spreadsheetId") spreadsheetId: String,
        @Body body: BatchUpdateRequestDto,
    ): JsonElement

    /** Значения диапазона (для идемпотентности читаем колонку A листа «Workouts»). */
    @GET("v4/spreadsheets/{spreadsheetId}/values/{range}")
    suspend fun getValues(
        @Header("Authorization") bearer: String,
        @Path("spreadsheetId") spreadsheetId: String,
        @Path("range") range: String,
    ): ValueRangeDto

    /** Добавляет строки в конец таблицы `INSERT_ROWS`, значения — как есть (`RAW`). */
    @POST("v4/spreadsheets/{spreadsheetId}/values/{range}:append")
    suspend fun appendValues(
        @Header("Authorization") bearer: String,
        @Path("spreadsheetId") spreadsheetId: String,
        @Path("range") range: String,
        @Body body: AppendValuesDto,
        @Query("valueInputOption") valueInputOption: String = "RAW",
        @Query("insertDataOption") insertDataOption: String = "INSERT_ROWS",
    ): JsonElement
}

@Serializable
data class SpreadsheetDto(
    val sheets: List<SheetDto> = emptyList(),
)

@Serializable
data class SheetDto(
    val properties: SheetPropertiesDto,
)

@Serializable
data class SheetPropertiesDto(
    val title: String,
)

@Serializable
data class BatchUpdateRequestDto(
    val requests: List<BatchRequestDto>,
)

@Serializable
data class BatchRequestDto(
    val addSheet: AddSheetDto,
)

@Serializable
data class AddSheetDto(
    val properties: SheetPropertiesDto,
)

/**
 * Ответ `values.get`. Ключ `values` отсутствует у пустого диапазона, поэтому поле nullable.
 * Значения читаем как строки — колонка A (`workout_id`) всегда строковая.
 */
@Serializable
data class ValueRangeDto(
    val values: List<List<String>>? = null,
)

/**
 * Тело `values.append`. [values] собирается вручную ([JsonArray] из [JsonArray]) из
 * [com.valerochka1337.valerochkagym.domain.WorkoutRowMapper.rows], чтобы числа уходили числами,
 * а `null` — пустой строкой (см. `SheetsRepositoryImpl.cellToJson`).
 */
@Serializable
data class AppendValuesDto(
    val values: JsonArray,
)
