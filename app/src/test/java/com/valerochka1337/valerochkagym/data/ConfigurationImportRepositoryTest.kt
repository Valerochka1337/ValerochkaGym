package com.valerochka1337.valerochkagym.data

import android.app.Activity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.google.AppendValuesDto
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.BatchUpdateRequestDto
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.ImportResult
import com.valerochka1337.valerochkagym.data.google.SheetDto
import com.valerochka1337.valerochkagym.data.google.SheetPropertiesDto
import com.valerochka1337.valerochkagym.data.google.SheetsApi
import com.valerochka1337.valerochkagym.data.google.SpreadsheetDto
import com.valerochka1337.valerochkagym.data.google.TokenResult
import com.valerochka1337.valerochkagym.data.google.UpdateValuesDto
import com.valerochka1337.valerochkagym.data.google.ValueRangeDto
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepositoryImpl
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.ExerciseSheetRecord
import com.valerochka1337.valerochkagym.domain.ExerciseSheetRowMapper
import com.valerochka1337.valerochkagym.domain.GymSheetRecord
import com.valerochka1337.valerochkagym.domain.GymSheetRowMapper
import com.valerochka1337.valerochkagym.domain.RoutineGymsSheetRecord
import com.valerochka1337.valerochkagym.domain.RoutineGymsSheetRowMapper
import com.valerochka1337.valerochkagym.domain.RoutineRowMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationImportRepositoryTest : RoomDaoTest() {

    @Test
    fun `legacy spreadsheet without configuration sheets remains compatible`() = runTest {
        val api = FakeSheetsApi(
            sheets = listOf(ROUTINES_SHEET),
            valuesByRange = mapOf(ROUTINES_RANGE to listOf(RoutineRowMapper.HEADER_ROW)),
        )

        assertEquals(ImportResult.NothingToImport, repository(api).importAll())
        assertEquals(listOf(ROUTINES_RANGE), api.requestedRanges)
        assertEquals(0, tableCount("exercises"))
        assertEquals(0, tableCount("gyms"))
        assertEquals(0, tableCount("routine_gyms"))
    }

    @Test
    fun `restores exercise then gym then routine gym links from configuration sheets`() = runTest {
        val exerciseRows = ExerciseSheetRowMapper.rows(
            ExerciseSheetRecord.Snapshot(
                syncId = EXERCISE_ID,
                updatedAt = 100,
                name = "Жим в тренажёре",
                muscleGroup = MuscleGroup.CHEST,
                type = ExerciseType.STRENGTH,
                isCustom = true,
                muscleLoads = linkedMapOf(Muscle.CHEST to 100, Muscle.TRICEPS to 65),
            ),
        ).map(::stringRow)
        val gymRows = GymSheetRowMapper.rows(
            GymSheetRecord.Snapshot(
                syncId = GYM_ID,
                updatedAt = 200,
                name = "Основной зал",
                exerciseSyncIds = setOf(EXERCISE_ID),
            ),
        ).map(::stringRow)
        val routineGymRows = RoutineGymsSheetRowMapper.rows(
            RoutineGymsSheetRecord.Snapshot(
                routineSyncId = ROUTINE_ID,
                updatedAt = 300,
                gymSyncIds = setOf(GYM_ID),
            ),
        ).map(::stringRow)
        val api = FakeSheetsApi(
            sheets = listOf(
                ExerciseSheetRowMapper.SHEET_NAME,
                GymSheetRowMapper.SHEET_NAME,
                ROUTINES_SHEET,
                RoutineGymsSheetRowMapper.SHEET_NAME,
            ),
            valuesByRange = mapOf(
                ExerciseSheetRowMapper.RANGE to listOf(ExerciseSheetRowMapper.HEADER_ROW) + exerciseRows,
                GymSheetRowMapper.RANGE to listOf(GymSheetRowMapper.HEADER_ROW) + gymRows,
                ROUTINES_RANGE to listOf(
                    RoutineRowMapper.HEADER_ROW,
                    listOf(
                        ROUTINE_ID,
                        "300",
                        "false",
                        "Верх тела",
                        "",
                        "0",
                        "Старое имя до переименования",
                        "CHEST",
                        "STRENGTH",
                        "90",
                        "[]",
                        EXERCISE_ID,
                    ),
                ),
                RoutineGymsSheetRowMapper.RANGE to
                    listOf(RoutineGymsSheetRowMapper.HEADER_ROW) + routineGymRows,
            ),
        )

        val result = repository(api).importAll()

        assertEquals(
            ImportResult.Success(
                imported = 0,
                importedRoutines = 1,
                importedExercises = 1,
                importedGyms = 1,
                importedRoutineGyms = 1,
            ),
            result,
        )
        val exercise = db.exerciseDao().getAllOnce().single()
        assertEquals(EXERCISE_ID, exercise.syncId)
        assertEquals("Жим в тренажёре", exercise.name)
        assertEquals(MuscleGroup.CHEST, exercise.muscleGroup)
        assertEquals(ExerciseType.STRENGTH, exercise.type)
        assertEquals(true, exercise.isCustom)
        assertEquals(
            mapOf(Muscle.CHEST to 100, Muscle.TRICEPS to 65),
            db.exerciseMuscleDao().getForExercise(exercise.id).associate { it.muscle to it.contribution },
        )

        val gym = db.gymDao().getGyms().single()
        assertEquals(GYM_ID, gym.syncId)
        assertEquals(listOf(exercise.id), db.gymDao().getGymExerciseIds(gym.id))

        val routine = db.routineDao().observeRoutinesFull().first().single()
        assertEquals(ROUTINE_ID, routine.routine.syncId)
        assertEquals(exercise.id, routine.exercises.single().exercise.id)
        assertEquals(listOf(GYM_ID), routine.gyms.map(GymEntity::syncId))
    }

    @Test
    fun `missing exercise reference skips gym snapshot without mutating gyms`() = runTest {
        val gymRows = GymSheetRowMapper.rows(
            GymSheetRecord.Snapshot(
                syncId = GYM_ID,
                updatedAt = 200,
                name = "Повреждённый зал",
                exerciseSyncIds = setOf(MISSING_ID),
            ),
        ).map(::stringRow)
        val api = FakeSheetsApi(
            sheets = listOf(GymSheetRowMapper.SHEET_NAME),
            valuesByRange = mapOf(
                GymSheetRowMapper.RANGE to listOf(GymSheetRowMapper.HEADER_ROW) + gymRows,
            ),
        )

        val result = repository(api).importAll()

        assertEquals(ImportResult.Success(imported = 0, skippedRows = 1), result)
        assertEquals(0, tableCount("gyms"))
        assertEquals(0, tableCount("gym_exercises"))
    }

    @Test
    fun `missing gym reference skips routine snapshot and preserves existing links`() = runTest {
        val exerciseId = db.exerciseDao().insert(
            ExerciseEntity(
                syncId = EXERCISE_ID,
                updatedAt = 100,
                name = "Жим",
                muscleGroup = MuscleGroup.CHEST,
                type = ExerciseType.STRENGTH,
            ),
        )
        val existingGymId = db.gymDao().insertGym(
            GymEntity(syncId = GYM_ID, updatedAt = 100, name = "Основной зал"),
        )
        db.gymDao().replaceGymExercises(existingGymId, listOf(exerciseId))
        val routineId = db.routineDao().upsertRoutine(
            RoutineEntity(syncId = ROUTINE_ID, updatedAt = 100, name = "Верх тела"),
        )
        db.routineDao().replaceRoutineExercises(
            routineId,
            listOf(
                RoutineExerciseEntity(
                    routineId = routineId,
                    exerciseId = exerciseId,
                    position = 0,
                ),
            ),
        )
        db.gymDao().replaceRoutineGyms(routineId, listOf(existingGymId))
        val routineGymRows = RoutineGymsSheetRowMapper.rows(
            RoutineGymsSheetRecord.Snapshot(
                routineSyncId = ROUTINE_ID,
                updatedAt = 200,
                gymSyncIds = setOf(MISSING_ID),
            ),
        ).map(::stringRow)
        val api = FakeSheetsApi(
            sheets = listOf(RoutineGymsSheetRowMapper.SHEET_NAME),
            valuesByRange = mapOf(
                RoutineGymsSheetRowMapper.RANGE to
                    listOf(RoutineGymsSheetRowMapper.HEADER_ROW) + routineGymRows,
            ),
        )

        val result = repository(api).importAll()

        assertEquals(ImportResult.Success(imported = 0, skippedRows = 1), result)
        assertEquals(
            listOf(GYM_ID),
            db.gymDao().getGymsForRoutine(routineId).map(GymEntity::syncId),
        )
    }

    @Test
    fun `remote gym narrowing rolls back when it conflicts with a linked routine`() = runTest {
        val exerciseId = db.exerciseDao().insert(
            ExerciseEntity(
                syncId = EXERCISE_ID,
                updatedAt = 100,
                name = "Жим",
                muscleGroup = MuscleGroup.CHEST,
                type = ExerciseType.STRENGTH,
            ),
        )
        val gymId = db.gymDao().insertGym(
            GymEntity(syncId = GYM_ID, updatedAt = 100, name = "Основной зал"),
        )
        db.gymDao().replaceGymExercises(gymId, listOf(exerciseId))
        val routineId = db.routineDao().upsertRoutine(
            RoutineEntity(syncId = ROUTINE_ID, updatedAt = 100, name = "Верх тела"),
        )
        db.routineDao().replaceRoutineExercises(
            routineId,
            listOf(RoutineExerciseEntity(routineId = routineId, exerciseId = exerciseId, position = 0)),
        )
        db.gymDao().replaceRoutineGyms(routineId, listOf(gymId))
        val gymRows = GymSheetRowMapper.rows(
            GymSheetRecord.Snapshot(
                syncId = GYM_ID,
                updatedAt = 200,
                name = "Основной зал после правки",
                exerciseSyncIds = emptySet(),
            ),
        ).map(::stringRow)
        val api = FakeSheetsApi(
            sheets = listOf(GymSheetRowMapper.SHEET_NAME),
            valuesByRange = mapOf(
                GymSheetRowMapper.RANGE to listOf(GymSheetRowMapper.HEADER_ROW) + gymRows,
            ),
        )

        val result = repository(api).importAll()

        assertTrue(result is ImportResult.Failure)
        assertEquals("Основной зал", db.gymDao().getGym(gymId)?.name)
        assertEquals(listOf(exerciseId), db.gymDao().getGymExerciseIds(gymId))
    }

    private fun repository(api: FakeSheetsApi): WorkoutImportRepositoryImpl =
        WorkoutImportRepositoryImpl(
            api = api,
            googleAuth = FakeGoogleAuth,
            settingsRepository = SettingsRepository(
                FakeDataStore(
                    mutablePreferencesOf(stringPreferencesKey("spreadsheet_id") to SPREADSHEET_ID),
                ),
            ),
            database = db,
            workoutDao = db.workoutDao(),
            exerciseDao = db.exerciseDao(),
            exerciseMuscleDao = db.exerciseMuscleDao(),
            gymDao = db.gymDao(),
        )

    private class FakeSheetsApi(
        private val sheets: List<String>,
        private val valuesByRange: Map<String, List<List<String>>>,
    ) : SheetsApi {
        val requestedRanges = mutableListOf<String>()

        override suspend fun getSpreadsheet(
            bearer: String,
            spreadsheetId: String,
            fields: String,
        ): SpreadsheetDto = SpreadsheetDto(sheets.map { SheetDto(SheetPropertiesDto(it)) })

        override suspend fun batchUpdate(
            bearer: String,
            spreadsheetId: String,
            body: BatchUpdateRequestDto,
        ): JsonElement = JsonNull

        override suspend fun getValues(
            bearer: String,
            spreadsheetId: String,
            range: String,
        ): ValueRangeDto {
            requestedRanges += range
            return ValueRangeDto(valuesByRange[range]?.takeIf(List<*>::isNotEmpty))
        }

        override suspend fun appendValues(
            bearer: String,
            spreadsheetId: String,
            range: String,
            body: AppendValuesDto,
            valueInputOption: String,
            insertDataOption: String,
        ): JsonElement = JsonNull

        override suspend fun updateValues(
            bearer: String,
            spreadsheetId: String,
            range: String,
            body: UpdateValuesDto,
            valueInputOption: String,
        ): JsonElement = JsonNull
    }

    private data object FakeGoogleAuth : GoogleAuth {
        override suspend fun signIn(activity: Activity): Result<String> = Result.success("user@example.com")
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
        const val ROUTINES_SHEET = "Routines"
        const val ROUTINES_RANGE = "Routines!A:L"
        const val EXERCISE_ID = "00000000-0000-0000-0000-000000000001"
        const val GYM_ID = "10000000-0000-0000-0000-000000000001"
        const val ROUTINE_ID = "20000000-0000-0000-0000-000000000001"
        const val MISSING_ID = "30000000-0000-0000-0000-000000000001"
    }
}

private fun stringRow(row: List<Any?>): List<String> = row.map { it?.toString().orEmpty() }
