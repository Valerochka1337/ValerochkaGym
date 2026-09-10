package com.valerochka1337.valerochkagym.data.health

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.dao.HealthDao
import com.valerochka1337.valerochkagym.data.db.dao.HealthSyncDao
import com.valerochka1337.valerochkagym.data.db.entity.HealthHeadHistoryEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthLogicalRecordEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthMetricIdentityEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRecordVersionEntity
import com.valerochka1337.valerochkagym.di.ComputeDispatcher
import com.valerochka1337.valerochkagym.domain.*
import com.valerochka1337.valerochkagym.service.WallClock
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject

private const val GUEST_SCOPE = "GUEST"

@Singleton
class HealthRepositoryImpl
@Inject
constructor(
    private val database: GymDatabase,
    private val dao: HealthDao,
    private val syncDao: HealthSyncDao,
    private val bodyMeasurements: BodyMeasurementDao,
    private val sync: BackendSync,
    private val sessions: BackendSessionStore,
    private val consent: HealthConsentStore,
    private val clock: WallClock,
    @ComputeDispatcher private val computeDispatcher: CoroutineDispatcher,
) : HealthRepository {
  private val mutationMutex = Mutex()

  override fun observeCurrent(): Flow<List<HealthCurrentRecord>> {
    return targets().flatMapLatest { target ->
      target?.let {
        combine(dao.observeCurrent(it.scope), dao.observeVersionsForScope(it.scope)) {
            records,
            versions ->
          if (!targetStillCurrent(it)) emptyList()
          else
              records.map { record ->
                record.toCurrent(
                    versions.firstOrNull { version -> version.versionId == record.currentVersionId }
                )
              }
        }
      } ?: flowOf(emptyList())
    }
  }

  override fun observeHistory(logicalId: String): Flow<HealthHistory?> {
    return targets().flatMapLatest { target ->
      target?.let {
        combine(
            dao.observeRecord(logicalId, it.scope),
            dao.observeVersions(logicalId),
            dao.observeHeadHistory(logicalId),
        ) { record, versions, heads ->
          if (record == null || !targetStillCurrent(it)) null
          else
              HealthHistory(
                  record.toCurrent(
                      versions.firstOrNull { version ->
                        version.versionId == record.currentVersionId
                      }
                  ),
                  versions.map(::versionSnapshot),
                  heads.map(::headSnapshot),
              )
        }
      } ?: flowOf(null)
    }
  }

  override fun observeMetrics(): Flow<List<HealthMetricIdentity>> {
    return targets().flatMapLatest { target ->
      target?.let {
        dao.observeMetrics(it.scope).map { metrics ->
          if (!targetStillCurrent(it)) emptyList()
          else
              metrics.map { metric ->
                HealthMetricIdentity(metric.id, metric.nameOriginal, metric.createdAtEpochMs)
              }
        }
      } ?: flowOf(emptyList())
    }
  }

  override fun observeAnalysis(): Flow<HealthAnalysisSnapshot> {
    return targets()
        .flatMapLatest { target ->
          target?.let {
            combine(
                dao.observeCurrent(it.scope),
                dao.observeVersionsForScope(it.scope),
                bodyMeasurements.observeAll(),
            ) { records, versions, measurements ->
              if (!targetStillCurrent(it))
                  HealthAnalysisSnapshot(emptyList(), emptyList(), emptyList())
              else {
                val current =
                    records.map {
                      it.toCurrent(
                          versions.firstOrNull { version ->
                            version.versionId == it.currentVersionId
                          }
                      )
                    }
                HealthAnalysisSnapshot(
                    current,
                    HealthComparability.trends(current),
                    measurements.map { measurement -> measurement.id }.distinct(),
                )
              }
            }
          } ?: flowOf(HealthAnalysisSnapshot(emptyList(), emptyList(), emptyList()))
        }
        .flowOn(computeDispatcher)
  }

  override fun observeBodyMeasurementReferences(
      ids: Set<String>
  ): Flow<List<com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity>> =
      targets().flatMapLatest { target ->
        if (target == null || ids.isEmpty()) flowOf(emptyList())
        else
            bodyMeasurements.observeByIds(ids).map { values ->
              if (targetStillCurrent(target)) values else emptyList()
            }
      }

  override fun observeTargetValidity(target: HealthEditTarget): Flow<Boolean> =
      targets().map { current ->
        current?.scope == target.scope &&
            current.ownerId == target.ownerId &&
            current.sessionEpoch == target.sessionEpoch
      }

  override suspend fun openCreate(kind: HealthRecordKind): HealthEditorSnapshot? {
    val target = currentTarget() ?: return null
    val draft =
        when (kind) {
          HealthRecordKind.REPORT ->
              HealthEditorDraft.Report("", null, "", HealthObservedPrecision.DATE)
          HealthRecordKind.OBSERVATION ->
              HealthEditorDraft.Observation(
                  "",
                  "",
                  "",
                  HealthValueKind.NUMBER,
                  "",
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  "",
                  HealthObservedPrecision.DATE,
              )
          HealthRecordKind.RESTRICTION -> HealthEditorDraft.Restriction("")
        }
    return HealthEditorSnapshot(target, draft)
  }

  override suspend fun openEdit(logicalId: String): HealthEditorSnapshot? {
    val target = currentTarget() ?: return null
    return database.withTransaction {
      val record = dao.record(logicalId, target.scope) ?: return@withTransaction null
      if (record.deleted || record.currentVersionId == null || !targetStillCurrent(target))
          return@withTransaction null
      val version = dao.version(record.currentVersionId) ?: return@withTransaction null
      val payload = payload(version) ?: return@withTransaction null
      HealthEditorSnapshot(
          target.copy(
              logicalId = logicalId,
              currentVersionId = version.versionId,
              baseHeadRevision = record.headRevision,
          ),
          payload.toDraft(),
      )
    }
  }

  override suspend fun createMetric(
      target: HealthEditTarget,
      nameOriginal: String,
  ): HealthMetricMutationResult {
    val normalized =
        HealthLedgerValidator.metricName(nameOriginal) ?: return HealthMetricMutationResult.Invalid
    return mutationMutex.withLock {
      database.withTransaction {
        if (!targetStillCurrent(target))
            return@withTransaction HealthMetricMutationResult.StaleTarget
        val identity =
            HealthMetricIdentityEntity(
                UUID.randomUUID().toString(),
                target.scope,
                normalized,
                clock.nowMillis().coerceAtLeast(0),
            )
        dao.upsertMetric(identity)
        HealthMetricMutationResult.Created(
            HealthMetricIdentity(identity.id, identity.nameOriginal, identity.createdAtEpochMs)
        )
      }
    }
  }

  override suspend fun confirm(
      target: HealthEditTarget,
      draft: HealthEditorDraft,
  ): HealthMutationResult {
    val payload =
        HealthLedgerValidator.normalize(draft, clock.nowMillis())
            ?: return HealthMutationResult.Invalid
    if (!hasCurrentAcknowledgement()) return HealthMutationResult.StorageAcknowledgementRequired
    return mutationMutex.withLock {
      database.withTransaction {
        if (!targetStillCurrent(target)) return@withTransaction HealthMutationResult.StaleTarget
        val existing = target.logicalId?.let { dao.record(it, target.scope) }
        if (
            target.logicalId != null &&
                (existing == null ||
                    existing.deleted ||
                    existing.currentVersionId != target.currentVersionId ||
                    existing.headRevision != target.baseHeadRevision)
        )
            return@withTransaction HealthMutationResult.StaleTarget
        val kind = draft.kind()
        if (existing != null && existing.kind != kind.name)
            return@withTransaction HealthMutationResult.Invalid
        if (payload is HealthPayload.Observation) {
          val report = dao.record(payload.reportLogicalId, target.scope)
          if (
              report == null ||
                  report.kind != HealthRecordKind.REPORT.name ||
                  report.deleted ||
                  dao.metric(payload.metricIdentityId, target.scope) == null
          )
              return@withTransaction HealthMutationResult.Invalid
        }
        val logicalId = existing?.logicalId ?: UUID.randomUUID().toString()
        val versionId = UUID.randomUUID().toString()
        val parent = existing?.currentVersionId
        val version =
            HealthRecordVersionEntity(
                versionId,
                logicalId,
                parent,
                kind.name,
                HealthVersionState.CONFIRMED.name,
                clock.nowMillis().coerceAtLeast(0),
                HealthLedgerValidator.payloadToJson(payload),
                null,
                null,
            )
        if (existing == null)
            dao.upsertRecord(
                HealthLogicalRecordEntity(
                    logicalId,
                    target.scope,
                    kind.name,
                    version.enteredAtEpochMs,
                    versionId,
                    1,
                    false,
                    null,
                )
            )
        else
            dao.upsertRecord(
                existing.copy(
                    currentVersionId = versionId,
                    headRevision = existing.headRevision + 1,
                    deleted = false,
                )
            )
        dao.insertVersion(version)
        captureOperationIfAbsent(
            target.scope,
            version,
            recordHead(logicalId, versionId, existing?.headRevision ?: 0L),
        )
        HealthMutationResult.Saved(logicalId, versionId)
      }
    }
  }

  override suspend fun tombstone(target: HealthEditTarget): HealthMutationResult =
      mutationMutex.withLock {
        database.withTransaction {
          if (!targetStillCurrent(target)) return@withTransaction HealthMutationResult.StaleTarget
          val record =
              target.logicalId?.let { dao.record(it, target.scope) }
                  ?: return@withTransaction HealthMutationResult.MissingOrDeleted
          if (record.deleted) return@withTransaction HealthMutationResult.MissingOrDeleted
          if (
              record.currentVersionId != target.currentVersionId ||
                  record.headRevision != target.baseHeadRevision
          )
              return@withTransaction HealthMutationResult.StaleTarget
          val versionId = UUID.randomUUID().toString()
          val version =
              HealthRecordVersionEntity(
                  versionId,
                  record.logicalId,
                  record.currentVersionId,
                  record.kind,
                  HealthVersionState.TOMBSTONE.name,
                  clock.nowMillis().coerceAtLeast(0),
                  null,
                  null,
                  null,
              )
          dao.insertVersion(version)
          val updated =
              record.copy(
                  currentVersionId = versionId,
                  headRevision = record.headRevision + 1,
                  deleted = true,
                  healthRevision = null,
              )
          dao.upsertRecord(updated)
          captureOperationIfAbsent(
              target.scope,
              version,
              recordHead(record.logicalId, versionId, record.headRevision),
          )
          HealthMutationResult.Saved(record.logicalId, versionId)
        }
      }

  private suspend fun hasCurrentAcknowledgement(): Boolean =
      consent
          .observe()
          .map {
            it.localStorageAcknowledgedVersion >= HealthConsentStoreImpl.CURRENT_NOTICE_VERSION
          }
          .first()

  /** A captured operation is literal and never rebuilt; later local edits wait for settlement. */
  private suspend fun captureOperationIfAbsent(
      scope: String,
      version: HealthRecordVersionEntity,
      head: String,
  ) {
    if (syncDao.outbox(scope) != null) return
    val operationId = UUID.randomUUID().toString()
    val payload = version.payloadJson ?: "null"
    val parent = version.parentVersionId?.let { "\"$it\"" } ?: "null"
    val bytes =
        ("{\"operationId\":\"$operationId\",\"versions\":[{\"versionId\":\"${version.versionId}\",\"logicalId\":\"${version.logicalId}\",\"parentVersionId\":$parent,\"kind\":\"health_${version.kind.lowercase()}\",\"state\":\"${version.state}\",\"enteredAtEpochMs\":${version.enteredAtEpochMs},\"payload\":$payload}],\"heads\":[$head]}")
            .encodeToByteArray()
    val digest =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    syncDao.upsertOutbox(
        com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity(
            operationId,
            scope,
            bytes,
            digest,
        )
    )
  }

  private fun recordHead(logicalId: String, versionId: String, base: Long): String =
      "{\"logicalId\":\"$logicalId\",\"currentVersionId\":\"$versionId\",\"baseHeadRevision\":$base}"

  private fun currentTarget(): HealthEditTarget? {
    val owner = sync.owner()
    val session = sessions.snapshot()
    if (sync.owner() != owner) return null
    return if (owner == null) {
      if (session == null) HealthEditTarget(GUEST_SCOPE, null, 0, null, null, 0) else null
    } else if (session?.tokens?.userId == owner)
        HealthEditTarget(owner, owner, session.epoch, null, null, 0)
    else null
  }

  private fun targets(): Flow<HealthEditTarget?> =
      combine(sync.transfer, sessions.session) { _, _ -> currentTarget() }

  private fun targetStillCurrent(target: HealthEditTarget): Boolean {
    val current = currentTarget() ?: return false
    return current.scope == target.scope &&
        current.ownerId == target.ownerId &&
        current.sessionEpoch == target.sessionEpoch
  }

  private fun HealthLogicalRecordEntity.toCurrent(
      version: HealthRecordVersionEntity?
  ): HealthCurrentRecord =
      HealthCurrentRecord(
          logicalId,
          enum<HealthRecordKind>(kind) ?: HealthRecordKind.REPORT,
          createdAtEpochMs,
          currentVersionId,
          headRevision,
          deleted,
          healthRevision,
          if (deleted) null else payload(version),
      )

  private fun versionSnapshot(version: HealthRecordVersionEntity) =
      HealthVersionSnapshot(
          version.versionId,
          version.logicalId,
          version.parentVersionId,
          enum<HealthRecordKind>(version.kind) ?: HealthRecordKind.REPORT,
          enum<HealthVersionState>(version.state) ?: HealthVersionState.CONFIRMED,
          version.enteredAtEpochMs,
          payload(version),
          version.serverSequence,
          version.healthRevision,
      )

  private fun headSnapshot(head: HealthHeadHistoryEntity) =
      HealthHeadHistorySnapshot(
          head.logicalId,
          head.headRevision,
          head.currentVersionId,
          enum<HealthRecordKind>(head.kind) ?: HealthRecordKind.REPORT,
          head.deleted,
          head.healthRevision,
      )

  private fun payload(version: HealthRecordVersionEntity?): HealthPayload? =
      version
          ?.payloadJson
          ?.let {
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }
                .getOrNull()
          }
          ?.let { HealthLedgerValidator.payloadFromJson(version.kind, it) }

  private fun HealthPayload.toDraft(): HealthEditorDraft =
      when (this) {
        is HealthPayload.Report ->
            HealthEditorDraft.Report(title, sourceText, observedAt, observedPrecision)
        is HealthPayload.Observation ->
            HealthEditorDraft.Observation(
                reportLogicalId,
                metricIdentityId,
                metricNameOriginal,
                valueKind,
                valueOriginal,
                numberValue,
                rangeLow,
                rangeHigh,
                operator,
                unitOriginal,
                methodOriginal,
                specimenOriginal,
                sourceOriginal,
                referenceOriginal,
                observedAt,
                observedPrecision,
            )
        is HealthPayload.Restriction -> HealthEditorDraft.Restriction(textOriginal)
      }

  private fun HealthEditorDraft.kind() =
      when (this) {
        is HealthEditorDraft.Report -> HealthRecordKind.REPORT
        is HealthEditorDraft.Observation -> HealthRecordKind.OBSERVATION
        is HealthEditorDraft.Restriction -> HealthRecordKind.RESTRICTION
      }

  private inline fun <reified T : Enum<T>> enum(value: String): T? =
      runCatching { enumValueOf<T>(value) }.getOrNull()
}
