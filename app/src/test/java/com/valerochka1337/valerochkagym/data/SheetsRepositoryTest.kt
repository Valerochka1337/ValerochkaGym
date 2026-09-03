package com.valerochka1337.valerochkagym.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.SheetsApi
import com.valerochka1337.valerochkagym.data.google.SheetsRepositoryImpl
import com.valerochka1337.valerochkagym.data.google.RemoteClearResult
import com.valerochka1337.valerochkagym.data.google.TokenResult
import com.valerochka1337.valerochkagym.data.google.UploadResult
import com.valerochka1337.valerochkagym.data.google.AppendValuesDto
import com.valerochka1337.valerochkagym.data.google.BatchUpdateRequestDto
import com.valerochka1337.valerochkagym.data.google.ClearValuesDto
import com.valerochka1337.valerochkagym.data.google.SheetDto
import com.valerochka1337.valerochkagym.data.google.SheetPropertiesDto
import com.valerochka1337.valerochkagym.data.google.SpreadsheetDto
import com.valerochka1337.valerochkagym.data.google.ValueRangeDto
import com.valerochka1337.valerochkagym.data.google.UpdateValuesDto
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.data.measurements.MeasurementRepository
import com.valerochka1337.valerochkagym.data.measurements.MeasurementSnapshotCodec
import com.valerochka1337.valerochkagym.domain.RoutineRowMapper
import com.valerochka1337.valerochkagym.domain.WorkoutRowMapper
import com.valerochka1337.valerochkagym.domain.ExerciseSheetRowMapper
import com.valerochka1337.valerochkagym.domain.ExerciseVariantSheetRowMapper
import com.valerochka1337.valerochkagym.domain.GymSheetRowMapper
import com.valerochka1337.valerochkagym.domain.RoutineGymsSheetRowMapper
import com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementRowMapper
import com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementRowParser
import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val api = FakeSheetsApi(
            sheets = mutableListOf(ROUTINES_SHEET),
            routineRows = (listOf(RoutineRowMapper.HEADER_ROW) + RoutineRowMapper.rows(snapshot))
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
        val legacyRows = (listOf(RoutineRowMapper.LEGACY_HEADER_ROW) +
            RoutineRowMapper.rows(snapshot).map { it.dropLast(2) })
            .map { row -> row.map { it?.toString().orEmpty() } }
            .toMutableList()
        val api = FakeSheetsApi(
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
    fun `confirmed workouts configuration clear validates every header then clears exact managed ranges`() = runTest {
        val api = FakeSheetsApi(
            sheets = mutableListOf(
                WORKOUTS_SHEET,
                ROUTINES_SHEET,
                "Exercises",
                "ExerciseVariants",
                "Gyms",
                "RoutineGyms",
                "Personal notes",
            ),
        ).apply {
            sheetHeaders.putAll(
                mapOf(
                    WORKOUTS_SHEET to (WorkoutRowMapper.HEADER_ROW + "custom_workout_column"),
                    ROUTINES_SHEET to (RoutineRowMapper.HEADER_ROW + "custom_routine_column"),
                    "Exercises" to (ExerciseSheetRowMapper.HEADER_ROW + "custom_exercise_column"),
                    "ExerciseVariants" to ExerciseVariantSheetRowMapper.HEADER_ROW,
                    "Gyms" to GymSheetRowMapper.HEADER_ROW,
                    "RoutineGyms" to RoutineGymsSheetRowMapper.HEADER_ROW,
                ),
            )
        }

        val result = repository(api).clearWorkoutsAndConfigurationAfterConfirmation()

        assertEquals(
            RemoteClearResult.Success(
                listOf(
                    "Workouts!A2:S",
                    "Routines!A2:M",
                    "Exercises!A2:I",
                    "ExerciseVariants!A2:F",
                    "Gyms!A2:E",
                    "RoutineGyms!A2:D",
                ),
            ),
            result,
        )
        assertEquals(
            listOf(
                "Workouts!A2:S",
                "Routines!A2:M",
                "Exercises!A2:I",
                "ExerciseVariants!A2:F",
                "Gyms!A2:E",
                "RoutineGyms!A2:D",
            ),
            api.clears,
        )
        assertTrue(api.addedSheets.isEmpty())
    }

    @Test
    fun `incompatible configuration header prevents every destructive clear`() = runTest {
        val api = FakeSheetsApi(sheets = mutableListOf(WORKOUTS_SHEET, "ExerciseVariants")).apply {
            sheetHeaders[WORKOUTS_SHEET] = WorkoutRowMapper.HEADER_ROW
            sheetHeaders["ExerciseVariants"] = listOf("user_owned", "column")
        }

        assertTrue(repository(api).clearWorkoutsAndConfigurationAfterConfirmation() is RemoteClearResult.Failure)
        assertTrue(api.clears.isEmpty())
        assertTrue(api.addedSheets.isEmpty())
    }

    @Test
    fun `configuration clear uses recognised legacy widths and ignores absent sheets`() = runTest {
        val api = FakeSheetsApi(sheets = mutableListOf(WORKOUTS_SHEET, ROUTINES_SHEET)).apply {
            sheetHeaders[WORKOUTS_SHEET] = WorkoutRowMapper.HEADER_ROW.take(14) + "user_column"
            sheetHeaders[ROUTINES_SHEET] = RoutineRowMapper.LEGACY_HEADER_ROW + "user_column"
        }

        assertEquals(
            RemoteClearResult.Success(listOf("Workouts!A2:N", "Routines!A2:K")),
            repository(api).clearWorkoutsAndConfigurationAfterConfirmation(),
        )
        assertEquals(listOf("Workouts!A2:N", "Routines!A2:K"), api.clears)
        assertTrue(api.addedSheets.isEmpty())

        val stableRoutine = FakeSheetsApi(sheets = mutableListOf(ROUTINES_SHEET)).apply {
            sheetHeaders[ROUTINES_SHEET] = RoutineRowMapper.STABLE_EXERCISE_HEADER_ROW + "user_column"
        }
        assertEquals(
            RemoteClearResult.Success(listOf("Routines!A2:L")),
            repository(stableRoutine).clearWorkoutsAndConfigurationAfterConfirmation(),
        )
        assertEquals(listOf("Routines!A2:L"), stableRoutine.clears)

        val absent = FakeSheetsApi()
        assertEquals(
            RemoteClearResult.Success(emptyList()),
            repository(absent).clearWorkoutsAndConfigurationAfterConfirmation(),
        )
        assertTrue(absent.clears.isEmpty())
        assertTrue(absent.addedSheets.isEmpty())
    }

    @Test
    fun `configuration clear reports partial remote failure without changing local measurement or settings`() = runTest {
        seedMeasurement()
        val settings = settingsRepository(SPREADSHEET_ID)
        val beforeSettings = settings.settings.first()
        val api = FakeSheetsApi(sheets = mutableListOf(WORKOUTS_SHEET, ROUTINES_SHEET)).apply {
            sheetHeaders[WORKOUTS_SHEET] = WorkoutRowMapper.HEADER_ROW
            sheetHeaders[ROUTINES_SHEET] = RoutineRowMapper.HEADER_ROW
            failClearAt = 2
        }

        val result = repository(api, settings = settings).clearWorkoutsAndConfigurationAfterConfirmation()

        assertEquals(
            RemoteClearResult.Failure(
                "Нет сети при очистке Google Sheets",
                listOf("Workouts!A2:S"),
                "Routines!A2:M",
            ),
            result,
        )
        assertEquals(listOf("Workouts!A2:S"), api.clears)
        assertEquals(beforeSettings, settings.settings.first())
        assertEquals(UploadStatus.PENDING, measurementUploadStatus())
    }

    @Test fun `configuration clear revalidates a changed third header before destructive request`() = runTest {
        val api = FakeSheetsApi(sheets = mutableListOf(WORKOUTS_SHEET, ROUTINES_SHEET, "Exercises", "ExerciseVariants")).apply {
            sheetHeaders[WORKOUTS_SHEET] = WorkoutRowMapper.HEADER_ROW
            sheetHeaders[ROUTINES_SHEET] = RoutineRowMapper.HEADER_ROW
            sheetHeaders["Exercises"] = ExerciseSheetRowMapper.HEADER_ROW
            sheetHeaders["ExerciseVariants"] = ExerciseVariantSheetRowMapper.HEADER_ROW
            mutateHeaderAfterClear = 1 to ("Exercises" to listOf("user_owned", "column"))
        }

        assertEquals(
            RemoteClearResult.Failure(
                "Заголовок листа Exercises изменён вручную — очистка отменена",
                clearedRanges = listOf("Workouts!A2:S", "Routines!A2:M"),
                failedRange = "Exercises!A2:I",
                remainingRanges = listOf("ExerciseVariants!A2:F"),
            ),
            repository(api).clearWorkoutsAndConfigurationAfterConfirmation(),
        )
        assertEquals(listOf("Workouts!A2:S", "Routines!A2:M"), api.clears)
    }

    @Test
    fun `measurement clear keeps legacy and user columns and never creates missing sheets`() = runTest {
        val earlyLegacy = FakeSheetsApi(sheets = mutableListOf(MEASUREMENTS_SHEET)).apply {
            sheetHeaders[MEASUREMENTS_SHEET] = BodyMeasurementRowMapper.LEGACY_HEADER_ROW.take(14) + "my_column"
        }
        assertEquals(RemoteClearResult.Success(listOf("Measurements!A2:N")), repository(earlyLegacy).clearMeasurementsAfterConfirmation())
        assertEquals(listOf("Measurements!A2:N"), earlyLegacy.clears)
        assertTrue(earlyLegacy.addedSheets.isEmpty())
        assertEquals(listOf(MEASUREMENTS_SHEET), earlyLegacy.sheets)

        val fullLegacy = FakeSheetsApi(sheets = mutableListOf(MEASUREMENTS_SHEET)).apply {
            sheetHeaders[MEASUREMENTS_SHEET] = BodyMeasurementRowMapper.LEGACY_HEADER_ROW + "my_column"
        }
        assertEquals(RemoteClearResult.Success(listOf("Measurements!A2:AP")), repository(fullLegacy).clearMeasurementsAfterConfirmation())
        assertEquals(listOf("Measurements!A2:AP"), fullLegacy.clears)

        val modern = FakeSheetsApi(sheets = mutableListOf(MEASUREMENTS_SHEET)).apply {
            sheetHeaders[MEASUREMENTS_SHEET] = BodyMeasurementRowMapper.HEADER_ROW + "my_column"
        }
        assertEquals(RemoteClearResult.Success(listOf("Measurements!A2:AY")), repository(modern).clearMeasurementsAfterConfirmation())
        assertEquals(listOf("Measurements!A2:AY"), modern.clears)

        val absent = FakeSheetsApi(sheets = mutableListOf("Personal notes"))
        assertEquals(RemoteClearResult.Success(emptyList()), repository(absent).clearMeasurementsAfterConfirmation())
        assertTrue(absent.clears.isEmpty())
        assertTrue(absent.addedSheets.isEmpty())
        assertFalse(absent.sheets.contains(WORKOUTS_SHEET))
    }

    @Test
    fun `unknown measurement header stops clear without local mutation`() = runTest {
        seedMeasurement()
        val api = FakeSheetsApi(sheets = mutableListOf(MEASUREMENTS_SHEET)).apply {
            sheetHeaders[MEASUREMENTS_SHEET] = listOf("user_owned", "column")
        }

        assertTrue(repository(api).clearMeasurementsAfterConfirmation() is RemoteClearResult.Failure)
        assertTrue(api.clears.isEmpty())
        assertEquals(UploadStatus.PENDING, measurementUploadStatus())
    }

    @Test
    fun `measurement export creates Measurements directly after Workouts and appends its header`() = runTest {
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
        assertEquals("", batch[1][6]) // versioned sync rows never export calculated fat mass
        assertEquals(UploadStatus.UPLOADED, measurementUploadStatus())
    }

    @Test
    fun `legacy Measurements header receives InBody columns after N without rewriting its rows`() = runTest {
        seedMeasurement()
        val legacyHeader = BodyMeasurementRowMapper.HEADER_ROW.take(14)
        val api = FakeSheetsApi(
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
        assertEquals(listOf(listOf(BodyMeasurementRowMapper.HEADER_ROW.drop(14))), api.headerUpdates)
        assertEquals(MEASUREMENT_APPEND_RANGE, api.appendRanges.single())
        assertEquals(1, api.appended.single().size)
        assertEquals(MEASUREMENT_ID, api.appended.single().single().first())
        assertEquals(UploadStatus.UPLOADED, measurementUploadStatus())
    }

    @Test
    fun `legacy Measurements header inserts nine managed columns before user AQ column`() = runTest {
        seedMeasurement()
        val api = FakeSheetsApi(
            sheets = mutableListOf(WORKOUTS_SHEET, MEASUREMENTS_SHEET),
            measurementColumnA = mutableListOf("measurement_id"),
            measurementHeader = (BodyMeasurementRowMapper.LEGACY_HEADER_ROW + "my_user_column").toMutableList(),
        )

        assertEquals(UploadResult.Success, repository(api).uploadMeasurement(MEASUREMENT_ID))

        val inserted = api.insertedDimensions.single()
        assertEquals(42, inserted.range.startIndex)
        assertEquals(51, inserted.range.endIndex)
        assertEquals(listOf(listOf(BodyMeasurementRowMapper.HEADER_ROW.drop(42))), api.headerUpdates)
    }

    @Test
    fun `interim AU Measurements header inserts four condition columns before user AV column`() = runTest {
        seedMeasurement()
        val api = FakeSheetsApi(
            sheets = mutableListOf(WORKOUTS_SHEET, MEASUREMENTS_SHEET),
            measurementColumnA = mutableListOf("measurement_id"),
            measurementHeader = (BodyMeasurementRowMapper.INTERIM_HEADER_ROW + "my_user_column").toMutableList(),
        )

        assertEquals(UploadResult.Success, repository(api).uploadMeasurement(MEASUREMENT_ID))

        val inserted = api.insertedDimensions.single()
        assertEquals(47, inserted.range.startIndex)
        assertEquals(51, inserted.range.endIndex)
        assertEquals(listOf(listOf(BodyMeasurementRowMapper.HEADER_ROW.takeLast(4))), api.headerUpdates)
    }

    @Test
    fun `already appended measurement id is idempotent and does not append again`() = runTest {
        seedMeasurement()
        val api = FakeSheetsApi(
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
        val api = FakeSheetsApi(sheets = mutableListOf(WORKOUTS_SHEET, MEASUREMENTS_SHEET), failGetValues = httpException(401))

        val result = repository(api).uploadMeasurement(MEASUREMENT_ID)

        assertEquals(UploadResult.PermanentFailure("Нет доступа к таблице — проверьте вход и права"), result)
        assertEquals(UploadStatus.FAILED, measurementUploadStatus())
    }

    @Test
    fun `delayed v1 snapshot uploads its immutable row and does not mark local v2 uploaded`() = runTest {
        val measurements = MeasurementRepository(db)
        measurements.save(BodyMeasurementEntity("versioned", 100, weightKg = 70.0), now = 100)
        val v1 = db.healthSyncOutboxDao().pending(HealthSyncCategory.MEASUREMENTS).single()
        measurements.save(BodyMeasurementEntity("versioned", 200, weightKg = 69.0), now = 200)
        val v2 = db.healthSyncOutboxDao().pending(HealthSyncCategory.MEASUREMENTS).last()
        val api = FakeSheetsApi(
            sheets = mutableListOf(WORKOUTS_SHEET, MEASUREMENTS_SHEET),
            measurementRows = mutableListOf(BodyMeasurementRowMapper.HEADER_ROW),
        )

        assertEquals(UploadResult.Success, repository(api).uploadMeasurementSnapshot(v1))

        assertEquals("70.0", api.appended.single().single()[3])
        assertEquals("1", api.appended.single().single()[42])
        assertEquals(UploadStatus.PENDING, db.bodyMeasurementDao().getById("versioned")!!.uploadStatus)

        assertEquals(UploadResult.Success, repository(api).uploadMeasurementSnapshot(v2))
        assertEquals("69.0", api.appended.last().single()[3])
        assertEquals(UploadStatus.UPLOADED, db.bodyMeasurementDao().getById("versioned")!!.uploadStatus)
    }

    @Test
    fun `literal migration v1 nullable snapshot syncs blank primary fields and reimports without conflict`() = runTest {
        val measurement = BodyMeasurementEntity(
            "nullable-primary", 100,
            weightKg = 70.0, bodyFatPercentage = 20.0, bodyFatMassKg = null,
            waistHipRatio = null, waistCm = 75.0, hipsCm = 100.0,
        )
        val snapshot = HealthSyncOutboxEntity(
            HealthSyncCategory.MEASUREMENTS, measurement.id, 1,
            actualMigrationV1Payload, null, "nullable-primary:1", 100,
        )
        val api = FakeSheetsApi(
            sheets = mutableListOf(WORKOUTS_SHEET, MEASUREMENTS_SHEET),
            measurementRows = mutableListOf(BodyMeasurementRowMapper.HEADER_ROW),
        )

        assertEquals(UploadResult.Success, repository(api).uploadMeasurementSnapshot(snapshot))
        val row = api.appended.single().single()
        assertEquals("", row[6])
        assertEquals("", row[8])
        assertEquals(UploadResult.Success, repository(api).uploadMeasurementSnapshot(snapshot))
        val parsed = BodyMeasurementRowParser.parse(listOf(BodyMeasurementRowMapper.HEADER_ROW, row)).snapshots.single()
        assertEquals(snapshot.canonicalPayload, parsed.canonicalPayload)
        assertEquals(MeasurementSnapshotCodec.PayloadFormat.V1, parsed.payloadFormat)
        assertEquals("", row[47])
        assertEquals("", row[48])
        assertEquals("", row[49])
        val imported = MeasurementRepository(db)
        assertEquals(true, imported.applyImported(parsed))
        assertEquals(false, imported.applyImported(parsed))
        assertEquals(1, db.healthDao().measurementSnapshots("nullable-primary").size)
        assertEquals(snapshot.canonicalPayload, db.healthDao().measurementSnapshots("nullable-primary").single().canonicalPayload)
        assertEquals(0, db.healthDao().conflicts().size)
    }

    @Test
    fun `tombstone snapshot uploads its exact immutable values rather than an empty live row`() = runTest {
        val measurements = MeasurementRepository(db)
        measurements.save(BodyMeasurementEntity("deleted", 100, weightKg = 70.0, waistCm = 72.0), now = 100)
        measurements.delete("deleted", now = 200)
        val tombstone = db.healthSyncOutboxDao().pending(HealthSyncCategory.MEASUREMENTS).last()
        val api = FakeSheetsApi(
            sheets = mutableListOf(WORKOUTS_SHEET, MEASUREMENTS_SHEET),
            measurementRows = mutableListOf(BodyMeasurementRowMapper.HEADER_ROW),
        )

        assertEquals(UploadResult.Success, repository(api).uploadMeasurementSnapshot(tombstone))

        val row = api.appended.single().single()
        assertEquals("deleted", row[0])
        assertEquals("70.0", row[3])
        assertEquals("72.0", row[9])
        assertEquals("2", row[42])
        assertEquals("true", row[44])
        assertEquals(tombstone.payloadHash, row[45])
        assertEquals(tombstone.idempotencyKey, row[46])
    }

    @Test
    fun `matching remote snapshot is acknowledged while an equal divergent row becomes a conflict`() = runTest {
        val measurements = MeasurementRepository(db)
        measurements.save(BodyMeasurementEntity("matching", 100, weightKg = 70.0), now = 100)
        val snapshot = db.healthSyncOutboxDao().pending(HealthSyncCategory.MEASUREMENTS).single()
        val matchingApi = FakeSheetsApi(
            sheets = mutableListOf(WORKOUTS_SHEET, MEASUREMENTS_SHEET),
            measurementRows = mutableListOf(BodyMeasurementRowMapper.HEADER_ROW),
        )
        assertEquals(UploadResult.Success, repository(matchingApi).uploadMeasurementSnapshot(snapshot))
        assertEquals(1, matchingApi.appended.size)
        assertEquals(UploadResult.Success, repository(matchingApi).uploadMeasurementSnapshot(snapshot))
        assertEquals(1, matchingApi.appended.size)

        measurements.save(BodyMeasurementEntity("divergent", 100, weightKg = 70.0), now = 100)
        val divergent = db.healthSyncOutboxDao().pending(HealthSyncCategory.MEASUREMENTS)
            .single { it.syncId == "divergent" }
        val row = BodyMeasurementRowMapper.versionedRow(
            MeasurementRepository.canonicalPayload(BodyMeasurementEntity("divergent", 100, weightKg = 70.0))
                .let { com.valerochka1337.valerochkagym.data.measurements.MeasurementSnapshotCodec.decode(it)!!.measurement },
            divergent.version, divergent.createdAt, false, divergent.payloadHash, divergent.idempotencyKey,
        ).map { it?.toString().orEmpty() }.toMutableList()
        row[3] = "68.0"
        val divergentApi = FakeSheetsApi(
            sheets = mutableListOf(WORKOUTS_SHEET, MEASUREMENTS_SHEET),
            measurementRows = mutableListOf(BodyMeasurementRowMapper.HEADER_ROW, row),
        )

        assertEquals(UploadResult.PermanentFailure("Конфликт версии замера в таблице"), repository(divergentApi).uploadMeasurementSnapshot(divergent))
        assertTrue(divergentApi.appended.isEmpty())
        assertEquals(1, db.healthDao().conflicts().size)
        assertEquals(UploadStatus.PENDING, db.bodyMeasurementDao().getById("divergent")!!.uploadStatus)
    }

    @Test
    fun `divergent v2 condition fields remain in the durable remote conflict payload`() = runTest {
        val measurements = MeasurementRepository(db)
        measurements.save(BodyMeasurementEntity("conditions", 100, afterMeal = true, conditionNote = "еда"), now = 100)
        val snapshot = db.healthSyncOutboxDao().pending(HealthSyncCategory.MEASUREMENTS).single()
        val decoded = MeasurementSnapshotCodec.decode(snapshot.canonicalPayload)!!
        val remote = BodyMeasurementRowMapper.versionedRow(
            decoded.measurement, snapshot.version, snapshot.createdAt, false,
            snapshot.payloadHash, snapshot.idempotencyKey,
        ).map { it?.toString().orEmpty() }.toMutableList().also {
            it[47] = "false"
            it[50] = "после сна"
        }
        val api = FakeSheetsApi(
            sheets = mutableListOf(WORKOUTS_SHEET, MEASUREMENTS_SHEET),
            measurementRows = mutableListOf(BodyMeasurementRowMapper.HEADER_ROW, remote),
        )

        assertTrue(repository(api).uploadMeasurementSnapshot(snapshot) is UploadResult.PermanentFailure)
        val conflict = db.healthDao().conflicts().single()
        val remoteDecoded = MeasurementSnapshotCodec.decode(conflict.remotePayload)!!
        assertEquals(MeasurementSnapshotCodec.PayloadFormat.V2, remoteDecoded.payloadFormat)
        assertEquals(false, remoteDecoded.measurement.afterMeal)
        assertEquals("после сна", remoteDecoded.measurement.conditionNote)
    }

    @Test
    fun `sheet properties omit an absent index and serialize an explicit position`() {
        val json = Json { encodeDefaults = true }

        assertEquals("{\"title\":\"Workouts\"}", json.encodeToString(SheetPropertiesDto(WORKOUTS_SHEET)))
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
    ): SheetsRepositoryImpl = SheetsRepositoryImpl(
        api,
        auth,
        settings,
        db.workoutDao(),
        db.bodyMeasurementDao(),
        db.healthDao(),
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
        db.bodyMeasurementDao().insert(
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
        val routineId = db.routineDao().upsertRoutine(
            RoutineEntity(syncId = syncId, updatedAt = updatedAt, name = "Грудь", note = "Техника"),
        )
        db.routineDao().replaceRoutineExercises(
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
        private val measurementColumnA: MutableList<String> = mutableListOf(),
        private var measurementHeader: MutableList<String> = mutableListOf(),
        private val measurementRows: MutableList<List<String>> = mutableListOf(),
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
        val insertedDimensions = mutableListOf<com.valerochka1337.valerochkagym.data.google.InsertDimensionDto>()
        val headerUpdates = mutableListOf<List<List<String>>>()
        val clears = mutableListOf<String>()
        val sheetHeaders = mutableMapOf<String, List<String>>()
        var failClearAt: Int? = null
        var mutateHeaderAfterClear: Pair<Int, Pair<String, List<String>>>? = null
        var batchUpdateCount: Int = 0
            private set

        override suspend fun getSpreadsheet(bearer: String, spreadsheetId: String, fields: String): SpreadsheetDto {
            failGetSpreadsheet?.let { throw it }
            return SpreadsheetDto(sheets.mapIndexed { index, title ->
                SheetDto(SheetPropertiesDto(title = title, index = index, sheetId = index))
            })
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

        override suspend fun getValues(bearer: String, spreadsheetId: String, range: String): ValueRangeDto {
            failGetValues?.let { throw it }
            if (range.endsWith("!1:1")) {
                sheetHeaders[range.substringBefore("!")]?.let { return ValueRangeDto(values = listOf(it)) }
            }
            if (range == "Measurements!1:1") {
                if (measurementRows.isNotEmpty()) return ValueRangeDto(values = listOf(measurementRows.first()))
                val header = measurementHeader.ifEmpty {
                    if (measurementColumnA.firstOrNull() == "measurement_id") {
                        BodyMeasurementRowMapper.HEADER_ROW
                    } else {
                        emptyList()
                    }
                }
                return ValueRangeDto(values = header.takeIf { it.isNotEmpty() }?.let(::listOf))
            }
            if (range == MEASUREMENT_APPEND_RANGE && measurementRows.isNotEmpty()) {
                return ValueRangeDto(values = measurementRows)
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
            if (range == MEASUREMENT_APPEND_RANGE) measurementRows += rows
            if (range == ROUTINE_APPEND_RANGE) routineRows += rows
            return JsonNull
        }

        override suspend fun clearValues(
            bearer: String, spreadsheetId: String, range: String, body: ClearValuesDto,
        ): JsonElement {
            if (failClearAt == clears.size + 1) throw IOException("clear failed")
            clears += range
            mutateHeaderAfterClear?.takeIf { (at, _) -> clears.size == at }?.second?.let { (sheet, header) ->
                sheetHeaders[sheet] = header
            }
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
            if (range.startsWith("Routines!") && rows.singleOrNull() != null && routineRows.isNotEmpty()) {
                routineRows[0] = RoutineRowMapper.HEADER_ROW
            }
            if (range.startsWith("Measurements!") && rows.singleOrNull() != null) {
                val column = range.substringAfter('!').takeWhile { it.isLetter() }
                val start = column.fold(0) { value, char -> value * 26 + (char - 'A' + 1) } - 1
                measurementHeader = (
                    measurementHeader.take(start) + rows.single() + measurementHeader.drop(start)
                ).toMutableList()
            }
            return JsonNull
        }

        private fun addSheet(properties: SheetPropertiesDto) {
            if (sheets.contains(properties.title)) return
            val index = properties.index?.coerceIn(0, sheets.size) ?: sheets.size
            sheets.add(index, properties.title)
            addedSheets += properties
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
        /** Literal SQL `MIGRATION_9_10` v1 form, deliberately not produced by the v2 writer. */
        const val actualMigrationV1Payload = "v1|id='nullable-primary'|measuredAt=100|weightKg=70.0|skeletalMuscleMassKg=NULL" +
            "|bodyFatPercentage=20.0|bodyFatMassKg=NULL|visceralFatLevel=NULL|waistHipRatio=NULL" +
            "|waistCm=75.0|chestCm=NULL|hipsCm=100.0|rightRelaxedArmCm=NULL|rightThighCm=NULL" +
            "|inBodyScore=NULL|totalBodyWaterLiters=NULL|proteinKg=NULL|mineralsKg=NULL" +
            "|bodyMassIndex=NULL|fatFreeMassKg=NULL|basalMetabolicRateKcal=NULL|recommendedCalorieIntakeKcal=NULL" +
            "|leftArmLeanMassKg=NULL|leftArmLeanPercentage=NULL|leftArmFatMassKg=NULL|leftArmFatPercentage=NULL" +
            "|rightArmLeanMassKg=NULL|rightArmLeanPercentage=NULL|rightArmFatMassKg=NULL|rightArmFatPercentage=NULL" +
            "|trunkLeanMassKg=NULL|trunkLeanPercentage=NULL|trunkFatMassKg=NULL|trunkFatPercentage=NULL" +
            "|leftLegLeanMassKg=NULL|leftLegLeanPercentage=NULL|leftLegFatMassKg=NULL|leftLegFatPercentage=NULL" +
            "|rightLegLeanMassKg=NULL|rightLegLeanPercentage=NULL|rightLegFatMassKg=NULL|rightLegFatPercentage=NULL"
        const val WORKOUT_ID = "w-1"
        const val MEASUREMENT_ID = "m-1"
        const val SPREADSHEET_ID = "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"
        const val WORKOUTS_SHEET = "Workouts"
        const val MEASUREMENTS_SHEET = "Measurements"
        const val ROUTINES_SHEET = "Routines"
        const val MEASUREMENT_APPEND_RANGE = "Measurements!A:AY"
        const val ROUTINE_APPEND_RANGE = "Routines!A:M"
        const val ROUTINES_RANGE = "Routines!A:M"
    }
}
