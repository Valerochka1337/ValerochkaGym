package com.valerochka1337.valerochkagym.data.google

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncConflictEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportSnapshotEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionSnapshotEntity
import com.valerochka1337.valerochkagym.data.settings.HealthSyncCategory as SettingCategory
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.health.HealthInformationSource
import com.valerochka1337.valerochkagym.domain.health.HealthRawValueKind
import com.valerochka1337.valerochkagym.domain.health.HealthReportStatus
import com.valerochka1337.valerochkagym.domain.health.HealthRestrictionState
import com.valerochka1337.valerochkagym.domain.health.HealthSheetRows
import com.valerochka1337.valerochkagym.data.health.HealthSyncPayloadCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import retrofit2.HttpException
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Durable primary-health delivery. It deliberately knows nothing about document bytes or drafts. */
interface HealthSheetsRepository {
    suspend fun upload(entry: HealthSyncOutboxEntity): UploadResult
    suspend fun import(category: SettingCategory): Int
    /** Explicit opt-in import: caller has collected consent but has not persisted the flag yet. */
    suspend fun importForEnable(category: SettingCategory): HealthImportResult =
        HealthImportResult.Success(import(category))
    /**
     * Clears only validated managed ranges. A category with multiple sheets can report that an
     * earlier range was already cleared when a later request fails, so callers can describe the
     * partial remote result accurately without touching local data.
     */
    suspend fun clearAfterConfirmation(category: SettingCategory): RemoteClearResult
}

sealed interface HealthImportResult {
    data class Success(val imported: Int) : HealthImportResult
    data object NothingToImport : HealthImportResult
    data class Failure(val message: String) : HealthImportResult
}

@Singleton
class HealthSheetsRepositoryImpl @Inject constructor(
    private val api: SheetsApi,
    private val auth: GoogleAuth,
    private val settingsRepository: SettingsRepository,
    private val database: GymDatabase,
) : HealthSheetsRepository {
    override suspend fun upload(entry: HealthSyncOutboxEntity): UploadResult {
        val category = entry.settingCategory() ?: return UploadResult.PermanentFailure("Неизвестная категория")
        // Worker preflight is intentionally duplicated here: a category can be disabled after
        // WorkManager starts but before credential/network work. This is suppression, not ACK.
        if (!settingsRepository.settings.first().healthSync.isEnabled(category)) return UploadResult.NotAttemptedDisabled
        val spreadsheetId = settingsRepository.settings.first().spreadsheetId
            ?: return UploadResult.PermanentFailure("Укажите таблицу в настройках")
        val token = (auth.getAccessToken() as? TokenResult.Success)?.token
            ?: return UploadResult.TransientFailure("Нет доступа к Google Sheets")
        return try {
            val bearer = "Bearer $token"
            val definition = definition(category)
            ensureSheet(bearer, spreadsheetId, definition)
            val existing = api.getValues(bearer, spreadsheetId, definition.range).values.orEmpty()
            if (category == SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS) {
                val observations = Definition("HealthObservations", "HealthObservations!A:Q", "Q", HealthSheetRows.OBSERVATION_HEADER, -1)
                ensureSheet(bearer, spreadsheetId, observations)
                val existingObservationRows = api.getValues(bearer, spreadsheetId, observations.range).values.orEmpty()
                val aggregate = HealthSyncPayloadCodec.decodeReport(entry.canonicalPayload)
                    ?.takeIf { it.report.syncId == entry.syncId && it.report.version == entry.version }
                    ?: return UploadResult.PermanentFailure("Снимок исследования повреждён")
                val expectedReport = HealthSheetRows.reportRow(aggregate.report, entry.payloadHash, entry.idempotencyKey)
                val sameVersion = existing.firstOrNull { row ->
                    row.firstOrNull() == entry.syncId && row.getOrNull(1)?.toLongOrNull() == entry.version
                }
                if (sameVersion != null && sameVersion != expectedReport) {
                    val remotePayload = remoteReportPayload(sameVersion, existingObservationRows)
                        ?: return UploadResult.PermanentFailure("Строка исследования в Google Sheets повреждена")
                    storeConflict(entry, remotePayload)
                    return UploadResult.PermanentFailure("Конфликт версии в Google Sheets")
                }
                val exact = aggregate.observations
                exact.forEach { observation ->
                    val expectedObservation = HealthSheetRows.observationRow(observation, aggregate.report.version)
                    val remote = existingObservationRows.firstOrNull { row ->
                        row.firstOrNull() == observation.syncId && row.getOrNull(1) == aggregate.report.syncId &&
                            row.getOrNull(2)?.toLongOrNull() == aggregate.report.version && row.getOrNull(3)?.toLongOrNull() == observation.version
                    }
                    if (remote != null && remote != expectedObservation) {
                        val remoteObservation = parseObservation(remote, ObservationWireLayout(reportVersion = true, sourcePage = true))?.observation
                            ?: return UploadResult.PermanentFailure("Строка наблюдения в Google Sheets повреждена")
                        val remotePayload = HealthSyncPayloadCodec.report(
                            aggregate.report,
                            aggregate.observations.map { candidate ->
                                if (candidate.syncId == observation.syncId) remoteObservation else candidate
                            },
                        )
                        storeConflict(entry, remotePayload)
                        return UploadResult.PermanentFailure("Конфликт наблюдения в Google Sheets")
                    }
                }
                if (sameVersion == null) api.appendValues(
                    bearer, spreadsheetId, definition.range, AppendValuesDto(jsonRows(listOf(expectedReport))),
                )
                val pendingRows = exact.filterNot { observation -> existingObservationRows.any { row ->
                    row.firstOrNull() == observation.syncId && row.getOrNull(1) == aggregate.report.syncId &&
                        row.getOrNull(2)?.toLongOrNull() == aggregate.report.version && row.getOrNull(3)?.toLongOrNull() == observation.version
                } }.map { HealthSheetRows.observationRow(it, aggregate.report.version) }
                if (pendingRows.isNotEmpty()) api.appendValues(bearer, spreadsheetId, observations.range, AppendValuesDto(jsonRows(pendingRows)))
            } else {
                val sameVersion = existing.firstOrNull { row ->
                    row.firstOrNull() == entry.syncId && row.getOrNull(1)?.toLongOrNull() == entry.version
                }
                val expected = rowsFor(entry, category).singleOrNull()
                    ?: return UploadResult.PermanentFailure("Снимок ограничения повреждён")
                if (sameVersion != null && sameVersion != expected) {
                    val remote = parseRestriction(sameVersion)
                        ?.let(HealthSyncPayloadCodec::restriction)
                        ?: return UploadResult.PermanentFailure("Строка ограничения в Google Sheets повреждена")
                    storeConflict(entry, remote)
                    return UploadResult.PermanentFailure("Конфликт версии в Google Sheets")
                }
                if (sameVersion == null) api.appendValues(bearer, spreadsheetId, definition.range, AppendValuesDto(jsonRows(listOf(expected))))
            }
            UploadResult.Success
        } catch (e: HttpException) {
            if (e.code() in 400..499) UploadResult.PermanentFailure("Google Sheets вернул HTTP ${e.code()}")
            else UploadResult.TransientFailure("Google Sheets временно недоступен")
        } catch (_: IOException) {
            UploadResult.TransientFailure("Нет сети")
        }
    }

    override suspend fun import(category: SettingCategory): Int = importInternal(category, enforceSetting = true)

    override suspend fun importForEnable(category: SettingCategory): HealthImportResult = try {
        validateEnableHeaders(category)
        val imported = importInternal(category, enforceSetting = false)
        if (imported == 0) HealthImportResult.NothingToImport else HealthImportResult.Success(imported)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        HealthImportResult.Failure(error.message ?: "Не удалось импортировать данные здоровья")
    }

    private suspend fun validateEnableHeaders(category: SettingCategory) {
        val spreadsheetId = settingsRepository.settings.first().spreadsheetId
            ?: throw IllegalStateException("Укажите таблицу в настройках")
        val token = (auth.getAccessToken() as? TokenResult.Success)?.token
            ?: throw IllegalStateException("Настройте доступ к Google в настройках")
        val bearer = "Bearer $token"
        val definition = definition(category)
        val rows = api.getValues(bearer, spreadsheetId, definition.range).values.orEmpty()
        if (rows.isEmpty()) return // absent health sheet is a compatible legacy state
        if (!rows.first().hasManagedPrefix(definition.header)) throw IllegalStateException("Структура листа здоровья несовместима")
        if (category == SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS) {
            val observationRows = api.getValues(bearer, spreadsheetId, "HealthObservations!A:Q").values.orEmpty()
            if (observationRows.isEmpty()) return
            val header = observationRows.first()
            if (!header.hasManagedPrefix(HealthSheetRows.OBSERVATION_HEADER) &&
                !header.hasManagedPrefix(HealthSheetRows.REPORT_VERSION_OBSERVATION_HEADER) &&
                !header.hasManagedPrefix(HealthSheetRows.LEGACY_OBSERVATION_HEADER)
            ) throw IllegalStateException("Структура листа здоровья несовместима")
        }
    }

    private suspend fun importInternal(category: SettingCategory, enforceSetting: Boolean): Int {
        if (enforceSetting && !settingsRepository.settings.first().healthSync.isEnabled(category)) return 0
        val spreadsheetId = settingsRepository.settings.first().spreadsheetId
            ?: if (enforceSetting) return 0 else throw IllegalStateException("Укажите таблицу в настройках")
        val token = (auth.getAccessToken() as? TokenResult.Success)?.token
            ?: if (enforceSetting) return 0 else throw IllegalStateException("Настройте доступ к Google в настройках")
        val definition = definition(category)
        val rows = try {
            api.getValues("Bearer $token", spreadsheetId, definition.range).values.orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (enforceSetting) return 0 else throw error
        }
        if (rows.isEmpty()) return 0 // An absent sheet is a compatible legacy state.
        require(rows.first().hasManagedPrefix(definition.header)) { "Структура листа здоровья несовместима" }
        if (category == SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS) {
            val observations = Definition("HealthObservations", "HealthObservations!A:Q", "Q", HealthSheetRows.OBSERVATION_HEADER, -1)
            val observationRows = try {
                api.getValues("Bearer $token", spreadsheetId, observations.range).values.orEmpty()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (enforceSetting) return 0 else throw error
            }
            val observationHeader = observationRows.firstOrNull() ?: return 0
            val layout = when {
                observationHeader.hasManagedPrefix(observations.header) -> ObservationWireLayout(reportVersion = true, sourcePage = true)
                observationHeader.hasManagedPrefix(HealthSheetRows.REPORT_VERSION_OBSERVATION_HEADER) -> ObservationWireLayout(reportVersion = true, sourcePage = false)
                observationHeader.hasManagedPrefix(HealthSheetRows.LEGACY_OBSERVATION_HEADER) -> ObservationWireLayout(reportVersion = false, sourcePage = false)
                else -> return 0
            }
            val parsedReports = rows.drop(1).map { row ->
                val report = parseReport(row)
                    ?: throw IllegalStateException("Некорректная строка исследования")
                report to row
            }
            require(parsedReports.groupBy { it.first.syncId to it.first.version }.values.all { it.size == 1 }) {
                "Дублирующаяся версия исследования"
            }
            val reports = parsedReports.map { it.first }.sortedWith(compareBy(HealthReportEntity::syncId).thenBy(HealthReportEntity::version))
            val parsedObservations = observationRows.drop(1).map { row ->
                parseObservation(row, layout) ?: throw IllegalStateException("Некорректная строка результата")
            }
            require(parsedObservations.groupBy { Triple(it.observation.syncId, it.observation.version, it.reportVersion) }.values.all { it.size == 1 }) {
                "Дублирующийся результат исследования"
            }
            val grouped = parsedObservations.groupBy { it.observation.reportSyncId to it.reportVersion }
            require(grouped.keys.all { key -> reports.any { it.syncId == key.first && (layout.reportVersion && it.version == key.second || !layout.reportVersion && key.second == 1L) } }) {
                "Результат без исследования"
            }
            return database.withTransaction {
                reports.count { report ->
                    val key = report.syncId to report.version
                    val exact = grouped[key].orEmpty().map(ParsedSheetObservation::observation)
                    val legacyAmbiguous = !layout.reportVersion && report.version != 1L && grouped.keys.any { it.first == report.syncId }
                    require(report.isTombstone || (!legacyAmbiguous && exact.isNotEmpty())) { "Неполный агрегат исследования" }
                    applyImportedReportAggregate(report, exact, parsedReports.first { it.first == report }.second)
                }
            }
        }
        val restrictionRows = rows.drop(1).map { row ->
            val restriction = parseRestriction(row)
                ?: throw IllegalStateException("Некорректная строка ограничения")
            restriction to row
        }
        require(restrictionRows.groupBy { it.first.syncId to it.first.version }.values.all { it.size == 1 }) {
            "Дублирующаяся версия ограничения"
        }
        return database.withTransaction {
            restrictionRows.count { (restriction, _) -> applyImportedRestriction(restriction) }
        }
    }

    private suspend fun applyImportedReportAggregate(
        parsed: HealthReportEntity,
        observations: List<com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity>,
        row: List<String>,
    ): Boolean = database.withTransaction {
        if (observations.isEmpty() && !parsed.isTombstone) return@withTransaction false
        val payload = HealthSyncPayloadCodec.report(parsed, observations)
        // Sheets v1/v2 rows may omit payload_hash. A conflict must nevertheless be resolvable:
        // persist the digest of the parsed canonical bytes, never an absent or unverified cell.
        val remoteHash = payloadHash(payload)
        val local = database.healthDao().report(parsed.syncId)
        if (local != null && local.version > parsed.version) return@withTransaction false
        if (local != null && local.version == parsed.version) {
            val snapshot = database.healthDao().reportSnapshots(parsed.syncId)
                .firstOrNull { it.version == parsed.version }
            val localPayload = snapshot?.canonicalPayload
                ?: HealthSyncPayloadCodec.report(local, database.healthDao().observations(local.syncId))
            if (localPayload == payload &&
                (snapshot?.payloadHash == null || remoteHash == null || snapshot.payloadHash == remoteHash)
            ) return@withTransaction false
            database.healthDao().upsertConflict(HealthSyncConflictEntity(
                HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, parsed.syncId, parsed.version,
                localPayload, snapshot?.payloadHash,
                payload, remoteHash, System.currentTimeMillis(),
            ))
            return@withTransaction false
        }
        database.healthDao().upsertReport(parsed)
        database.healthDao().deleteObservations(parsed.syncId)
        if (!parsed.isTombstone) database.healthDao().insertObservations(observations)
        database.healthDao().insertReportSnapshot(
            HealthReportSnapshotEntity(parsed.syncId, parsed.version, parsed.updatedAt, parsed.isTombstone, payload, remoteHash),
        )
        true
    }

    private fun parseReport(row: List<String>): HealthReportEntity? = runCatching {
        val status = row[4].let { if (it == "CONFIRMED") "FINAL" else it }
        val report = HealthReportEntity(
            syncId = row[0], version = row[1].toLong(), updatedAt = row[2].toLong(),
            isTombstone = row[3].toStrictBit(), status = status, provenance = row[5], reportedAt = row[6].toLong(),
            collectedAt = row.getOrNull(7)?.toLongOrNull(), title = row[8], note = row.getOrNull(9)?.takeIf(String::isNotBlank),
            supersedesVersion = row.getOrNull(10)?.toLongOrNull(), correctionOfVersion = row.getOrNull(11)?.toLongOrNull(),
            conditions = row.getOrNull(12)?.takeIf(String::isNotBlank), originalExpected = row[13].toStrictBit(),
        )
        require(report.syncId.isNotBlank() && report.title.isNotBlank() && report.provenance.isNotBlank())
        require(report.version > 0 && report.updatedAt > 0 && report.reportedAt > 0)
        require(report.collectedAt == null || report.collectedAt > 0)
        require(report.status in REPORT_STATUSES)
        require(report.supersedesVersion == null || report.supersedesVersion in 1 until report.version)
        require(report.correctionOfVersion == null || report.correctionOfVersion in 1 until report.version)
        require((report.status == "REVOKED") == report.isTombstone)
        require(report.status != "CORRECTED" || report.correctionOfVersion != null)
        report
    }.getOrNull()
    private fun parseObservation(row: List<String>, layout: ObservationWireLayout): ParsedSheetObservation? = runCatching {
        val offset = if (layout.reportVersion) 1 else 0
        val reportVersion = if (layout.reportVersion) row[2].toLong() else 1L
        val observation = com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity(
            row[0], row[1], row[2 + offset].toLong(), row[3 + offset].toLong(), row[4 + offset].toStrictBit(), row[5 + offset].toLong(),
            row[6 + offset], row[7 + offset], row[8 + offset], row.getOrNull(9 + offset)?.takeIf(String::isNotBlank),
            row.getOrNull(10 + offset)?.takeIf(String::isNotBlank), row.getOrNull(11 + offset)?.takeIf(String::isNotBlank),
            row.getOrNull(12 + offset)?.takeIf(String::isNotBlank), row.getOrNull(13 + offset)?.takeIf(String::isNotBlank), row.getOrNull(14 + offset)?.takeIf(String::isNotBlank),
            if (layout.sourcePage) row.getOrNull(15 + offset)?.toIntOrNull()?.takeIf { it > 0 } else null,
        )
        require(observation.syncId.isNotBlank() && observation.reportSyncId.isNotBlank())
        require(reportVersion > 0 && observation.version > 0 && observation.updatedAt > 0 && observation.observedAt > 0)
        require(observation.rawName.isNotBlank() && observation.rawValue.isNotBlank())
        require(observation.valueType in OBSERVATION_VALUE_TYPES)
        ParsedSheetObservation(observation, reportVersion)
    }.getOrNull()

    private fun parseRestriction(row: List<String>): HealthRestrictionEntity? = runCatching {
        val restriction = HealthRestrictionEntity(
            syncId = row[0], version = row[1].toLong(), updatedAt = row[2].toLong(), isTombstone = row[3].toStrictBit(),
            status = row[4], source = row[5], confirmedAt = row[6].toLong(),
            startsAt = row.getOrNull(7)?.toLongOrNull(), reviewAt = row.getOrNull(8)?.toLongOrNull(), description = row[9],
        )
        require(restriction.syncId.isNotBlank() && restriction.description.isNotBlank())
        require(restriction.version > 0 && restriction.updatedAt > 0 && restriction.confirmedAt > 0)
        require(restriction.startsAt == null || restriction.startsAt > 0)
        require(restriction.reviewAt == null || restriction.reviewAt > 0)
        require(restriction.startsAt == null || restriction.reviewAt == null || restriction.reviewAt >= restriction.startsAt)
        require(restriction.status in RESTRICTION_STATUSES && restriction.source in RESTRICTION_SOURCES)
        require((restriction.status == "LIFTED") == restriction.isTombstone)
        restriction
    }.getOrNull()

    private fun remoteReportPayload(
        reportRow: List<String>,
        observationRows: List<List<String>>,
    ): String? = parseReport(reportRow)?.let { report ->
        observationRows.drop(1).mapNotNull { parseObservation(it, ObservationWireLayout(reportVersion = true, sourcePage = true)) }
            .filter { it.observation.reportSyncId == report.syncId && it.reportVersion == report.version }
            .map(ParsedSheetObservation::observation)
            .takeIf { report.isTombstone || it.isNotEmpty() }
            ?.let { observations -> HealthSyncPayloadCodec.report(report, observations) }
    }

    private suspend fun storeConflict(entry: HealthSyncOutboxEntity, remotePayload: String) {
        database.healthDao().upsertConflict(
            HealthSyncConflictEntity(
                category = entry.category,
                syncId = entry.syncId,
                version = entry.version,
                localPayload = entry.canonicalPayload,
                localPayloadHash = entry.payloadHash,
                remotePayload = remotePayload,
                remotePayloadHash = payloadHash(remotePayload),
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun clearAfterConfirmation(category: SettingCategory): RemoteClearResult {
        val spreadsheetId = settingsRepository.settings.first().spreadsheetId
            ?: return RemoteClearResult.Failure("Укажите таблицу в настройках")
        val token = (auth.getAccessToken() as? TokenResult.Success)?.token
            ?: return RemoteClearResult.Failure("Нет доступа к Google Sheets")
        return try {
            val definitions = clearDefinitions(category)
                ?: return RemoteClearResult.Failure("Категория не поддерживает очистку")
            val bearer = "Bearer $token"
            val titles = api.getSpreadsheet(bearer, spreadsheetId).sheets
                .map { it.properties.title }
                .toSet()
            // A reports aggregate must never be half-cleared: every existing companion header is
            // validated before the first values.clear request.
            val validated = definitions.mapNotNull { definition ->
                if (definition.sheet !in titles) return@mapNotNull null
                val header = api.getValues(bearer, spreadsheetId, "${definition.sheet}!1:1")
                    .values
                    ?.firstOrNull()
                    .orEmpty()
                definition.clearRange(header)?.let { range -> ValidatedHealthClear(definition, header, range) }
                    ?: return RemoteClearResult.Failure(
                        "Заголовок листа ${definition.sheet} изменён вручную — очистка отменена",
                    )
            }
            val clearedRanges = mutableListOf<String>()
            for ((index, clear) in validated.withIndex()) {
                val remaining = validated.drop(index + 1).map(ValidatedHealthClear::range)
                val currentHeader = try {
                    api.getValues(bearer, spreadsheetId, "${clear.definition.sheet}!1:1")
                        .values
                        ?.firstOrNull()
                        .orEmpty()
                } catch (_: IOException) {
                    return RemoteClearResult.Failure(
                        "Нет сети при повторной проверке листа здоровья",
                        clearedRanges,
                        clear.range,
                        remaining,
                    )
                } catch (_: HttpException) {
                    return RemoteClearResult.Failure(
                        "Не удалось повторно проверить структуру листа здоровья",
                        clearedRanges,
                        clear.range,
                        remaining,
                    )
                }
                if (currentHeader != clear.header || clear.definition.clearRange(currentHeader) != clear.range) {
                    return RemoteClearResult.Failure(
                        "Заголовок листа ${clear.definition.sheet} изменён вручную — очистка отменена",
                        clearedRanges,
                        clear.range,
                        remaining,
                    )
                }
                try {
                    api.clearValues(bearer, spreadsheetId, clear.range)
                    clearedRanges += clear.range
                } catch (_: IOException) {
                    return RemoteClearResult.Failure(
                        "Нет сети при очистке Google Sheets",
                        clearedRanges,
                        clear.range,
                        remaining,
                    )
                } catch (error: HttpException) {
                    return RemoteClearResult.Failure(
                        "Google Sheets вернул HTTP ${error.code()} при очистке",
                        clearedRanges,
                        clear.range,
                        remaining,
                    )
                }
            }
            RemoteClearResult.Success(clearedRanges)
        } catch (_: IOException) {
            RemoteClearResult.Failure("Нет сети при проверке листов здоровья")
        } catch (e: HttpException) {
            RemoteClearResult.Failure("Google Sheets вернул HTTP ${e.code()} при проверке листов здоровья")
        } catch (_: IllegalStateException) {
            RemoteClearResult.Failure("Не удалось безопасно проверить структуру листов здоровья")
        }
    }

    private fun rowsFor(entry: HealthSyncOutboxEntity, category: SettingCategory): List<List<String>> {
        return when (category) {
            SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS -> {
                HealthSyncPayloadCodec.decodeReport(entry.canonicalPayload)?.let { listOf(HealthSheetRows.reportRow(it.report, entry.payloadHash, entry.idempotencyKey)) }.orEmpty()
            }
            SettingCategory.HEALTH_RESTRICTIONS -> HealthSyncPayloadCodec.decodeRestriction(entry.canonicalPayload)
                ?.takeIf { it.syncId == entry.syncId && it.version == entry.version }
                ?.let { listOf(HealthSheetRows.restrictionRow(it, entry.payloadHash, entry.idempotencyKey)) }
                .orEmpty()
            else -> emptyList()
        }
    }

    private suspend fun applyImportedRestriction(parsed: HealthRestrictionEntity): Boolean {
        val id = parsed.syncId
        val version = parsed.version
        val remotePayload = HealthSyncPayloadCodec.restriction(parsed)
        val hash = payloadHash(remotePayload)
        return database.withTransaction {
            val local = database.healthDao().restriction(id)
            val localVersion = local?.version
            if (localVersion != null && localVersion > version) return@withTransaction false
            if (localVersion == version) {
                val localPayload = HealthSyncPayloadCodec.restriction(local)
                val localHash = database.healthSyncOutboxDao()
                    .entry(HealthSyncCategory.HEALTH_RESTRICTIONS, id, version)?.payloadHash
                if (localPayload == remotePayload) return@withTransaction false
                database.healthDao().upsertConflict(HealthSyncConflictEntity(
                    category = HealthSyncCategory.HEALTH_RESTRICTIONS, syncId = id, version = version,
                    localPayload = localPayload, localPayloadHash = localHash,
                    remotePayload = remotePayload, remotePayloadHash = hash, createdAt = System.currentTimeMillis(),
                ))
                return@withTransaction false
            }
            database.healthDao().upsertRestriction(parsed.copy(originalText = local?.originalText))
            database.healthDao().insertRestrictionSnapshot(
                HealthRestrictionSnapshotEntity(parsed.syncId, parsed.version, parsed.updatedAt, parsed.isTombstone, remotePayload, hash),
            )
            true
        }
    }

    private suspend fun ensureSheet(bearer: String, spreadsheetId: String, definition: Definition) {
        val sheet = api.getSpreadsheet(bearer, spreadsheetId).sheets.firstOrNull { it.properties.title == definition.sheet }
        if (sheet == null) api.batchUpdate(bearer, spreadsheetId, BatchUpdateRequestDto(listOf(BatchRequestDto(addSheet = AddSheetDto(SheetPropertiesDto(definition.sheet))))))
        val header = api.getValues(bearer, spreadsheetId, "${definition.sheet}!1:1").values?.firstOrNull().orEmpty()
        if (header.isEmpty()) api.updateValues(bearer, spreadsheetId, "${definition.sheet}!A1", UpdateValuesDto(jsonRows(listOf(definition.header))))
        else check(header.hasManagedPrefix(definition.header)) { "Несовместимый заголовок ${definition.sheet}" }
    }

    private fun definition(category: SettingCategory): Definition = when (category) {
        SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS -> Definition(HealthSheetRows.REPORT_SHEET, "${HealthSheetRows.REPORT_SHEET}!A:P", "P", HealthSheetRows.REPORT_HEADER, REPORT_HASH_INDEX)
        SettingCategory.HEALTH_RESTRICTIONS -> Definition("HealthRestrictions", "HealthRestrictions!A:L", "L", HealthSheetRows.RESTRICTION_HEADER, 10)
        else -> error("Not a health Sheets category")
    }

    private fun clearDefinitions(category: SettingCategory): List<ClearDefinition>? = when (category) {
        SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS -> listOf(
            ClearDefinition(HealthSheetRows.REPORT_SHEET, listOf(HealthSheetRows.REPORT_HEADER to "P")),
            ClearDefinition(
                "HealthObservations",
                listOf(
                    HealthSheetRows.OBSERVATION_HEADER to "Q",
                    HealthSheetRows.REPORT_VERSION_OBSERVATION_HEADER to "P",
                    HealthSheetRows.LEGACY_OBSERVATION_HEADER to "O",
                ),
            ),
        )
        SettingCategory.HEALTH_RESTRICTIONS -> listOf(
            ClearDefinition("HealthRestrictions", listOf(HealthSheetRows.RESTRICTION_HEADER to "L")),
        )
        else -> null
    }
    private fun HealthSyncOutboxEntity.settingCategory() = when (category) {
        HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS -> SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS
        HealthSyncCategory.HEALTH_RESTRICTIONS -> SettingCategory.HEALTH_RESTRICTIONS
        else -> null
    }
    private fun SettingCategory.outboxCategory() = when (this) {
        SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS -> HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS
        SettingCategory.HEALTH_RESTRICTIONS -> HealthSyncCategory.HEALTH_RESTRICTIONS
        else -> error("Not a health category")
    }
    private fun jsonRows(rows: List<List<String>>): JsonArray = buildJsonArray { rows.forEach { row -> add(buildJsonArray { row.forEach { add(JsonPrimitive(it)) } }) } }
    private data class Definition(val sheet: String, val range: String, val lastColumn: String, val header: List<String>, val hashIndex: Int)
    private data class ValidatedHealthClear(
        val definition: ClearDefinition,
        val header: List<String>,
        val range: String,
    )
    private data class ParsedSheetObservation(
        val observation: com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity,
        val reportVersion: Long,
    )
    private data class ObservationWireLayout(val reportVersion: Boolean, val sourcePage: Boolean)

    private data class ClearDefinition(
        val sheet: String,
        val headers: List<Pair<List<String>, String>>,
    ) {
        fun clearRange(header: List<String>): String? = headers.firstOrNull { (expected, _) ->
            header.size >= expected.size && header.take(expected.size) == expected
        }?.second?.let { "$sheet!A2:$it" }
    }

    private fun List<String>.hasManagedPrefix(expected: List<String>): Boolean = size >= expected.size && take(expected.size) == expected
    private fun String.toStrictBit(): Boolean = when (this) {
        "0" -> false
        "1" -> true
        else -> error("Expected 0 or 1")
    }
    private fun payloadHash(payload: String): String = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private companion object {
        const val REPORT_HASH_INDEX = 14
        val REPORT_STATUSES = HealthReportStatus.entries.mapTo(linkedSetOf()) { it.name }
        val RESTRICTION_STATUSES = HealthRestrictionState.entries.mapTo(linkedSetOf()) { it.name }
        val RESTRICTION_SOURCES = HealthInformationSource.entries.mapTo(linkedSetOf()) { it.name }
        val OBSERVATION_VALUE_TYPES = HealthRawValueKind.entries.mapTo(linkedSetOf()) { it.name }
    }
}
