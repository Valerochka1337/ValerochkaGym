package com.valerochka1337.valerochkagym.data

import android.app.Activity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.db.CanonicalExerciseRegistry
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.google.AppendValuesDto
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.BatchUpdateRequestDto
import com.valerochka1337.valerochkagym.data.google.ConfigurationSheetsRepositoryImpl
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.SheetDto
import com.valerochka1337.valerochkagym.data.google.SheetPropertiesDto
import com.valerochka1337.valerochkagym.data.google.SheetsApi
import com.valerochka1337.valerochkagym.data.google.SpreadsheetDto
import com.valerochka1337.valerochkagym.data.google.TokenResult
import com.valerochka1337.valerochkagym.data.google.UpdateValuesDto
import com.valerochka1337.valerochkagym.data.google.UploadResult
import com.valerochka1337.valerochkagym.data.google.ValueRangeDto
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.ExerciseSheetRowMapper
import com.valerochka1337.valerochkagym.domain.GymSheetRowMapper
import com.valerochka1337.valerochkagym.domain.RoutineGymsSheetRowMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigurationSheetsRepositoryTest : RoomDaoTest() {

  @Test
  fun `known legacy exercise header is extended before canonical role append`() = runTest {
    val exercise =
        ExerciseEntity(
            syncId = EXERCISE_ID,
            updatedAt = 200,
            name = "Своё",
            muscleGroup = MuscleGroup.CHEST,
            type = ExerciseType.STRENGTH,
            isCustom = true,
        )
    val id = db.exerciseDao().insert(exercise)
    db.exerciseMuscleDao().upsertAll(listOf(ExerciseMuscleEntity(id, Muscle.UPPER_CHEST, 100)))
    val api =
        FakeSheetsApi().apply {
          seed(ExerciseSheetRowMapper.RANGE, listOf(ExerciseSheetRowMapper.HEADER_ROW.dropLast(1)))
        }

    assertEquals(UploadResult.Success, repository(api).uploadExercise(EXERCISE_ID))
    assertEquals(1, api.updated.size)
    assertEquals(listOf(ExerciseSheetRowMapper.HEADER_ROW), api.updated.single())
    assertEquals("2", api.appended.single().single().last())
  }

  @Test
  fun `built in registry exercises are never exported to the custom cloud catalog`() = runTest {
    val builtIn = CanonicalExerciseRegistry.entries.first().exercise.copy(updatedAt = 200)
    db.exerciseDao().insert(builtIn)
    val api = FakeSheetsApi()

    assertEquals(UploadResult.Success, repository(api).uploadExercise(builtIn.syncId))
    assertEquals(emptyList<List<List<String>>>(), api.appended)
    assertEquals(emptyList<String>(), api.appendRanges)
  }

  @Test
  fun `uploads a complete exercise snapshot with header and deduplicates its version`() = runTest {
    val exercise =
        ExerciseEntity(
            syncId = EXERCISE_ID,
            updatedAt = 200,
            name = "Жим в тренажёре",
            muscleGroup = MuscleGroup.CHEST,
            type = ExerciseType.STRENGTH,
            isCustom = true,
        )
    val localId = db.exerciseDao().insert(exercise)
    db.exerciseMuscleDao()
        .upsertAll(
            listOf(
                ExerciseMuscleEntity(localId, Muscle.UPPER_CHEST, 100),
                ExerciseMuscleEntity(localId, Muscle.TRICEPS, 50),
            ),
        )
    val api = FakeSheetsApi()
    val repository = repository(api)

    assertEquals(UploadResult.Success, repository.uploadExercise(EXERCISE_ID))
    assertEquals(UploadResult.Success, repository.uploadExercise(EXERCISE_ID))

    assertEquals(listOf(ExerciseSheetRowMapper.RANGE), api.appendRanges)
    assertEquals(
        listOf(
            ExerciseSheetRowMapper.HEADER_ROW,
            listOf(
                EXERCISE_ID,
                "200",
                "false",
                "Жим в тренажёре",
                "CHEST",
                "STRENGTH",
                "true",
                "UPPER_CHEST",
                "100",
                "2",
            ),
            listOf(
                EXERCISE_ID,
                "200",
                "false",
                "Жим в тренажёре",
                "CHEST",
                "STRENGTH",
                "true",
                "TRICEPS",
                "50",
                "2",
            ),
        ),
        api.appended.single(),
    )
  }

  @Test
  fun `uploads a complete gym snapshot with header and deduplicates its version`() = runTest {
    val firstExerciseId =
        db.exerciseDao()
            .insert(
                ExerciseEntity(
                    syncId = EXERCISE_ID,
                    updatedAt = 100,
                    name = "Жим",
                    muscleGroup = MuscleGroup.CHEST,
                    type = ExerciseType.STRENGTH,
                ),
            )
    val secondExerciseId =
        db.exerciseDao()
            .insert(
                ExerciseEntity(
                    syncId = SECOND_EXERCISE_ID,
                    updatedAt = 100,
                    name = "Тяга",
                    muscleGroup = MuscleGroup.BACK,
                    type = ExerciseType.STRENGTH,
                ),
            )
    val gymId =
        db.gymDao()
            .insertGym(
                GymEntity(syncId = GYM_ID, updatedAt = 300, name = "Основной зал"),
            )
    db.gymDao().replaceGymExercises(gymId, listOf(secondExerciseId, firstExerciseId))
    val api = FakeSheetsApi()
    val repository = repository(api)

    assertEquals(UploadResult.Success, repository.uploadGym(GYM_ID))
    assertEquals(UploadResult.Success, repository.uploadGym(GYM_ID))

    assertEquals(listOf(GymSheetRowMapper.RANGE), api.appendRanges)
    assertEquals(
        listOf(
            GymSheetRowMapper.HEADER_ROW,
            listOf(GYM_ID, "300", "false", "Основной зал", EXERCISE_ID),
            listOf(GYM_ID, "300", "false", "Основной зал", SECOND_EXERCISE_ID),
        ),
        api.appended.single(),
    )
  }

  @Test
  fun `uploads a complete routine gyms snapshot with header and deduplicates its version`() =
      runTest {
        val firstGymId =
            db.gymDao()
                .insertGym(
                    GymEntity(syncId = GYM_ID, updatedAt = 100, name = "Основной зал"),
                )
        val secondGymId =
            db.gymDao()
                .insertGym(
                    GymEntity(syncId = SECOND_GYM_ID, updatedAt = 100, name = "Запасной зал"),
                )
        val routineId =
            db.routineDao()
                .upsertRoutine(
                    RoutineEntity(syncId = ROUTINE_ID, updatedAt = 400, name = "Верх тела"),
                )
        db.gymDao().replaceRoutineGyms(routineId, listOf(secondGymId, firstGymId))
        val api = FakeSheetsApi()
        val repository = repository(api)

        assertEquals(UploadResult.Success, repository.uploadRoutineGyms(ROUTINE_ID))
        assertEquals(UploadResult.Success, repository.uploadRoutineGyms(ROUTINE_ID))

        assertEquals(listOf(RoutineGymsSheetRowMapper.RANGE), api.appendRanges)
        assertEquals(
            listOf(
                RoutineGymsSheetRowMapper.HEADER_ROW,
                listOf(ROUTINE_ID, "400", "false", GYM_ID),
                listOf(ROUTINE_ID, "400", "false", SECOND_GYM_ID),
            ),
            api.appended.single(),
        )
      }

  private fun repository(api: FakeSheetsApi): ConfigurationSheetsRepositoryImpl =
      ConfigurationSheetsRepositoryImpl(
          api = api,
          googleAuth = FakeGoogleAuth,
          settingsRepository =
              SettingsRepository(
                  FakeDataStore(
                      mutablePreferencesOf(
                          stringPreferencesKey("spreadsheet_id") to SPREADSHEET_ID
                      ),
                  ),
              ),
          exerciseDao = db.exerciseDao(),
          exerciseMuscleDao = db.exerciseMuscleDao(),
          gymDao = db.gymDao(),
          routineDao = db.routineDao(),
      )

  private class FakeSheetsApi : SheetsApi {
    private val sheets = mutableListOf<String>()
    private val valuesByRange = mutableMapOf<String, MutableList<List<String>>>()

    val appended = mutableListOf<List<List<String>>>()
    val appendRanges = mutableListOf<String>()
    val updated = mutableListOf<List<List<String>>>()

    fun seed(range: String, rows: List<List<String>>) {
      valuesByRange.getOrPut(range, ::mutableListOf).addAll(rows)
    }

    override suspend fun getSpreadsheet(
        bearer: String,
        spreadsheetId: String,
        fields: String,
    ): SpreadsheetDto =
        SpreadsheetDto(
            sheets.mapIndexed { index, title ->
              SheetDto(SheetPropertiesDto(title = title, index = index, sheetId = index))
            },
        )

    override suspend fun batchUpdate(
        bearer: String,
        spreadsheetId: String,
        body: BatchUpdateRequestDto,
    ): JsonElement {
      body.requests
          .mapNotNull { it.addSheet?.properties?.title }
          .filterNot(sheets::contains)
          .forEach(sheets::add)
      return JsonNull
    }

    override suspend fun getValues(
        bearer: String,
        spreadsheetId: String,
        range: String,
    ): ValueRangeDto = ValueRangeDto(valuesByRange[range]?.takeIf(List<*>::isNotEmpty))

    override suspend fun appendValues(
        bearer: String,
        spreadsheetId: String,
        range: String,
        body: AppendValuesDto,
        valueInputOption: String,
        insertDataOption: String,
    ): JsonElement {
      val rows =
          body.values.map { row ->
            (row as JsonArray).map { cell -> (cell as JsonPrimitive).content }
          }
      appendRanges += range
      appended += rows
      valuesByRange.getOrPut(range, ::mutableListOf).addAll(rows)
      return JsonNull
    }

    override suspend fun updateValues(
        bearer: String,
        spreadsheetId: String,
        range: String,
        body: UpdateValuesDto,
        valueInputOption: String,
    ): JsonElement {
      updated += body.values.map { row -> (row as JsonArray).map { (it as JsonPrimitive).content } }
      return JsonNull
    }
  }

  private data object FakeGoogleAuth : GoogleAuth {
    override suspend fun signIn(activity: Activity): Result<String> =
        Result.success("user@example.com")

    override suspend fun authorize(activity: Activity): AuthorizeOutcome = AuthorizeOutcome.Granted

    override suspend fun getAccessToken(): TokenResult = TokenResult.Success("token")

    override suspend fun signOut() = Unit
  }

  private class FakeDataStore(prefs: Preferences) : DataStore<Preferences> {
    private val state = MutableStateFlow(prefs)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
      state.value = transform(state.value)
      return state.value
    }
  }

  private companion object {
    const val SPREADSHEET_ID = "spreadsheet-id"
    const val EXERCISE_ID = "00000000-0000-0000-0000-000000000001"
    const val SECOND_EXERCISE_ID = "00000000-0000-0000-0000-000000000002"
    const val GYM_ID = "10000000-0000-0000-0000-000000000001"
    const val SECOND_GYM_ID = "10000000-0000-0000-0000-000000000002"
    const val ROUTINE_ID = "20000000-0000-0000-0000-000000000001"
  }
}
