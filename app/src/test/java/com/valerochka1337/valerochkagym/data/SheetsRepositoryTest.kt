package com.valerochka1337.valerochkagym.data

import android.app.Activity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.google.AppendValuesDto
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.BatchUpdateRequestDto
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.SheetDto
import com.valerochka1337.valerochkagym.data.google.SheetPropertiesDto
import com.valerochka1337.valerochkagym.data.google.SheetsApi
import com.valerochka1337.valerochkagym.data.google.SheetsRepositoryImpl
import com.valerochka1337.valerochkagym.data.google.SpreadsheetDto
import com.valerochka1337.valerochkagym.data.google.TokenResult
import com.valerochka1337.valerochkagym.data.google.UpdateValuesDto
import com.valerochka1337.valerochkagym.data.google.UploadResult
import com.valerochka1337.valerochkagym.data.google.ValueRangeDto
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.RoutineRowMapper
import com.valerochka1337.valerochkagym.domain.WorkoutRowMapper
import com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementRowMapper
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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

/**
 * Tests for [SheetsRepositoryImpl] over a real in-memory GymDatabase (via [RoomDaoTest]) so that
 * [WorkoutRowMapper] runs against genuine WorkoutFull trees and [SheetsRepositoryImpl] can read
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
    val api =
        FakeSheetsApi(
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
    val api =
        FakeSheetsApi(
            sheets = mutableListOf(WORKOUTS_SHEET),
            columnA = mutableListOf("workout_id", WORKOUT_ID),
        )

    val result = repository(api).uploadWorkout(WORKOUT_ID)

    assertEquals(UploadResult.Success, result)
    assertTrue(api.appended.isEmpty())
    assertEquals(UploadStatus.UPLOADED, uploadStatus())
  }

  // endregion

  // region routines

  @Test
  fun `routine export creates Routines with a snapshot header and data`() = runTest {
    val syncId = seedRoutine()
    val api = FakeSheetsApi()

    val result = repository(api).uploadRoutine(syncId)

    assertEquals(UploadResult.Success, result)
    assertTrue(ROUTINES_SHEET in api.sheets)
    assertEquals(ROUTINE_APPEND_RANGE, api.appendRanges.single())
    assertEquals(RoutineRowMapper.HEADER_ROW, api.appended.single().first())
    assertEquals(syncId, api.appended.single()[1].first())
  }

  @Test
  fun `routine version already in Routines is idempotent`() = runTest {
    val syncId = seedRoutine(updatedAt = 200)
    val snapshot = db.routineDao().getRoutineWithExercises(1)!!
    val api =
        FakeSheetsApi(
            sheets = mutableListOf(ROUTINES_SHEET),
            routineRows =
                (listOf(RoutineRowMapper.HEADER_ROW) + RoutineRowMapper.rows(snapshot))
                    .map { row -> row.map { it?.toString().orEmpty() } }
                    .toMutableList(),
        )

    val result = repository(api).uploadRoutine(syncId)

    assertEquals(UploadResult.Success, result)
    assertTrue(api.appended.isEmpty())
  }

  @Test
  fun `routine export upgrades legacy header and appends stable exercise ids`() = runTest {
    val syncId = seedRoutine(updatedAt = 200)
    val snapshot = db.routineDao().getRoutineWithExercises(1)!!
    val legacyRows =
        (listOf(RoutineRowMapper.LEGACY_HEADER_ROW) +
                RoutineRowMapper.rows(snapshot).map { it.dropLast(2) })
            .map { row -> row.map { it?.toString().orEmpty() } }
            .toMutableList()
    val api =
        FakeSheetsApi(
            sheets = mutableListOf(ROUTINES_SHEET),
            routineRows = legacyRows,
        )

    val result = repository(api).uploadRoutine(syncId)

    assertEquals(UploadResult.Success, result)
    assertEquals(listOf(listOf(listOf("exercise_id"))), api.headerUpdates)
    assertEquals(snapshot.exercises.single().exercise.syncId, api.appended.single().single()[11])
  }

  @Test
  fun `routine deletion writes an idempotent tombstone snapshot`() = runTest {
    val api = FakeSheetsApi()

    val result = repository(api).uploadRoutineDeletion("routine-1", 300)

    assertEquals(UploadResult.Success, result)
    assertEquals(RoutineRowMapper.HEADER_ROW, api.appended.single().first())
    val tombstone = api.appended.single()[1]
    assertEquals(listOf("routine-1", "300", "true"), tombstone.take(3))
  }

  // endregion

  // region measurements

  @Test
  fun `measurement export creates Measurements directly after Workouts and appends its header`() =
      runTest {
        seedMeasurement()
        val api = FakeSheetsApi(sheets = mutableListOf("Readme", WORKOUTS_SHEET, "Archive"))

        val result = repository(api).uploadMeasurement(MEASUREMENT_ID)

        assertEquals(UploadResult.Success, result)
        assertEquals(listOf("Readme", WORKOUTS_SHEET, MEASUREMENTS_SHEET, "Archive"), api.sheets)
        assertEquals(2, api.addedSheets.single().index)
        assertEquals(MEASUREMENT_APPEND_RANGE, api.appendRanges.single())
        val batch = api.appended.single()
        assertEquals(BodyMeasurementRowMapper.HEADER_ROW, batch.first())
        assertEquals(MEASUREMENT_ID, batch[1].first())
        assertEquals("17.5", batch[1][6]) // weight × body-fat percent
        assertEquals(UploadStatus.UPLOADED, measurementUploadStatus())
      }

  @Test
  fun `legacy Measurements header receives InBody columns after N without rewriting its rows`() =
      runTest {
        seedMeasurement()
        val legacyHeader = BodyMeasurementRowMapper.HEADER_ROW.take(14)
        val api =
            FakeSheetsApi(
                sheets = mutableListOf(WORKOUTS_SHEET, MEASUREMENTS_SHEET),
                measurementColumnA = mutableListOf("measurement_id", "older-measurement"),
                measurementHeader = legacyHeader.toMutableList(),
            )

        val result = repository(api).uploadMeasurement(MEASUREMENT_ID)

        assertEquals(UploadResult.Success, result)
        val inserted = api.insertedDimensions.single()
        assertEquals("COLUMNS", inserted.range.dimension)
        assertEquals(14, inserted.range.startIndex)
        assertEquals(BodyMeasurementRowMapper.HEADER_ROW.size, inserted.range.endIndex)
        assertEquals(
            listOf(listOf(BodyMeasurementRowMapper.HEADER_ROW.drop(14))),
            api.headerUpdates,
        )
        assertEquals(MEASUREMENT_APPEND_RANGE, api.appendRanges.single())
        assertEquals(1, api.appended.single().size)
        assertEquals(MEASUREMENT_ID, api.appended.single().single().first())
        assertEquals(UploadStatus.UPLOADED, measurementUploadStatus())
      }

  @Test
  fun `already appended measurement id is idempotent and does not append again`() = runTest {
    seedMeasurement()
    val api =
        FakeSheetsApi(
            sheets = mutableListOf(WORKOUTS_SHEET, MEASUREMENTS_SHEET),
            measurementColumnA = mutableListOf("measurement_id", MEASUREMENT_ID),
        )

    val result = repository(api).uploadMeasurement(MEASUREMENT_ID)

    assertEquals(UploadResult.Success, result)
    assertTrue(api.appended.isEmpty())
    assertEquals(UploadStatus.UPLOADED, measurementUploadStatus())
  }

  @Test
  fun `permanent Sheets error marks a measurement failed`() = runTest {
    seedMeasurement()
    val api =
        FakeSheetsApi(
            sheets = mutableListOf(WORKOUTS_SHEET, MEASUREMENTS_SHEET),
            failGetValues = httpException(401),
        )

    val result = repository(api).uploadMeasurement(MEASUREMENT_ID)

    assertEquals(
        UploadResult.PermanentFailure("Нет доступа к таблице — проверьте вход и права"),
        result,
    )
    assertEquals(UploadStatus.FAILED, measurementUploadStatus())
  }

  @Test
  fun `sheet properties omit an absent index and serialize an explicit position`() {
    val json = Json { encodeDefaults = true }

    assertEquals(
        "{\"title\":\"Workouts\"}",
        json.encodeToString(SheetPropertiesDto(WORKOUTS_SHEET)),
    )
    assertEquals(
        "{\"title\":\"Measurements\",\"index\":2}",
        json.encodeToString(SheetPropertiesDto(MEASUREMENTS_SHEET, index = 2)),
    )
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
    val repository =
        repository(FakeSheetsApi(), settings = settingsRepository(spreadsheetId = null))

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
    val repository =
        repository(
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
    val api =
        FakeSheetsApi(sheets = mutableListOf(WORKOUTS_SHEET), failGetValues = httpException(401))

    val result = repository(api).uploadWorkout(WORKOUT_ID)

    assertEquals(
        UploadResult.PermanentFailure("Нет доступа к таблице — проверьте вход и права"),
        result,
    )
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
    val api =
        FakeSheetsApi(sheets = mutableListOf(WORKOUTS_SHEET), failGetValues = httpException(429))

    val result = repository(api).uploadWorkout(WORKOUT_ID)

    assertTrue(result is UploadResult.TransientFailure)
    assertEquals(UploadStatus.PENDING, uploadStatus())
  }

  @Test
  fun `500 is transient`() = runTest {
    seedFinishedWorkout()
    val api =
        FakeSheetsApi(sheets = mutableListOf(WORKOUTS_SHEET), failGetValues = httpException(500))

    val result = repository(api).uploadWorkout(WORKOUT_ID)

    assertTrue(result is UploadResult.TransientFailure)
    assertEquals(UploadStatus.PENDING, uploadStatus())
  }

  @Test
  fun `IOException is transient`() = runTest {
    seedFinishedWorkout()
    val api =
        FakeSheetsApi(
            sheets = mutableListOf(WORKOUTS_SHEET),
            failGetValues = IOException("timeout"),
        )

    val result = repository(api).uploadWorkout(WORKOUT_ID)

    assertTrue(result is UploadResult.TransientFailure)
    assertEquals(UploadStatus.PENDING, uploadStatus())
  }

  @Test
  fun `unknown 4xx like 422 is permanent with the code`() = runTest {
    seedFinishedWorkout()
    val api =
        FakeSheetsApi(sheets = mutableListOf(WORKOUTS_SHEET), failGetValues = httpException(422))

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
    val api =
        FakeSheetsApi(sheets = mutableListOf(WORKOUTS_SHEET), columnA = mutableListOf("workout_id"))

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
  ): SheetsRepositoryImpl =
      SheetsRepositoryImpl(
          api,
          auth,
          settings,
          db.workoutDao(),
          db.bodyMeasurementDao(),
          db.routineDao(),
      )

  private suspend fun uploadStatus(id: String = WORKOUT_ID): UploadStatus =
      workoutFull(id).workout.uploadStatus

  private suspend fun measurementUploadStatus(id: String = MEASUREMENT_ID): UploadStatus =
      db.bodyMeasurementDao().getById(id)!!.uploadStatus

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

  private suspend fun seedMeasurement(id: String = MEASUREMENT_ID) {
    db.bodyMeasurementDao()
        .insert(
            BodyMeasurementEntity(
                id = id,
                measuredAt = 1_700_000_000_000,
                weightKg = 70.0,
                skeletalMuscleMassKg = 28.0,
                bodyFatPercentage = 25.0,
                visceralFatLevel = 8,
                waistCm = 72.0,
                hipsCm = 96.0,
            ),
        )
  }

  private suspend fun seedRoutine(
      syncId: String = "routine-1",
      updatedAt: Long = 100,
  ): String {
    val exerciseId = insertExercise()
    val routineId =
        db.routineDao()
            .upsertRoutine(
                RoutineEntity(
                    syncId = syncId,
                    updatedAt = updatedAt,
                    name = "Грудь",
                    note = "Техника",
                ),
            )
    db.routineDao()
        .replaceRoutineExercises(
            routineId,
            listOf(
                RoutineExerciseEntity(
                    routineId = routineId,
                    exerciseId = exerciseId,
                    position = 0,
                    restSeconds = 90,
                ),
            ),
        )
    return syncId
  }

  private suspend fun insertExercise(): Long =
      db.exerciseDao()
          .insert(
              ExerciseEntity(
                  name = "Жим штанги лёжа",
                  muscleGroup = MuscleGroup.CHEST,
                  type = ExerciseType.STRENGTH,
              ),
          )

  private fun settingsRepository(spreadsheetId: String?): SettingsRepository {
    val prefs =
        if (spreadsheetId == null) {
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
   * `workout_id` column (header included). [appended] captures each append batch decoded to plain
   * cell text. Any of the `fail*` knobs, when set, is thrown from the matching call;
   * [simulateAddSheetRace] makes [batchUpdate] both create the sheet and throw 400.
   */
  private class FakeSheetsApi(
      val sheets: MutableList<String> = mutableListOf(),
      private val columnA: MutableList<String> = mutableListOf(),
      private val measurementColumnA: MutableList<String> = mutableListOf(),
      private val measurementHeader: MutableList<String> = mutableListOf(),
      private val routineRows: MutableList<List<String>> = mutableListOf(),
      private val failGetSpreadsheet: Exception? = null,
      private val failBatchUpdate: Exception? = null,
      private val failGetValues: Exception? = null,
      private val failAppend: Exception? = null,
      private val simulateAddSheetRace: Boolean = false,
  ) : SheetsApi {

    val appended: MutableList<List<List<String>>> = mutableListOf()
    val appendRanges = mutableListOf<String>()
    val addedSheets = mutableListOf<SheetPropertiesDto>()
    val insertedDimensions =
        mutableListOf<com.valerochka1337.valerochkagym.data.google.InsertDimensionDto>()
    val headerUpdates = mutableListOf<List<List<String>>>()
    var batchUpdateCount: Int = 0
      private set

    override suspend fun getSpreadsheet(
        bearer: String,
        spreadsheetId: String,
        fields: String,
    ): SpreadsheetDto {
      failGetSpreadsheet?.let { throw it }
      return SpreadsheetDto(
          sheets.mapIndexed { index, title ->
            SheetDto(SheetPropertiesDto(title = title, index = index, sheetId = index))
          }
      )
    }

    override suspend fun batchUpdate(
        bearer: String,
        spreadsheetId: String,
        body: BatchUpdateRequestDto,
    ): JsonElement {
      batchUpdateCount++
      val addSheet = body.requests.firstOrNull()?.addSheet
      if (addSheet != null && simulateAddSheetRace) {
        addSheet(addSheet.properties)
        throw httpException(400)
      }
      failBatchUpdate?.let { throw it }
      body.requests.forEach { request ->
        request.addSheet?.let { addSheet(it.properties) }
        request.insertDimension?.let { insertedDimensions += it }
      }
      return JsonNull
    }

    override suspend fun getValues(
        bearer: String,
        spreadsheetId: String,
        range: String,
    ): ValueRangeDto {
      failGetValues?.let { throw it }
      if (range == "Measurements!1:1") {
        val header =
            measurementHeader.ifEmpty {
              if (measurementColumnA.firstOrNull() == "measurement_id") {
                BodyMeasurementRowMapper.HEADER_ROW
              } else {
                emptyList()
              }
            }
        return ValueRangeDto(values = header.takeIf { it.isNotEmpty() }?.let(::listOf))
      }
      if (range == ROUTINES_RANGE) {
        return ValueRangeDto(values = routineRows.ifEmpty { null })
      }
      val values = if (range.startsWith("Measurements!")) measurementColumnA else columnA
      return ValueRangeDto(values = if (values.isEmpty()) null else values.map { listOf(it) })
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
      appendRanges += range
      val rows = body.values.map { row -> (row as JsonArray).map { (it as JsonPrimitive).content } }
      appended.add(rows)
      if (range == ROUTINE_APPEND_RANGE) routineRows += rows
      return JsonNull
    }

    override suspend fun updateValues(
        bearer: String,
        spreadsheetId: String,
        range: String,
        body: UpdateValuesDto,
        valueInputOption: String,
    ): JsonElement {
      val rows = body.values.map { row -> (row as JsonArray).map { (it as JsonPrimitive).content } }
      headerUpdates += rows
      if (range == "Measurements!O1:AP1" && rows.singleOrNull() != null) {
        measurementHeader += rows.single()
      }
      return JsonNull
    }

    private fun addSheet(properties: SheetPropertiesDto) {
      if (sheets.contains(properties.title)) return
      val index = properties.index?.coerceIn(0, sheets.size) ?: sheets.size
      sheets.add(index, properties.title)
      addedSheets += properties
    }

    @Suppress("SameParameterValue")
    private fun httpException(code: Int): HttpException =
        HttpException(Response.error<Unit>(code, "".toResponseBody()))
  }

  /** [GoogleAuth] whose only relevant method returns the configured [token]. */
  private class FakeGoogleAuth(private val token: TokenResult) : GoogleAuth {
    override suspend fun signIn(activity: Activity): Result<String> =
        Result.success("user@example.com")

    override suspend fun authorize(activity: Activity): AuthorizeOutcome = AuthorizeOutcome.Granted

    override suspend fun getAccessToken(): TokenResult = token

    override suspend fun signOut() = Unit
  }

  /** Minimal in-memory [DataStore] so a real [SettingsRepository] can read [Preferences]. */
  private class FakeDataStore(prefs: Preferences) : DataStore<Preferences> {

    private val state = MutableStateFlow(prefs)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences {
      state.value = transform(state.value)
      return state.value
    }
  }

  private companion object {
    const val WORKOUT_ID = "w-1"
    const val MEASUREMENT_ID = "m-1"
    const val SPREADSHEET_ID = "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"
    const val WORKOUTS_SHEET = "Workouts"
    const val MEASUREMENTS_SHEET = "Measurements"
    const val ROUTINES_SHEET = "Routines"
    const val MEASUREMENT_APPEND_RANGE = "Measurements!A:AP"
    const val ROUTINE_APPEND_RANGE = "Routines!A:M"
    const val ROUTINES_RANGE = "Routines!A:M"
  }
}
