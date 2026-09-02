package com.valerochka1337.valerochkagym.data.google

import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseVariantDao
import com.valerochka1337.valerochkagym.data.db.dao.GymDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.ExerciseSheetRecord
import com.valerochka1337.valerochkagym.domain.ExerciseSheetRowMapper
import com.valerochka1337.valerochkagym.domain.ExerciseVariantSheetRecord
import com.valerochka1337.valerochkagym.domain.ExerciseVariantSheetRowMapper
import com.valerochka1337.valerochkagym.domain.GymSheetRecord
import com.valerochka1337.valerochkagym.domain.GymSheetRowMapper
import com.valerochka1337.valerochkagym.domain.RoutineGymsSheetRecord
import com.valerochka1337.valerochkagym.domain.RoutineGymsSheetRowMapper
import com.valerochka1337.valerochkagym.domain.toSheetLongOrNull
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import retrofit2.HttpException
import java.io.IOException

interface ConfigurationSheetsRepository {
    suspend fun uploadExercise(syncId: String): UploadResult
    suspend fun uploadGym(syncId: String): UploadResult
    suspend fun uploadGymDeletion(syncId: String, updatedAt: Long): UploadResult
    suspend fun uploadRoutineGyms(routineSyncId: String): UploadResult
    suspend fun uploadRoutineGymsDeletion(routineSyncId: String, updatedAt: Long): UploadResult
}

object NoOpConfigurationSheetsRepository : ConfigurationSheetsRepository {
    override suspend fun uploadExercise(syncId: String) = UploadResult.Success
    override suspend fun uploadGym(syncId: String) = UploadResult.Success
    override suspend fun uploadGymDeletion(syncId: String, updatedAt: Long) = UploadResult.Success
    override suspend fun uploadRoutineGyms(routineSyncId: String) = UploadResult.Success
    override suspend fun uploadRoutineGymsDeletion(routineSyncId: String, updatedAt: Long) = UploadResult.Success
}

/** Транспорт app-owned конфигурации. Room остаётся source of truth, листы — backup/recovery. */
class ConfigurationSheetsRepositoryImpl @Inject constructor(
    private val api: SheetsApi,
    private val googleAuth: GoogleAuth,
    private val settingsRepository: SettingsRepository,
    private val exerciseDao: ExerciseDao,
    private val exerciseMuscleDao: ExerciseMuscleDao,
    private val gymDao: GymDao,
    private val routineDao: RoutineDao,
    private val variantDao: ExerciseVariantDao? = null,
) : ConfigurationSheetsRepository {

    override suspend fun uploadExercise(syncId: String): UploadResult {
        val exercise = exerciseDao.getAllOnce().firstOrNull { it.syncId == syncId }
            ?: return UploadResult.PermanentFailure("Упражнение не найдено")
        val muscles = exerciseMuscleDao.getForExercise(exercise.id)
            .associate { it.muscle to it.contribution }
        val exerciseResult = uploadVersion(
            sheetName = ExerciseSheetRowMapper.SHEET_NAME,
            range = ExerciseSheetRowMapper.RANGE,
            header = ExerciseSheetRowMapper.HEADER_ROW,
            syncId = syncId,
            updatedAt = exercise.updatedAt,
            isDeleted = false,
            rows = ExerciseSheetRowMapper.rows(
                ExerciseSheetRecord.Snapshot(
                    syncId = syncId,
                    updatedAt = exercise.updatedAt,
                    name = exercise.name,
                    muscleGroup = exercise.muscleGroup,
                    type = exercise.type,
                    isCustom = exercise.isCustom,
                    muscleLoads = muscles,
                ),
            ),
        )
        if (exerciseResult != UploadResult.Success) return exerciseResult
        return variantDao?.getForExercise(exercise.id).orEmpty().fold<_, UploadResult>(UploadResult.Success) { result, variant ->
            if (result != UploadResult.Success) result else uploadVersion(
                sheetName = ExerciseVariantSheetRowMapper.SHEET_NAME,
                range = ExerciseVariantSheetRowMapper.RANGE,
                header = ExerciseVariantSheetRowMapper.HEADER_ROW,
                syncId = variant.syncId,
                updatedAt = variant.updatedAt,
                isDeleted = false,
                rows = listOf(
                    ExerciseVariantSheetRowMapper.row(
                        ExerciseVariantSheetRecord(
                            syncId = variant.syncId,
                            exerciseSyncId = exercise.syncId,
                            updatedAt = variant.updatedAt,
                            name = variant.name,
                            isArchived = variant.isArchived,
                        ),
                    ),
                ),
            )
        }
    }

    override suspend fun uploadGym(syncId: String): UploadResult {
        val gym = gymDao.getGymBySyncId(syncId)
            ?: return UploadResult.PermanentFailure("Зал не найден")
        val full = gymDao.getGymWithExercises(gym.id)
            ?: return UploadResult.PermanentFailure("Зал не найден")
        return uploadVersion(
            sheetName = GymSheetRowMapper.SHEET_NAME,
            range = GymSheetRowMapper.RANGE,
            header = GymSheetRowMapper.HEADER_ROW,
            syncId = syncId,
            updatedAt = gym.updatedAt,
            isDeleted = false,
            rows = GymSheetRowMapper.rows(
                GymSheetRecord.Snapshot(
                    syncId = syncId,
                    updatedAt = gym.updatedAt,
                    name = gym.name,
                    exerciseSyncIds = full.exercises.mapTo(linkedSetOf()) { it.syncId },
                ),
            ),
        )
    }

    override suspend fun uploadGymDeletion(syncId: String, updatedAt: Long): UploadResult =
        uploadVersion(
            sheetName = GymSheetRowMapper.SHEET_NAME,
            range = GymSheetRowMapper.RANGE,
            header = GymSheetRowMapper.HEADER_ROW,
            syncId = syncId,
            updatedAt = updatedAt,
            isDeleted = true,
            rows = GymSheetRowMapper.rows(GymSheetRecord.Tombstone(syncId, updatedAt)),
        )

    override suspend fun uploadRoutineGyms(routineSyncId: String): UploadResult {
        val routine = routineDao.observeRoutinesFull().first()
            .firstOrNull { it.routine.syncId == routineSyncId }
            ?: return UploadResult.PermanentFailure("Программа не найдена")
        return uploadVersion(
            sheetName = RoutineGymsSheetRowMapper.SHEET_NAME,
            range = RoutineGymsSheetRowMapper.RANGE,
            header = RoutineGymsSheetRowMapper.HEADER_ROW,
            syncId = routineSyncId,
            updatedAt = routine.routine.updatedAt,
            isDeleted = false,
            rows = RoutineGymsSheetRowMapper.rows(
                RoutineGymsSheetRecord.Snapshot(
                    routineSyncId = routineSyncId,
                    updatedAt = routine.routine.updatedAt,
                    gymSyncIds = routine.gyms.mapTo(linkedSetOf()) { it.syncId },
                ),
            ),
        )
    }

    override suspend fun uploadRoutineGymsDeletion(
        routineSyncId: String,
        updatedAt: Long,
    ): UploadResult = uploadVersion(
        sheetName = RoutineGymsSheetRowMapper.SHEET_NAME,
        range = RoutineGymsSheetRowMapper.RANGE,
        header = RoutineGymsSheetRowMapper.HEADER_ROW,
        syncId = routineSyncId,
        updatedAt = updatedAt,
        isDeleted = true,
        rows = RoutineGymsSheetRowMapper.rows(
            RoutineGymsSheetRecord.Tombstone(routineSyncId, updatedAt),
        ),
    )

    private suspend fun uploadVersion(
        sheetName: String,
        range: String,
        header: List<String>,
        syncId: String,
        updatedAt: Long,
        isDeleted: Boolean,
        rows: List<List<Any?>>,
    ): UploadResult {
        val spreadsheetId = settingsRepository.settings.first().spreadsheetId
            ?: return UploadResult.PermanentFailure("Укажите таблицу в настройках")
        val token = when (val result = googleAuth.getAccessToken()) {
            is TokenResult.Success -> result.token
            TokenResult.NeedsConsent -> return UploadResult.PermanentFailure(
                "Настройте доступ к Google в настройках",
            )
            is TokenResult.Failed -> return UploadResult.TransientFailure(GoogleErrorMessages.NO_CONNECTION)
        }
        val bearer = "Bearer $token"
        return try {
            ensureSheet(bearer, spreadsheetId, sheetName)
            val existing = api.getValues(bearer, spreadsheetId, range).values.orEmpty()
            if (existing.isNotEmpty() && existing.first() != header) {
                return UploadResult.PermanentFailure(
                    "Заголовок листа $sheetName изменён вручную — выгрузка остановлена",
                )
            }
            val existingVersion = existing.filter { row ->
                row.matchesVersion(syncId, updatedAt, isDeleted)
            }
            if (rows.all { expected -> existingVersion.any { it.matchesExpectedRow(expected) } }) {
                return UploadResult.Success
            }
            val append = if (existing.isEmpty()) listOf(header) + rows else rows
            api.appendValues(bearer, spreadsheetId, range, AppendValuesDto(jsonRows(append)))
            UploadResult.Success
        } catch (e: HttpException) {
            if (HttpErrorClassifier.isPermanent(e.code())) {
                UploadResult.PermanentFailure(HttpErrorClassifier.message(e.code()))
            } else {
                UploadResult.TransientFailure(HttpErrorClassifier.message(e.code()))
            }
        } catch (_: IOException) {
            UploadResult.TransientFailure(GoogleErrorMessages.NO_NETWORK)
        }
    }

    private suspend fun ensureSheet(bearer: String, spreadsheetId: String, sheetName: String) {
        if (sheetExists(bearer, spreadsheetId, sheetName)) return
        try {
            api.batchUpdate(
                bearer,
                spreadsheetId,
                BatchUpdateRequestDto(
                    requests = listOf(BatchRequestDto(AddSheetDto(SheetPropertiesDto(sheetName)))),
                ),
            )
        } catch (e: HttpException) {
            if (e.code() == ADD_SHEET_CONFLICT && sheetExists(bearer, spreadsheetId, sheetName)) return
            throw e
        }
    }

    private suspend fun sheetExists(bearer: String, spreadsheetId: String, sheetName: String): Boolean =
        api.getSpreadsheet(bearer, spreadsheetId).sheets.any { it.properties.title == sheetName }

    private fun List<String>.matchesVersion(syncId: String, updatedAt: Long, isDeleted: Boolean): Boolean =
        getOrNull(0) == syncId &&
            getOrNull(1)?.toSheetLongOrNull() == updatedAt &&
            getOrNull(2)?.toBooleanStrictOrNull() == isDeleted

    private fun List<String>.matchesExpectedRow(expected: List<Any?>): Boolean =
        expected.indices.all { index ->
            val actual = getOrNull(index).orEmpty()
            when (val value = expected[index]) {
                null -> actual.isEmpty()
                is Number -> actual.toDoubleOrNull() == value.toDouble()
                else -> actual == value.toString()
            }
        }

    private fun jsonRows(rows: List<List<Any?>>): JsonArray = buildJsonArray {
        rows.forEach { row ->
            add(buildJsonArray { row.forEach { cell -> add(cell.toJsonPrimitive()) } })
        }
    }

    private fun Any?.toJsonPrimitive(): JsonPrimitive = when (this) {
        null -> JsonPrimitive("")
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }

    private companion object {
        const val ADD_SHEET_CONFLICT = 400
    }
}
