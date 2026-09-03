package com.valerochka1337.valerochkagym.data

import android.app.Activity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
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
import com.valerochka1337.valerochkagym.domain.RoutineRowMapper
import com.valerochka1337.valerochkagym.domain.WorkoutRowMapper
import com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementRowMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    private val header = WorkoutRowMapper.HEADER_ROW.take(14)
    private val v9Header = WorkoutRowMapper.HEADER_ROW
    private fun dataRow(
        id: String, date: String, time: String, name: String, exercise: String,
        muscle: String, type: String, setIndex: String, weight: String = "", reps: String = "",
    ) = listOf(id, date, time, name, exercise, muscle, type, setIndex, weight, reps, "", "", "", "")

    @Test
    fun `rejects conflicting v9 section tuple before writing local workout`() = runTest {
        val base = dataRow("w-v9", "2026-01-02", "10:00", "T", "Жим", "Грудь", "Силовое", "1", "80", "8") +
            listOf("11111111-1111-1111-1111-111111111111", "0", "22222222-2222-2222-2222-222222222222", "legacy", "Узкий хват")
        val conflict = base.toMutableList().also { it[15] = "1" }
        val api = FakeSheetsApi(sheets = mutableListOf("Workouts"), values = mutableListOf(v9Header, base, conflict))

        val result = repository(api).importAll()

        assertTrue(result is ImportResult.Failure)
        assertEquals(0, tableCount("workouts"))
    }

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
    fun `imports an InBody measurement and marks it uploaded without requeueing`() = runTest {
        val measurement = BodyMeasurementEntity(
            id = "m-1",
            measuredAt = 1_700_000_000_000,
            weightKg = 70.2,
            skeletalMuscleMassKg = 29.4,
            bodyFatPercentage = 21.7,
            bodyFatMassKg = 15.2,
            inBodyScore = 78,
            leftArmLeanMassKg = 3.1,
            rightLegFatPercentage = 111.0,
        )
        val api = FakeSheetsApi(
            sheets = mutableListOf("Measurements"),
            valuesByRange = mapOf(
                "Measurements!A:AP" to listOf(
                    BodyMeasurementRowMapper.HEADER_ROW,
                    BodyMeasurementRowMapper.row(measurement).map { it?.toString().orEmpty() },
                ),
            ),
        )

        val result = repository(api).importAll()

        assertEquals(ImportResult.Success(imported = 0, importedMeasurements = 1), result)
        val restored = db.bodyMeasurementDao().getById("m-1")!!
        assertEquals(70.2, restored.weightKg ?: 0.0, 0.0)
        assertEquals(78, restored.inBodyScore)
        assertEquals(3.1, restored.leftArmLeanMassKg ?: 0.0, 0.0)
        assertEquals(111.0, restored.rightLegFatPercentage ?: 0.0, 0.0)
        assertEquals(UploadStatus.UPLOADED, restored.uploadStatus)
        assertEquals(emptyList<String>(), db.bodyMeasurementDao().getNotUploaded())
    }

    @Test
    fun `imports a routine and restores a custom exercise absent from workout history`() = runTest {
        val plannedSets = Json.encodeToString(listOf(PlannedSet(weightKg = 80.0, reps = 8)))
        val api = FakeSheetsApi(
            sheets = mutableListOf("Routines"),
            valuesByRange = mapOf(
                "Routines!A:M" to listOf(
                    RoutineRowMapper.HEADER_ROW,
                    listOf(
                        "routine-1",
                        "200",
                        "false",
                        "День ног",
                        "Разминка",
                        "0",
                        "Мой тренажёр",
                        "LEGS",
                        "STRENGTH",
                        "90",
                        plannedSets,
                    ),
                ),
            ),
        )

        val result = repository(api).importAll()

        assertEquals(ImportResult.Success(imported = 0, importedRoutines = 1), result)
        val routine = db.routineDao().observeRoutinesFull().first().single()
        assertEquals("routine-1", routine.routine.syncId)
        assertEquals(200, routine.routine.updatedAt)
        assertEquals("День ног", routine.routine.name)
        val item = routine.exercises.single()
        assertEquals("Мой тренажёр", item.exercise.name)
        assertTrue(item.exercise.isCustom)
        assertEquals(90, item.routineExercise.restSeconds)
        assertEquals(listOf(PlannedSet(weightKg = 80.0, reps = 8)), item.routineExercise.plannedSets)
    }

    @Test
    fun `a newer routine tombstone removes the local routine during recovery`() = runTest {
        db.routineDao().upsertRoutine(
            RoutineEntity(syncId = "routine-1", updatedAt = 100, name = "Старый план"),
        )
        val api = FakeSheetsApi(
            sheets = mutableListOf("Routines"),
            valuesByRange = mapOf(
                "Routines!A:M" to listOf(
                    RoutineRowMapper.HEADER_ROW,
                    RoutineRowMapper.deletion("routine-1", 200).map { it?.toString().orEmpty() },
                ),
            ),
        )

        val result = repository(api).importAll()

        assertEquals(ImportResult.Success(imported = 0, importedRoutines = 1), result)
        assertEquals(emptyList<Any>(), db.routineDao().observeRoutinesFull().first())
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
            db.exerciseMuscleDao(),
        )

        assertTrue(repo.importAll() is ImportResult.Failure)
    }

    // region helpers

    private fun repository(
        api: FakeSheetsApi,
        auth: GoogleAuth = FakeGoogleAuth(TokenResult.Success("token")),
        settings: SettingsRepository = settingsRepository(SPREADSHEET_ID),
    ): WorkoutImportRepositoryImpl =
        WorkoutImportRepositoryImpl(
            api,
            auth,
            settings,
            db,
            db.workoutDao(),
            db.exerciseDao(),
            db.exerciseMuscleDao(),
        )

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
        private val valuesByRange: Map<String, List<List<String>>> = emptyMap(),
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
            val selected = valuesByRange[range] ?: values
            return ValueRangeDto(values = selected.ifEmpty { null })
        }
        override suspend fun appendValues(bearer: String, spreadsheetId: String, range: String, body: com.valerochka1337.valerochkagym.data.google.AppendValuesDto, valueInputOption: String, insertDataOption: String): JsonElement = JsonNull
        override suspend fun updateValues(bearer: String, spreadsheetId: String, range: String, body: com.valerochka1337.valerochkagym.data.google.UpdateValuesDto, valueInputOption: String): JsonElement = JsonNull
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
