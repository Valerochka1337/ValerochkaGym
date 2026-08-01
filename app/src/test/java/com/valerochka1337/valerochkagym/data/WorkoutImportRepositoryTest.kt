package com.valerochka1337.valerochkagym.data

import android.app.Activity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.ImportResult
import com.valerochka1337.valerochkagym.data.google.SheetsApi
import com.valerochka1337.valerochkagym.data.google.SheetDto
import com.valerochka1337.valerochkagym.data.google.SheetPropertiesDto
import com.valerochka1337.valerochkagym.data.google.SpreadsheetDto
import com.valerochka1337.valerochkagym.data.google.TokenResult
import com.valerochka1337.valerochkagym.data.google.ValueRangeDto
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepositoryImpl
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.WorkoutRowMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class WorkoutImportRepositoryTest : RoomDaoTest() {

    private val header = WorkoutRowMapper.HEADER_ROW
    private fun dataRow(
        id: String, date: String, time: String, name: String, exercise: String,
        muscle: String, type: String, setIndex: String, weight: String = "", reps: String = "",
    ) = listOf(id, date, time, name, exercise, muscle, type, setIndex, weight, reps, "", "", "", "")

    @Test
    fun `imports new workouts and marks them UPLOADED with honest finish`() = runTest {
        val api = FakeSheetsApi(
            sheets = mutableListOf("Workouts"),
            values = mutableListOf(
                header,
                dataRow("w-1", "2026-01-02", "10:00", "Ноги", "Присед", "Ноги", "Силовое", "1", "100", "5"),
                dataRow("w-1", "2026-01-02", "10:05", "Ноги", "Присед", "Ноги", "Силовое", "2", "105", "3"),
            ),
        )

        val result = repository(api).importAll()

        assertEquals(ImportResult.Success(1), result)
        val full = workoutFull("w-1")
        assertEquals(UploadStatus.UPLOADED, full.workout.uploadStatus)
        // finishedAt = максимум времён подходов (10:05) > startedAt (10:00)
        assertTrue(full.workout.finishedAt!! > full.workout.startedAt)
        assertEquals(2, full.exercises.single().sets.size)
    }

    @Test
    fun `matches existing exercise by name and creates missing ones`() = runTest {
        db.exerciseDao().insert(
            ExerciseEntity(name = "Присед", muscleGroup = MuscleGroup.LEGS, type = ExerciseType.STRENGTH),
        )
        val api = FakeSheetsApi(
            sheets = mutableListOf("Workouts"),
            values = mutableListOf(
                header,
                dataRow("w-1", "2026-01-02", "10:00", "T", "Присед", "Ноги", "Силовое", "1", "100", "5"),
                dataRow("w-1", "2026-01-02", "10:05", "T", "Жим", "Грудь", "Силовое", "1", "60", "8"),
            ),
        )

        repository(api).importAll()

        // Присед переиспользован (1), Жим создан (2) → всего 2 упражнения.
        assertEquals(2, tableCount("exercises"))
    }

    @Test
    fun `skips workouts already present locally`() = runTest {
        insertWorkout("w-1", startedAt = 1_000, finishedAt = 2_000)
        val api = FakeSheetsApi(
            sheets = mutableListOf("Workouts"),
            values = mutableListOf(
                header,
                dataRow("w-1", "2026-01-02", "10:00", "T", "Присед", "Ноги", "Силовое", "1", "100", "5"),
            ),
        )

        assertEquals(ImportResult.NothingToImport, repository(api).importAll())
    }

    @Test
    fun `missing Workouts sheet is nothing to import`() = runTest {
        val api = FakeSheetsApi(sheets = mutableListOf(), values = mutableListOf())
        assertEquals(ImportResult.NothingToImport, repository(api).importAll())
    }

    @Test
    fun `missing spreadsheet id is a failure`() = runTest {
        val result = repository(FakeSheetsApi(), settings = settingsRepository(null)).importAll()
        assertTrue(result is ImportResult.Failure)
    }

    @Test
    fun `401 is a failure`() = runTest {
        val api = FakeSheetsApi(sheets = mutableListOf("Workouts"), failGetValues = httpException(401))
        assertTrue(repository(api).importAll() is ImportResult.Failure)
    }

    @Test
    fun `IOException is a failure`() = runTest {
        val api = FakeSheetsApi(sheets = mutableListOf("Workouts"), failGetValues = IOException("net"))
        assertTrue(repository(api).importAll() is ImportResult.Failure)
    }

    @Test
    fun `database error during insert surfaces as failure`() = runTest {
        // Реальный путь вставки, но упражнение падает при insert → транзакция откатывается,
        // а общий catch превращает ошибку в Failure вместо пробрасывания наружу.
        val failingExercises = object : ExerciseDao by db.exerciseDao() {
            override suspend fun insert(exercise: ExerciseEntity): Long = throw RuntimeException("db insert failed")
        }
        val api = FakeSheetsApi(
            sheets = mutableListOf("Workouts"),
            values = mutableListOf(
                header,
                dataRow("w-1", "2026-01-02", "10:00", "T", "Присед", "Ноги", "Силовое", "1", "100", "5"),
            ),
        )
        val repo = WorkoutImportRepositoryImpl(
            api,
            FakeGoogleAuth(TokenResult.Success("token")),
            settingsRepository(SPREADSHEET_ID),
            db,
            db.workoutDao(),
            failingExercises,
        )

        assertTrue(repo.importAll() is ImportResult.Failure)
    }

    // region helpers

    private fun repository(
        api: FakeSheetsApi,
        auth: GoogleAuth = FakeGoogleAuth(TokenResult.Success("token")),
        settings: SettingsRepository = settingsRepository(SPREADSHEET_ID),
    ): WorkoutImportRepositoryImpl =
        WorkoutImportRepositoryImpl(api, auth, settings, db, db.workoutDao(), db.exerciseDao())

    private fun settingsRepository(spreadsheetId: String?): SettingsRepository {
        val prefs = if (spreadsheetId == null) emptyPreferences()
        else mutablePreferencesOf(stringPreferencesKey("spreadsheet_id") to spreadsheetId)
        return SettingsRepository(FakeDataStore(prefs))
    }

    private fun httpException(code: Int): HttpException =
        HttpException(Response.error<Unit>(code, "".toResponseBody()))

    private class FakeSheetsApi(
        val sheets: MutableList<String> = mutableListOf(),
        private val values: MutableList<List<String>> = mutableListOf(),
        private val failGetSpreadsheet: Exception? = null,
        private val failGetValues: Exception? = null,
    ) : SheetsApi {
        override suspend fun getSpreadsheet(bearer: String, spreadsheetId: String, fields: String): SpreadsheetDto {
            failGetSpreadsheet?.let { throw it }
            return SpreadsheetDto(sheets.map { SheetDto(SheetPropertiesDto(it)) })
        }
        override suspend fun batchUpdate(bearer: String, spreadsheetId: String, body: com.valerochka1337.valerochkagym.data.google.BatchUpdateRequestDto): JsonElement = JsonNull
        override suspend fun getValues(bearer: String, spreadsheetId: String, range: String): ValueRangeDto {
            failGetValues?.let { throw it }
            return ValueRangeDto(values = values.ifEmpty { null })
        }
        override suspend fun appendValues(bearer: String, spreadsheetId: String, range: String, body: com.valerochka1337.valerochkagym.data.google.AppendValuesDto, valueInputOption: String, insertDataOption: String): JsonElement = JsonNull
    }

    private class FakeGoogleAuth(private val token: TokenResult) : GoogleAuth {
        override suspend fun signIn(activity: Activity): Result<String> = Result.success("u@e.com")
        override suspend fun authorize(activity: Activity): AuthorizeOutcome = AuthorizeOutcome.Granted
        override suspend fun getAccessToken(): TokenResult = token
        override suspend fun signOut() = Unit
    }

    private class FakeDataStore(prefs: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(prefs)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            state.value = transform(state.value); return state.value
        }
    }

    // endregion

    private companion object {
        const val SPREADSHEET_ID = "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"
    }
}
