package com.valerochka1337.valerochkagym.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.SheetsApi
import com.valerochka1337.valerochkagym.data.google.SheetsRepositoryImpl
import com.valerochka1337.valerochkagym.data.google.TokenResult
import com.valerochka1337.valerochkagym.data.google.UploadResult
import com.valerochka1337.valerochkagym.data.google.AppendValuesDto
import com.valerochka1337.valerochkagym.data.google.BatchUpdateRequestDto
import com.valerochka1337.valerochkagym.data.google.SheetDto
import com.valerochka1337.valerochkagym.data.google.SheetPropertiesDto
import com.valerochka1337.valerochkagym.data.google.SpreadsheetDto
import com.valerochka1337.valerochkagym.data.google.ValueRangeDto
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.WorkoutRowMapper
import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * Tests for [SheetsRepositoryImpl] over a real in-memory [GymDatabase] (via [RoomDaoTest]) so that
 * [WorkoutRowMapper] runs against genuine [WorkoutFull] trees and [SheetsRepositoryImpl] can read
 * upload status back from the DB. Only the Google side is faked: [FakeSheetsApi] captures append
 * batches and exposes failure knobs, [FakeGoogleAuth] returns a programmable [TokenResult], and a
 * real [SettingsRepository] over an in-memory [DataStore] provides the spreadsheet id.
 */
class SheetsRepositoryTest : RoomDaoTest() {

    // region success paths

    @Test
    fun `empty spreadsheet gets the sheet created, one append with header and data, and UPLOADED`() =
        runTest {
            seedFinishedWorkout()
            val api = FakeSheetsApi()

            val result = repository(api).uploadWorkout(WORKOUT_ID)

            assertEquals(UploadResult.Success, result)
            assertEquals(1, api.batchUpdateCount) // sheet had to be created
            assertTrue(api.sheets.contains(WORKOUTS_SHEET))
            val batch = api.appended.single()
            assertEquals(WorkoutRowMapper.HEADER_ROW, batch.first())
            assertEquals(2, batch.size) // header + one completed set
            assertEquals(UploadStatus.UPLOADED, uploadStatus())
        }

    @Test
    fun `existing sheet with empty column still includes the header row`() = runTest {
        seedFinishedWorkout()
        val api = FakeSheetsApi(sheets = mutableListOf(WORKOUTS_SHEET))

        val result = repository(api).uploadWorkout(WORKOUT_ID)

        assertEquals(UploadResult.Success, result)
        assertEquals(0, api.batchUpdateCount) // sheet already there
        assertEquals(WorkoutRowMapper.HEADER_ROW, api.appended.single().first())
        assertEquals(UploadStatus.UPLOADED, uploadStatus())
    }

    @Test
    fun `existing sheet with a header and foreign ids appends only data`() = runTest {
        seedFinishedWorkout()
        val api = FakeSheetsApi(
            sheets = mutableListOf(WORKOUTS_SHEET),
            columnA = mutableListOf("workout_id", "some-other-workout"),
        )

        val result = repository(api).uploadWorkout(WORKOUT_ID)

        assertEquals(UploadResult.Success, result)
        val batch = api.appended.single()
        assertNotEquals(WorkoutRowMapper.HEADER_ROW, batch.first())
        assertEquals(1, batch.size) // one completed set, no header
        assertEquals(WORKOUT_ID, batch.first().first())
        assertEquals(UploadStatus.UPLOADED, uploadStatus())
    }

    @Test
    fun `already present workout id is idempotent - no append, UPLOADED`() = runTest {
        seedFinishedWorkout()
        val api = FakeSheetsApi(
            sheets = mutableListOf(WORKOUTS_SHEET),
            columnA = mutableListOf("workout_id", WORKOUT_ID),
        )

        val result = repository(api).uploadWorkout(WORKOUT_ID)

        assertEquals(UploadResult.Success, result)
        assertTrue(api.appended.isEmpty())
        assertEquals(UploadStatus.UPLOADED, uploadStatus())
    }

    // endregion

    // region addSheet race

    @Test
    fun `addSheet 400 race where another worker created the sheet continues to success`() = runTest {
        seedFinishedWorkout()
        // batchUpdate throws 400 but simultaneously the sheet appears, as if a parallel worker made it.
        val api = FakeSheetsApi(simulateAddSheetRace = true)

        val result = repository(api).uploadWorkout(WORKOUT_ID)

        assertEquals(UploadResult.Success, result)
        assertTrue(api.sheets.contains(WORKOUTS_SHEET))
        assertEquals(WorkoutRowMapper.HEADER_ROW, api.appended.single().first())
        assertEquals(UploadStatus.UPLOADED, uploadStatus())
    }

    @Test
    fun `addSheet 400 with the sheet still missing is a permanent failure`() = runTest {
        seedFinishedWorkout()
        val api = FakeSheetsApi(failBatchUpdate = httpException(400))

        val result = repository(api).uploadWorkout(WORKOUT_ID)

        assertEquals(UploadResult.PermanentFailure("Ошибка запроса (HTTP 400)"), result)
        assertEquals(UploadStatus.FAILED, uploadStatus())
    }

    // endregion

    // region settings and token classification

    @Test
    fun `missing spreadsheet id is permanent and marks FAILED`() = runTest {
        seedFinishedWorkout()
        val repository = repository(FakeSheetsApi(), settings = settingsRepository(spreadsheetId = null))

        val result = repository.uploadWorkout(WORKOUT_ID)

        assertEquals(UploadResult.PermanentFailure("Укажите таблицу в настройках"), result)
        assertEquals(UploadStatus.FAILED, uploadStatus())
    }

    @Test
    fun `NeedsConsent token is permanent and marks FAILED`() = runTest {
        seedFinishedWorkout()
        val repository = repository(FakeSheetsApi(), auth = FakeGoogleAuth(TokenResult.NeedsConsent))

        val result = repository.uploadWorkout(WORKOUT_ID)

        assertEquals(UploadResult.PermanentFailure("Настройте доступ к Google в настройках"), result)
        assertEquals(UploadStatus.FAILED, uploadStatus())
    }

    @Test
    fun `Failed token is transient and leaves the status untouched`() = runTest {
        seedFinishedWorkout()
        val repository = repository(
            FakeSheetsApi(),
            auth = FakeGoogleAuth(TokenResult.Failed(IOException("no network"))),
        )

        val result = repository.uploadWorkout(WORKOUT_ID)

        assertTrue(result is UploadResult.TransientFailure)
        assertEquals(UploadStatus.PENDING, uploadStatus())
    }

    // endregion

    // region HTTP classification

    @Test
    fun `401 on getValues is permanent access error and marks FAILED`() = runTest {
        seedFinishedWorkout()
        val api = FakeSheetsApi(sheets = mutableListOf(WORKOUTS_SHEET), failGetValues = httpException(401))

        val result = repository(api).uploadWorkout(WORKOUT_ID)

        assertEquals(UploadResult.PermanentFailure("Нет доступа к таблице — проверьте вход и права"), result)
        assertEquals(UploadStatus.FAILED, uploadStatus())
    }

    @Test
    fun `404 is permanent not found and marks FAILED`() = runTest {
        seedFinishedWorkout()
        val api = FakeSheetsApi(failGetSpreadsheet = httpException(404))

        val result = repository(api).uploadWorkout(WORKOUT_ID)

        assertEquals(UploadResult.PermanentFailure("Таблица не найдена — проверьте ссылку"), result)
        assertEquals(UploadStatus.FAILED, uploadStatus())
    }

    @Test
    fun `429 is transient and leaves the status untouched`() = runTest {
        seedFinishedWorkout()
        val api = FakeSheetsApi(sheets = mutableListOf(WORKOUTS_SHEET), failGetValues = httpException(429))

        val result = repository(api).uploadWorkout(WORKOUT_ID)

        assertTrue(result is UploadResult.TransientFailure)
        assertEquals(UploadStatus.PENDING, uploadStatus())
    }

    @Test
    fun `500 is transient`() = runTest {
        seedFinishedWorkout()
        val api = FakeSheetsApi(sheets = mutableListOf(WORKOUTS_SHEET), failGetValues = httpException(500))

        val result = repository(api).uploadWorkout(WORKOUT_ID)

        assertTrue(result is UploadResult.TransientFailure)
        assertEquals(UploadStatus.PENDING, uploadStatus())
    }

    @Test
    fun `IOException is transient`() = runTest {
        seedFinishedWorkout()
        val api = FakeSheetsApi(sheets = mutableListOf(WORKOUTS_SHEET), failGetValues = IOException("timeout"))

        val result = repository(api).uploadWorkout(WORKOUT_ID)

        assertTrue(result is UploadResult.TransientFailure)
        assertEquals(UploadStatus.PENDING, uploadStatus())
    }

    @Test
    fun `unknown 4xx like 422 is permanent with the code`() = runTest {
        seedFinishedWorkout()
        val api = FakeSheetsApi(sheets = mutableListOf(WORKOUTS_SHEET), failGetValues = httpException(422))

        val result = repository(api).uploadWorkout(WORKOUT_ID)

        assertEquals(UploadResult.PermanentFailure("Ошибка запроса (HTTP 422)"), result)
        assertEquals(UploadStatus.FAILED, uploadStatus())
    }

    // endregion

    // region workout state and set filtering

    @Test
    fun `unfinished workout is permanent not found and marks FAILED`() = runTest {
        insertWorkout(WORKOUT_ID, startedAt = 1_000, finishedAt = null)

        val result = repository(FakeSheetsApi()).uploadWorkout(WORKOUT_ID)

        assertEquals(UploadResult.PermanentFailure("Тренировка не найдена"), result)
        assertEquals(UploadStatus.FAILED, uploadStatus())
    }

    @Test
    fun `missing workout is permanent not found`() = runTest {
        val result = repository(FakeSheetsApi()).uploadWorkout("no-such-workout")

        assertEquals(UploadResult.PermanentFailure("Тренировка не найдена"), result)
    }

    @Test
    fun `uncompleted sets do not reach the append batch`() = runTest {
        seedFinishedWorkout(completedSets = 2, uncompletedSets = 1)
        // Header already present so the batch is data-only: exactly the completed sets.
        val api = FakeSheetsApi(sheets = mutableListOf(WORKOUTS_SHEET), columnA = mutableListOf("workout_id"))

        val result = repository(api).uploadWorkout(WORKOUT_ID)

        assertEquals(UploadResult.Success, result)
        val batch = api.appended.single()
        assertEquals(2, batch.size) // only the two completed sets
        assertTrue(batch.all { it.first() == WORKOUT_ID })
    }

    // endregion

    // region helpers

    private fun repository(
        api: FakeSheetsApi,
        auth: GoogleAuth = FakeGoogleAuth(TokenResult.Success("token")),
        settings: SettingsRepository = settingsRepository(spreadsheetId = SPREADSHEET_ID),
    ): SheetsRepositoryImpl = SheetsRepositoryImpl(api, auth, settings, db.workoutDao())

    private suspend fun uploadStatus(id: String = WORKOUT_ID): UploadStatus =
        workoutFull(id).workout.uploadStatus

    private suspend fun seedFinishedWorkout(
        id: String = WORKOUT_ID,
        completedSets: Int = 1,
        uncompletedSets: Int = 0,
    ): String {
        val exerciseId = insertExercise()
        insertWorkout(id, startedAt = 1_000, finishedAt = 2_000)
        val we = insertWorkoutExercise(id, exerciseId, position = 0)
        repeat(completedSets) { i ->
            insertSet(we, setIndex = i, weightKg = 100.0 + i, reps = 5, isCompleted = true)
        }
        repeat(uncompletedSets) { i ->
            insertSet(we, setIndex = completedSets + i, weightKg = 200.0, reps = 3, isCompleted = false)
        }
        return id
    }

    private suspend fun insertExercise(): Long =
        db.exerciseDao().insert(
            ExerciseEntity(name = "Жим штанги лёжа", muscleGroup = MuscleGroup.CHEST, type = ExerciseType.STRENGTH),
        )

    private fun settingsRepository(spreadsheetId: String?): SettingsRepository {
        val prefs = if (spreadsheetId == null) {
            emptyPreferences()
        } else {
            mutablePreferencesOf(stringPreferencesKey("spreadsheet_id") to spreadsheetId)
        }
        return SettingsRepository(FakeDataStore(prefs))
    }

    private fun httpException(code: Int): HttpException =
        HttpException(Response.error<Unit>(code, "".toResponseBody()))

    /**
     * In-memory [SheetsApi]. [sheets] are existing sheet titles; [columnA] is the existing
     * `workout_id` column (header included). [appended] captures each append batch decoded to
     * plain cell text. Any of the `fail*` knobs, when set, is thrown from the matching call;
     * [simulateAddSheetRace] makes [batchUpdate] both create the sheet and throw 400.
     */
    private class FakeSheetsApi(
        val sheets: MutableList<String> = mutableListOf(),
        private val columnA: MutableList<String> = mutableListOf(),
        private val failGetSpreadsheet: Exception? = null,
        private val failBatchUpdate: Exception? = null,
        private val failGetValues: Exception? = null,
        private val failAppend: Exception? = null,
        private val simulateAddSheetRace: Boolean = false,
    ) : SheetsApi {

        val appended: MutableList<List<List<String>>> = mutableListOf()
        var batchUpdateCount: Int = 0
            private set

        override suspend fun getSpreadsheet(bearer: String, spreadsheetId: String, fields: String): SpreadsheetDto {
            failGetSpreadsheet?.let { throw it }
            return SpreadsheetDto(sheets.map { SheetDto(SheetPropertiesDto(it)) })
        }

        override suspend fun batchUpdate(
            bearer: String,
            spreadsheetId: String,
            body: BatchUpdateRequestDto,
        ): JsonElement {
            batchUpdateCount++
            val title = body.requests.first().addSheet.properties.title
            if (simulateAddSheetRace) {
                if (!sheets.contains(title)) sheets.add(title)
                throw httpException(400)
            }
            failBatchUpdate?.let { throw it }
            if (!sheets.contains(title)) sheets.add(title)
            return JsonNull
        }

        override suspend fun getValues(bearer: String, spreadsheetId: String, range: String): ValueRangeDto {
            failGetValues?.let { throw it }
            return ValueRangeDto(values = if (columnA.isEmpty()) null else columnA.map { listOf(it) })
        }

        override suspend fun appendValues(
            bearer: String,
            spreadsheetId: String,
            range: String,
            body: AppendValuesDto,
            valueInputOption: String,
            insertDataOption: String,
        ): JsonElement {
            failAppend?.let { throw it }
            appended.add(body.values.map { row -> (row as JsonArray).map { (it as JsonPrimitive).content } })
            return JsonNull
        }

        private fun httpException(code: Int): HttpException =
            HttpException(Response.error<Unit>(code, "".toResponseBody()))
    }

    /** [GoogleAuth] whose only relevant method returns the configured [token]. */
    private class FakeGoogleAuth(private val token: TokenResult) : GoogleAuth {
        override suspend fun signIn(activity: Activity): Result<String> = Result.success("user@example.com")
        override suspend fun authorize(activity: Activity): AuthorizeOutcome = AuthorizeOutcome.Granted
        override suspend fun getAccessToken(): TokenResult = token
        override suspend fun signOut() = Unit
    }

    /** Minimal in-memory [DataStore] so a real [SettingsRepository] can read [Preferences]. */
    private class FakeDataStore(prefs: Preferences) : DataStore<Preferences> {

        private val state = MutableStateFlow(prefs)

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            state.value = transform(state.value)
            return state.value
        }
    }

    // endregion

    private companion object {
        const val WORKOUT_ID = "w-1"
        const val SPREADSHEET_ID = "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"
        const val WORKOUTS_SHEET = "Workouts"
    }
}
