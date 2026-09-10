package com.valerochka1337.valerochkagym.data.health

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.BackendResponse
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.HealthDao
import com.valerochka1337.valerochkagym.data.db.dao.HealthSyncDao
import com.valerochka1337.valerochkagym.data.db.entity.HealthHeadHistoryEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthLogicalRecordEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRecordVersionEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncBaselineEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncStagingEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncStateEntity
import com.valerochka1337.valerochkagym.domain.HealthConsentStore
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The isolated health transport. Pages are staged as raw typed events and become visible only in
 * [applyStaged] after the server's final commit cursor arrives. It deliberately never reads or
 * rewrites the generic sync journal.
 */
@Singleton
class HealthLedgerSync
@Inject
constructor(
    private val database: GymDatabase,
    private val dao: HealthDao,
    private val syncDao: HealthSyncDao,
    private val api: BackendTransport,
    private val sessions: BackendSessionStore,
    private val sync: BackendSync,
    private val consent: HealthConsentStore,
) {
  suspend fun replayPending(): Boolean = sync.mutex.withLock { syncUnderOwnerMutex() }

  private suspend fun syncUnderOwnerMutex(): Boolean {
    val snapshot = sessions.snapshot() ?: return false
    val owner = snapshot.tokens.userId
    if (
        sync.owner() != owner ||
            !sync.supportsHealthLedger() ||
            !consent.observe().first().backendSyncEnabled ||
            dao.hasActiveWorkout()
    )
        return false
    // A pre-partition generic request is immutable evidence and must settle untouched first.
    if (
        database.openHelper.writableDatabase.query("SELECT 1 FROM backend_outbox WHERE id=1").use {
          it.moveToFirst()
        }
    )
        return false
    val state = syncDao.state(owner) ?: HealthSyncStateEntity(owner, null, needsFullRefresh = true)
    val didFull = state.needsFullRefresh || syncDao.outbox(owner) != null
    if (didFull && !refresh(owner, snapshot.epoch, full = true, after = null)) return false
    val current = syncDao.state(owner) ?: return false
    if (
        !didFull &&
            !current.needsFullRefresh &&
            current.cursor != null &&
            !refresh(owner, snapshot.epoch, full = false, after = current.cursor)
    )
        return false
    return replayOutbox(owner, snapshot.epoch)
  }

  /**
   * Reads an as-of-H page sequence; no Room projection/baseline/cursor changes before its last
   * page.
   */
  private suspend fun refresh(owner: String, epoch: Long, full: Boolean, after: String?): Boolean {
    var pageToken: String? = null
    var finalCursor: String? = null
    try {
      do {
        val path = pagePath(full, after, pageToken)
        val response =
            api.authorizedRawResponse(
                method = "GET",
                path = path,
                rawBody = ByteArray(0),
                headers = capabilityHeaders,
                expectedOwner = owner,
                expectedSessionEpoch = epoch,
                retryOnUnauthorized = false,
                maxResponseBytes = MAX_RESPONSE_BYTES,
            )
        if (
            !current(owner, epoch, response) ||
                CAPABILITY !in response.acceptedCapabilities ||
                !allowed(owner, epoch)
        )
            return false
        val page =
            parsePage(HealthWireJson.objectFrom(response.rawBody) ?: return false) ?: return false
        stagePage(owner, page)
        pageToken = page.nextPageToken
        finalCursor = page.commitCursor
      } while (pageToken != null)
      if (finalCursor == null) return false
      return applyStaged(owner, epoch, finalCursor)
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      return false
    }
  }

  private suspend fun stagePage(owner: String, page: HealthPage): Unit =
      database.withTransaction {
        val previous = syncDao.state(owner)
        syncDao.upsertState(
            (previous ?: HealthSyncStateEntity(owner, null)).copy(
                needsFullRefresh = true,
                pendingCursor = page.commitCursor ?: previous?.pendingCursor,
            )
        )
        page.versions.forEach { version ->
          syncDao.stage(
              HealthSyncStagingEntity(
                  owner,
                  version.healthRevision,
                  "version",
                  version.versionId,
                  version.raw.toString(),
              )
          )
        }
        page.heads.forEach { head ->
          syncDao.stage(
              HealthSyncStagingEntity(
                  owner,
                  head.healthRevision,
                  "head",
                  "${head.logicalId}:${head.headRevision}",
                  head.raw.toString(),
              )
          )
        }
      }

  /** The only transaction that exposes a frozen sequence of staged health events. */
  private suspend fun applyStaged(owner: String, epoch: Long, cursor: String): Boolean =
      database.withTransaction {
        if (
            !currentSnapshot(owner, epoch) ||
                !sync.supportsHealthLedger() ||
                !consent.observe().first().backendSyncEnabled ||
                dao.hasActiveWorkout()
        )
            throw Rejected
        val staged = syncDao.staged(owner)
        val events =
            staged
                .map { stagedEvent ->
                  when (stagedEvent.eventKind) {
                    "version" ->
                        parseVersion(Json.parseToJsonElement(stagedEvent.eventJson).jsonObject)
                            ?.let { Event.Version(it) }
                    "head" ->
                        parseHead(Json.parseToJsonElement(stagedEvent.eventJson).jsonObject)?.let {
                          Event.Head(it)
                        }
                    else -> null
                  } ?: throw Rejected
                }
                .sortedBy { it.healthRevision }
        if (events.map { it.healthRevision }.zipWithNext().any { (a, b) -> a >= b }) throw Rejected
        if (!validateStagedGraph(owner, events)) throw Rejected
        events.forEach { event ->
          when (event) {
            is Event.Version -> if (!applyVersion(owner, event.value)) throw Rejected
            is Event.Head -> if (!applyHead(owner, event.value)) throw Rejected
          }
        }
        // A full snapshot replaces the baseline; changes only union new immutable versions.
        val replacingSnapshot = syncDao.state(owner)?.cursor == null
        if (replacingSnapshot) syncDao.deleteBaseline(owner)
        events.filterIsInstance<Event.Version>().forEach { event ->
          syncDao.upsertBaseline(
              HealthSyncBaselineEntity(owner, event.value.versionId, event.value.raw.toString())
          )
        }
        syncDao.clearStaging(owner)
        syncDao.upsertState(HealthSyncStateEntity(owner, cursor, needsFullRefresh = false))
        true
      }

  private suspend fun replayOutbox(owner: String, epoch: Long): Boolean {
    val outbox = syncDao.outbox(owner) ?: return true
    val request = parseOperation(outbox.requestBytes) ?: return false
    return try {
      if (!allowed(owner, epoch)) return false
      database.withTransaction { syncDao.markDispatched(outbox.operationId) }
      val response =
          api.authorizedRawResponse(
              method = "POST",
              path = OPERATIONS_PATH,
              rawBody = outbox.requestBytes,
              headers = capabilityHeaders,
              expectedOwner = owner,
              expectedSessionEpoch = epoch,
              retryOnUnauthorized = false,
              maxResponseBytes = MAX_RESPONSE_BYTES,
          )
      if (
          !current(owner, epoch, response) ||
              !sync.supportsHealthLedger() ||
              CAPABILITY !in response.acceptedCapabilities ||
              !consent.observe().first().backendSyncEnabled ||
              dao.hasActiveWorkout()
      )
          return false
      val result =
          parseOperationResult(HealthWireJson.objectFrom(response.rawBody) ?: return false)
              ?: return false
      if (
          result.operationId != request.operationId ||
              result.receipts.map { it.versionId } != request.versionIds ||
              result.heads.map { it.logicalId to it.submittedCurrentVersionId } != request.heads
      )
          return false
      database.withTransaction {
        if (
            !currentSnapshot(owner, epoch) ||
                !sync.supportsHealthLedger() ||
                !consent.observe().first().backendSyncEnabled ||
                dao.hasActiveWorkout()
        )
            throw Rejected
        result.receipts.forEach { receipt ->
          val stored = dao.version(receipt.versionId) ?: throw Rejected
          if (
              (stored.serverSequence != null && stored.serverSequence != receipt.serverSequence) ||
                  (stored.healthRevision != null && stored.healthRevision != receipt.healthRevision)
          )
              throw Rejected
          dao.upsertVersionAssignment(
              receipt.versionId,
              receipt.serverSequence,
              receipt.healthRevision,
          )
        }
        result.heads.forEach { resultHead ->
          if (!applyHeadResult(owner, resultHead)) throw Rejected
        }
        syncDao.deleteOutbox(outbox.operationId)
        captureOutstanding(owner)
        true
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      false
    }
  }

  /** Captures edits made while an earlier exact operation was in flight, only after its ACK. */
  private suspend fun captureOutstanding(owner: String) {
    if (syncDao.outbox(owner) != null) return
    val outstanding = dao.allUnacknowledgedVersions(owner)
    if (outstanding.isEmpty()) return
    val remaining = outstanding.toMutableList()
    val versions = mutableListOf<HealthRecordVersionEntity>()
    while (versions.size < 500 && remaining.isNotEmpty()) {
      val ready =
          remaining.filter { version ->
            version.parentVersionId == null ||
                versions.any { it.versionId == version.parentVersionId } ||
                outstanding.none { it.versionId == version.parentVersionId }
          }
      if (ready.isEmpty()) return
      val next =
          ready.minWith(
              compareBy(
                  HealthRecordVersionEntity::enteredAtEpochMs,
                  HealthRecordVersionEntity::versionId,
              )
          )
      versions += next
      remaining -= next
    }
    val operationId = UUID.randomUUID().toString()
    val versionJson =
        JsonArray(
                versions.map { version ->
                  buildJsonObject {
                    put("versionId", version.versionId)
                    put("logicalId", version.logicalId)
                    put(
                        "parentVersionId",
                        version.parentVersionId?.let(::JsonPrimitive) ?: JsonNull,
                    )
                    put("kind", "health_${version.kind.lowercase()}")
                    put("state", version.state)
                    put("enteredAtEpochMs", version.enteredAtEpochMs)
                    put(
                        "payload",
                        version.payloadJson?.let(Json::parseToJsonElement) ?: JsonNull,
                    )
                  }
                }
            )
            .toString()
    val heads =
        versions
            .map { it.logicalId }
            .distinct()
            .mapNotNull { logicalId ->
              val record = dao.record(logicalId, owner) ?: return@mapNotNull null
              val current = record.currentVersionId ?: return@mapNotNull null
              val currentVersion = dao.version(current) ?: return@mapNotNull null
              if (
                  currentVersion.serverSequence == null && versions.none { it.versionId == current }
              )
                  return@mapNotNull null
              val serverHeadRevision =
                  dao.observeHeadHistory(logicalId).first().maxOfOrNull { it.headRevision } ?: 0
              "{\"logicalId\":\"$logicalId\",\"currentVersionId\":\"$current\",\"baseHeadRevision\":$serverHeadRevision}"
            }
            .joinToString(prefix = "[", postfix = "]")
    val bytes =
        "{\"operationId\":\"$operationId\",\"versions\":$versionJson,\"heads\":$heads}"
            .encodeToByteArray()
    val hash =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    syncDao.upsertOutbox(
        com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity(
            operationId,
            owner,
            bytes,
            hash,
        )
    )
  }

  /** Validates the whole frozen graph before any record, version, or cursor can be changed. */
  private suspend fun validateStagedGraph(owner: String, events: List<Event>): Boolean {
    val incomingVersions = events.filterIsInstance<Event.Version>().map { it.value }
    if (incomingVersions.map { it.versionId }.distinct().size != incomingVersions.size) return false
    val byId = incomingVersions.associateBy { it.versionId }
    for (version in incomingVersions) {
      val stored = dao.version(version.versionId)
      if (stored != null && !version.matches(stored)) return false
      val record = dao.record(version.logicalId, owner)
      if (record != null && record.kind != version.kind) return false
      val parentId = version.parentVersionId ?: continue
      val parent = byId[parentId] ?: dao.version(parentId) ?: return false
      val parentLogical =
          when (parent) {
            is HealthVersion -> parent.logicalId
            is HealthRecordVersionEntity -> parent.logicalId
            else -> return false
          }
      val parentKind =
          when (parent) {
            is HealthVersion -> parent.kind
            is HealthRecordVersionEntity -> parent.kind
            else -> return false
          }
      if (parentLogical != version.logicalId || parentKind != version.kind) return false
    }
    for (head in events.filterIsInstance<Event.Head>().map { it.value }) {
      val target = byId[head.currentVersionId] ?: dao.version(head.currentVersionId) ?: return false
      val targetLogical =
          when (target) {
            is HealthVersion -> target.logicalId
            is HealthRecordVersionEntity -> target.logicalId
            else -> return false
          }
      val targetKind =
          when (target) {
            is HealthVersion -> target.kind
            is HealthRecordVersionEntity -> target.kind
            else -> return false
          }
      val targetState =
          when (target) {
            is HealthVersion -> target.state
            is HealthRecordVersionEntity -> target.state
            else -> return false
          }
      if (
          targetLogical != head.logicalId ||
              targetKind != head.kind ||
              head.deleted != (targetState == "TOMBSTONE")
      )
          return false
      val record = dao.record(head.logicalId, owner)
      if (record != null && record.kind != head.kind) return false
      if (record == null && byId.values.none { it.logicalId == head.logicalId }) return false
    }
    return true
  }

  private suspend fun applyVersion(owner: String, version: HealthVersion): Boolean {
    val stored = dao.version(version.versionId)
    if (stored == null) {
      val record = dao.record(version.logicalId, owner)
      if (record != null && record.kind != version.kind) return false
      if (record == null)
          dao.upsertRecord(
              HealthLogicalRecordEntity(
                  version.logicalId,
                  owner,
                  version.kind,
                  version.enteredAtEpochMs,
                  null,
                  0,
                  false,
                  null,
              )
          )
      dao.insertVersion(
          HealthRecordVersionEntity(
              version.versionId,
              version.logicalId,
              version.parentVersionId,
              version.kind,
              version.state,
              version.enteredAtEpochMs,
              version.payload?.toString(),
              version.serverSequence,
              version.healthRevision,
          )
      )
    } else if (!version.matches(stored)) {
      return false
    } else if (stored.serverSequence == null && stored.healthRevision == null) {
      dao.upsertVersionAssignment(stored.versionId, version.serverSequence, version.healthRevision)
    }
    return true
  }

  private suspend fun applyHead(
      owner: String,
      head: HealthHead,
      updateProjection: Boolean = true,
  ): Boolean {
    val record = dao.record(head.logicalId, owner) ?: return false
    val version = dao.version(head.currentVersionId) ?: return false
    if (
        version.logicalId != head.logicalId ||
            version.kind != head.kind ||
            head.deleted != (version.state == "TOMBSTONE")
    )
        return false
    val existing = dao.headHistory(head.logicalId, head.headRevision)
    if (existing == null)
        dao.insertHeadHistory(
            HealthHeadHistoryEntity(
                head.logicalId,
                head.headRevision,
                head.currentVersionId,
                version.kind,
                head.deleted,
                head.healthRevision,
            )
        )
    else if (
        existing.currentVersionId != head.currentVersionId ||
            existing.kind != head.kind ||
            existing.deleted != head.deleted ||
            existing.healthRevision != head.healthRevision
    )
        return false
    val hasNewerLocalHead =
        record.currentVersionId
            ?.let { dao.version(it) }
            ?.let { it.serverSequence == null || it.healthRevision == null } == true
    if (updateProjection && !hasNewerLocalHead && record.headRevision <= head.headRevision)
        dao.upsertRecord(
            record.copy(
                currentVersionId = head.currentVersionId,
                headRevision = head.headRevision,
                deleted = head.deleted,
                healthRevision = head.healthRevision,
            )
        )
    return true
  }

  private suspend fun applyHeadResult(owner: String, head: HealthHeadResult): Boolean {
    val record = dao.record(head.logicalId, owner) ?: return false
    val version = dao.version(head.serverCurrentVersionId) ?: return false
    // A stale client CAS still receives the authoritative server head. Keep its losing immutable
    // version in audit, but never leave the speculative local projection selected.
    return applyHead(
        owner,
        HealthHead(
            head.logicalId,
            head.serverCurrentVersionId,
            head.headRevision,
            version.kind,
            version.state == "TOMBSTONE",
            head.healthRevision,
            JsonObject(emptyMap()),
        ),
        // An edit made after this exact request was captured remains the desired local head. The
        // server event is still immutable audit evidence; captureOutstanding submits that edit
        // with this accepted revision as its base.
        updateProjection = record.currentVersionId == head.submittedCurrentVersionId,
    )
  }

  private fun current(owner: String, epoch: Long, response: BackendResponse): Boolean =
      response.owner == owner && response.sessionEpoch == epoch && currentSnapshot(owner, epoch)

  private fun currentSnapshot(owner: String, epoch: Long): Boolean =
      sessions.snapshot()?.let { it.tokens.userId == owner && it.epoch == epoch } == true &&
          sync.owner() == owner

  private suspend fun allowed(owner: String, epoch: Long): Boolean =
      currentSnapshot(owner, epoch) &&
          sync.supportsHealthLedger() &&
          consent.observe().first().backendSyncEnabled &&
          !dao.hasActiveWorkout()

  private fun pagePath(full: Boolean, after: String?, token: String?): String {
    val query =
        buildList {
              add("limit=500")
              if (!full) add("after=${encode(requireNotNull(after))}")
              token?.let { add("pageToken=${encode(it)}") }
            }
            .joinToString("&")
    return if (full) "$SNAPSHOT_PATH?$query" else "$CHANGES_PATH?$query"
  }

  private fun encode(value: String): String = URLEncoder.encode(value, UTF_8.name())

  private fun parsePage(value: JsonElement): HealthPage? {
    val obj = value as? JsonObject ?: return null
    if (obj.keys != setOf("versions", "heads", "nextPageToken", "commitCursor")) return null
    val versions =
        (obj["versions"] as? JsonArray)?.map {
          parseVersion(it as? JsonObject ?: return null) ?: return null
        } ?: return null
    val heads =
        (obj["heads"] as? JsonArray)?.map {
          parseHead(it as? JsonObject ?: return null) ?: return null
        } ?: return null
    val next = (nullableString(obj, "nextPageToken", 4096) ?: return null).value
    val cursor = (nullableString(obj, "commitCursor", 4096) ?: return null).value
    if (next?.isEmpty() == true || cursor?.isEmpty() == true) return null
    if ((next == null) == (cursor == null) || versions.size + heads.size > 500) return null
    if (
        versions.map { it.healthRevision }.zipWithNext().any { (a, b) -> a >= b } ||
            heads.map { it.healthRevision }.zipWithNext().any { (a, b) -> a >= b } ||
            (versions.map { it.healthRevision } + heads.map { it.healthRevision })
                .distinct()
                .size != versions.size + heads.size ||
            versions.map { it.versionId }.distinct().size != versions.size ||
            heads.map { "${it.logicalId}:${it.headRevision}" }.distinct().size != heads.size
    )
        return null
    return HealthPage(versions, heads, next, cursor)
  }

  private fun parseVersion(obj: JsonObject): HealthVersion? {
    val keys =
        setOf(
            "versionId",
            "logicalId",
            "parentVersionId",
            "kind",
            "state",
            "enteredAtEpochMs",
            "payload",
            "serverSequence",
            "healthRevision",
        )
    if (obj.keys != keys) return null
    val id = uuid(obj, "versionId") ?: return null
    val logical = uuid(obj, "logicalId") ?: return null
    val parentField = nullableString(obj, "parentVersionId", 64) ?: return null
    val parent = parentField.value
    if (parent != null && !CANONICAL_UUID.matches(parent)) return null
    val kind = healthKind(string(obj, "kind")) ?: return null
    val state = enum(obj, "state", setOf("CONFIRMED", "TOMBSTONE")) ?: return null
    val entered = long(obj, "enteredAtEpochMs", nonNegative = true) ?: return null
    val sequence = long(obj, "serverSequence", positive = true) ?: return null
    val revision = long(obj, "healthRevision", positive = true) ?: return null
    val payload = obj["payload"]
    if ((state == "TOMBSTONE") != (payload is JsonNull)) return null
    if (
        state == "CONFIRMED" &&
            (payload !is JsonObject || HealthLedgerValidator.payloadFromJson(kind, payload) == null)
    )
        return null
    return HealthVersion(
        id,
        logical,
        parent,
        kind,
        state,
        entered,
        payload as? JsonObject,
        sequence,
        revision,
        obj,
    )
  }

  private fun parseHead(obj: JsonObject): HealthHead? {
    if (
        obj.keys !=
            setOf(
                "logicalId",
                "currentVersionId",
                "headRevision",
                "kind",
                "deleted",
                "healthRevision",
            )
    )
        return null
    val logical = uuid(obj, "logicalId") ?: return null
    val current = uuid(obj, "currentVersionId") ?: return null
    val revision = long(obj, "headRevision", positive = true) ?: return null
    val health = long(obj, "healthRevision", positive = true) ?: return null
    val kind = healthKind(string(obj, "kind")) ?: return null
    val deleted =
        (obj["deleted"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
    return HealthHead(logical, current, revision, kind, deleted, health, obj)
  }

  private fun parseOperation(bytes: ByteArray): PendingOperation? =
      runCatching {
            val obj = HealthWireJson.objectFrom(bytes) ?: return null
            if (obj.keys != setOf("operationId", "versions", "heads")) return null
            val operationId = uuid(obj, "operationId") ?: return null
            val versions = obj["versions"]?.jsonArray?.map { it.jsonObject } ?: return null
            val heads = obj["heads"]?.jsonArray?.map { it.jsonObject } ?: return null
            if (versions.isEmpty() || versions.size > 500 || heads.size > 500) return null
            val versionIds = versions.map { parsePendingVersion(it) ?: return null }
            val headTargets = heads.map { parsePendingHead(it) ?: return null }
            if (
                versionIds.distinct().size != versionIds.size ||
                    headTargets.distinct().size != headTargets.size
            )
                return null
            PendingOperation(
                operationId,
                versionIds,
                headTargets,
            )
          }
          .getOrNull()

  private fun parsePendingVersion(obj: JsonObject): String? {
    if (
        obj.keys !=
            setOf(
                "versionId",
                "logicalId",
                "parentVersionId",
                "kind",
                "state",
                "enteredAtEpochMs",
                "payload",
            )
    )
        return null
    val versionId = uuid(obj, "versionId") ?: return null
    uuid(obj, "logicalId") ?: return null
    nullableString(obj, "parentVersionId", 64)?.value?.let { parent ->
      if (!CANONICAL_UUID.matches(parent)) return null
    }
    val kind = healthKind(string(obj, "kind")) ?: return null
    val state = enum(obj, "state", setOf("CONFIRMED", "TOMBSTONE")) ?: return null
    if (long(obj, "enteredAtEpochMs", nonNegative = true) == null) return null
    val payload = obj["payload"]
    if ((state == "TOMBSTONE") != (payload is JsonNull)) return null
    if (
        state == "CONFIRMED" &&
            (payload !is JsonObject || HealthLedgerValidator.payloadFromJson(kind, payload) == null)
    )
        return null
    return versionId
  }

  private fun parsePendingHead(obj: JsonObject): Pair<String, String>? {
    if (obj.keys != setOf("logicalId", "currentVersionId", "baseHeadRevision")) return null
    val logicalId = uuid(obj, "logicalId") ?: return null
    val currentVersionId = uuid(obj, "currentVersionId") ?: return null
    if (long(obj, "baseHeadRevision", nonNegative = true) == null) return null
    return logicalId to currentVersionId
  }

  private fun parseOperationResult(value: JsonElement): HealthOperationResult? {
    val obj = value as? JsonObject ?: return null
    if (obj.keys != setOf("operationId", "versionReceipts", "headResults")) return null
    val id = uuid(obj, "operationId") ?: return null
    val receipts =
        obj["versionReceipts"]?.jsonArray?.map { receipt ->
          val item = receipt.jsonObject
          if (item.keys != setOf("versionId", "serverSequence", "healthRevision")) return null
          HealthReceipt(
              uuid(item, "versionId") ?: return null,
              long(item, "serverSequence", positive = true) ?: return null,
              long(item, "healthRevision", positive = true) ?: return null,
          )
        } ?: return null
    val heads =
        obj["headResults"]?.jsonArray?.map { item ->
          val h = item.jsonObject
          if (
              h.keys !=
                  setOf(
                      "logicalId",
                      "submittedCurrentVersionId",
                      "serverCurrentVersionId",
                      "headRevision",
                      "healthRevision",
                      "outcome",
                  )
          )
              return null
          HealthHeadResult(
              uuid(h, "logicalId") ?: return null,
              uuid(h, "submittedCurrentVersionId") ?: return null,
              uuid(h, "serverCurrentVersionId") ?: return null,
              long(h, "headRevision", positive = true) ?: return null,
              long(h, "healthRevision", positive = true) ?: return null,
              enum(h, "outcome", setOf("APPLIED", "ALREADY_CURRENT", "STALE")) ?: return null,
          )
        } ?: return null
    if (
        receipts.map { it.versionId }.distinct().size != receipts.size ||
            heads.map { it.logicalId }.distinct().size != heads.size
    )
        return null
    return HealthOperationResult(id, receipts, heads)
  }

  private fun string(obj: JsonObject, key: String): String? =
      (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

  private fun uuid(obj: JsonObject, key: String): String? =
      string(obj, key)?.takeIf { CANONICAL_UUID.matches(it) }

  /** null is valid JSON null; malformed/non-string nullable data is invalid. */
  private fun nullableString(obj: JsonObject, key: String, max: Int): NullableString? =
      when (val value = obj[key]) {
        JsonNull -> NullableString(null)
        is JsonPrimitive ->
            value
                .takeIf { it.isString }
                ?.content
                ?.takeIf { it.length <= max }
                ?.let(::NullableString)
        else -> null
      }

  private fun long(
      obj: JsonObject,
      key: String,
      positive: Boolean = false,
      nonNegative: Boolean = false,
  ): Long? =
      (obj[key] as? JsonPrimitive)
          ?.takeUnless { it.isString }
          ?.longOrNull
          ?.takeIf { (!positive || it > 0) && (!nonNegative || it >= 0) }

  private fun enum(obj: JsonObject, key: String, values: Set<String>): String? =
      string(obj, key)?.takeIf { it in values }

  private fun healthKind(value: String?): String? =
      when (value) {
        "health_report" -> "REPORT"
        "health_observation" -> "OBSERVATION"
        "health_restriction" -> "RESTRICTION"
        else -> null
      }

  private sealed interface Event {
    val healthRevision: Long

    data class Version(val value: HealthVersion) : Event {
      override val healthRevision
        get() = value.healthRevision
    }

    data class Head(val value: HealthHead) : Event {
      override val healthRevision
        get() = value.healthRevision
    }
  }

  private data class HealthPage(
      val versions: List<HealthVersion>,
      val heads: List<HealthHead>,
      val nextPageToken: String?,
      val commitCursor: String?,
  )

  private data class HealthVersion(
      val versionId: String,
      val logicalId: String,
      val parentVersionId: String?,
      val kind: String,
      val state: String,
      val enteredAtEpochMs: Long,
      val payload: JsonObject?,
      val serverSequence: Long,
      val healthRevision: Long,
      val raw: JsonObject,
  ) {
    fun matches(value: HealthRecordVersionEntity): Boolean =
        value.logicalId == logicalId &&
            value.parentVersionId == parentVersionId &&
            value.kind == kind &&
            value.state == state &&
            value.enteredAtEpochMs == enteredAtEpochMs &&
            runCatching {
                  Json.parseToJsonElement(value.payloadJson ?: "null") == (payload ?: JsonNull)
                }
                .getOrDefault(false) &&
            (value.serverSequence == null || value.serverSequence == serverSequence) &&
            (value.healthRevision == null || value.healthRevision == healthRevision)
  }

  private data class HealthHead(
      val logicalId: String,
      val currentVersionId: String,
      val headRevision: Long,
      val kind: String,
      val deleted: Boolean,
      val healthRevision: Long,
      val raw: JsonObject,
  )

  private data class PendingOperation(
      val operationId: String,
      val versionIds: List<String>,
      val heads: List<Pair<String, String>>,
  )

  private data class HealthReceipt(
      val versionId: String,
      val serverSequence: Long,
      val healthRevision: Long,
  )

  private data class HealthOperationResult(
      val operationId: String,
      val receipts: List<HealthReceipt>,
      val heads: List<HealthHeadResult>,
  )

  private data class HealthHeadResult(
      val logicalId: String,
      val submittedCurrentVersionId: String,
      val serverCurrentVersionId: String,
      val headRevision: Long,
      val healthRevision: Long,
      val outcome: String,
  )

  private object Rejected : IllegalStateException()

  private data class NullableString(val value: String?)

  companion object {
    private val CANONICAL_UUID =
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    const val CAPABILITY = "health-ledger-v1"
    const val SNAPSHOT_PATH = "/health-ledger/snapshot"
    const val CHANGES_PATH = "/health-ledger/changes"
    const val OPERATIONS_PATH = "/health-ledger/operations"
    const val MAX_RESPONSE_BYTES = 5_242_880
    val capabilityHeaders = mapOf("X-Gym-Capabilities" to CAPABILITY)
  }
}
