package com.valerochka1337.valerochkagym.data.coachrelation

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.trainingproposal.*
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Singleton
class CoachRelationsRepository
@Inject
constructor(
    private val db: GymDatabase,
    private val api: CoachRelationsApi,
    private val sessions: BackendSessionStore,
) {
  private val mutex = Mutex()
  private val dao
    get() = db.coachRelationOperationDao()

  fun session() =
      sessions.snapshot() ?: throw BackendException(401, "unauthorized", "Войдите в аккаунт")

  fun current(s: BackendSessionSnapshot) = api.current(s)

  suspend fun directory(s: BackendSessionSnapshot, clients: Boolean, cursor: String? = null) =
      api.directory(s, clients, cursor)

  suspend fun calendar(s: BackendSessionSnapshot, id: String, cursor: String? = null) =
      api.calendar(s, id, cursor)

  suspend fun completed(s: BackendSessionSnapshot, id: String, cursor: String? = null) =
      api.completed(s, id, cursor)

  suspend fun operations(s: BackendSessionSnapshot): List<CoachRelationOperationEntity> =
      withContext(Dispatchers.IO) {
        api.guard(s)
        dao.list(s.tokens.userId).also { api.guard(s) }
      }

  suspend fun createInvite(
      s: BackendSessionSnapshot,
      id: String = UUID.randomUUID().toString(),
  ): InvitationCreated {
    val result =
        mutate(
            s,
            id,
            "CREATE_INVITE",
            "POST",
            "/invitations",
            s.tokens.userId,
            encode(Operation(id)),
            secret = true,
        ) { bytes ->
          val r = ProposalWire.decode<InvitationCreated>(bytes)
          require(ProposalWire.uuid(r.inviteId) && TOKEN.matches(r.token) && r.expiresAtMillis >= 0)
          buildJsonObject {
                put("inviteId", r.inviteId)
                put("expiresAtMillis", r.expiresAtMillis)
              }
              .toString()
        }
    return ProposalWire.decode(result)
  }

  suspend fun acceptInvite(
      s: BackendSessionSnapshot,
      token: String,
      calendar: Boolean,
      completed: Boolean,
      id: String = UUID.randomUUID().toString(),
  ): Relation {
    require(TOKEN.matches(token))
    val bytes =
        mutate(
            s,
            id,
            "ACCEPT_INVITE",
            "POST",
            "/invitations/accept",
            "invitation",
            encode(InvitationAcceptRequest(id, token, calendar, completed)),
            true,
        ) { raw ->
          relation(raw)
          raw.decodeToString()
        }
    return relation(bytes)
  }

  suspend fun revoke(
      s: BackendSessionSnapshot,
      relationId: String,
      id: String = UUID.randomUUID().toString(),
  ): Relation {
    CoachRelationsApi.id(relationId)
    return relation(
        mutate(
            s,
            id,
            "REVOKE_RELATION",
            "POST",
            "/$relationId/revoke",
            relationId,
            encode(Operation(id)),
        ) { raw ->
          val r = relation(raw)
          require(r.relationId == relationId && r.state == "REVOKED")
          raw.decodeToString()
        }
    )
  }

  suspend fun createProposal(
      s: BackendSessionSnapshot,
      relationId: String,
      ownerRevision: Long,
      catalogRevision: Long,
      draft: ApprovalDraft,
      id: String = UUID.randomUUID().toString(),
  ): TrainingProposal {
    require(ownerRevision >= 0 && catalogRevision >= 0 && ProposalWire.validDraft(draft))
    CoachRelationsApi.id(relationId)
    return proposal(
        s,
        mutate(
            s,
            id,
            "CREATE_COACH_PROPOSAL",
            "POST",
            "/$relationId/training-proposals",
            relationId,
            encode(
                CoachProposalCreateRequest(
                    id,
                    ownerRevision,
                    catalogRevision,
                    ProposalWire.decode<ApprovalRequest>(
                            ProposalWire.canonical(ApprovalRequest(id, 1, draft))
                        )
                        .draft,
                )
            ),
        ) { raw ->
          proposal(s, raw)
          raw.decodeToString()
        },
    )
  }

  suspend fun reviseProposal(
      s: BackendSessionSnapshot,
      relationId: String,
      p: TrainingProposal,
      draft: ApprovalDraft,
      id: String = UUID.randomUUID().toString(),
  ): TrainingProposal {
    CoachRelationsApi.id(relationId)
    CoachRelationsApi.id(p.proposalId)
    require(ProposalWire.validDraft(draft))
    return proposal(
        s,
        mutate(
            s,
            id,
            "REVISE_COACH_PROPOSAL",
            "PUT",
            "/$relationId/training-proposals/${p.proposalId}",
            "$relationId,${p.proposalId}",
            encode(
                CoachProposalReviseRequest(
                    id,
                    p.currentVersion,
                    ProposalWire.decode<ApprovalRequest>(
                            ProposalWire.canonical(ApprovalRequest(id, 1, draft))
                        )
                        .draft,
                )
            ),
        ) { raw ->
          val r = proposal(s, raw)
          require(r.proposalId == p.proposalId)
          raw.decodeToString()
        },
    )
  }

  suspend fun revokeProposal(
      s: BackendSessionSnapshot,
      relationId: String,
      p: TrainingProposal,
      id: String = UUID.randomUUID().toString(),
  ): ProposalDecision {
    CoachRelationsApi.id(relationId)
    CoachRelationsApi.id(p.proposalId)
    val raw =
        mutate(
            s,
            id,
            "REVOKE_COACH_PROPOSAL",
            "POST",
            "/$relationId/training-proposals/${p.proposalId}/revoke",
            "$relationId,${p.proposalId}",
            encode(CoachProposalRevokeRequest(id, p.currentVersion)),
        ) { raw ->
          val d = ProposalWire.decode<ProposalDecision>(raw)
          require(
              d.proposalId == p.proposalId &&
                  d.version == p.currentVersion &&
                  d.status == ProposalStatus.REVOKED
          )
          raw.decodeToString()
        }
    return ProposalWire.decode(raw)
  }

  /** Explicit retry uses the exact immutable route/body; secret operations cannot be replayed. */
  suspend fun retry(s: BackendSessionSnapshot, id: String): ByteArray =
      withContext(Dispatchers.IO) {
        val op = dao.get(s.tokens.userId, id) ?: error("Нет операции")
        val bytes =
            op.firstSendBytes
                ?: throw BackendException(
                    409,
                    "invite_token_not_replayable",
                    "Введите приглашение заново",
                )
        require(op.state == "PENDING" && digest(bytes) == op.rawSha256)
        val method = op.route.substringBefore(' ')
        val path = op.route.substringAfter(" /v1").removePrefix(CoachRelationsApi.ROOT)
        mutate(s, id, op.action, method, path, op.resource, bytes) { raw ->
          when (op.action) {
            "REVOKE_RELATION" -> require(relation(raw).relationId == op.resource)
            "CREATE_COACH_PROPOSAL",
            "REVISE_COACH_PROPOSAL" -> proposal(s, raw)
            "REVOKE_COACH_PROPOSAL" ->
                require(ProposalWire.decode<ProposalDecision>(raw).status == ProposalStatus.REVOKED)
            else -> error("Неподдерживаемое действие")
          }
          raw.decodeToString()
        }
      }

  private suspend fun mutate(
      s: BackendSessionSnapshot,
      id: String,
      action: String,
      method: String,
      part: String,
      resource: String,
      bytes: ByteArray,
      secret: Boolean = false,
      sanitize: (ByteArray) -> String,
  ): ByteArray =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          CoachRelationsApi.id(id)
          api.guard(s)
          val path = CoachRelationsApi.ROOT + part
          val row =
              CoachRelationOperationEntity(
                  s.tokens.userId,
                  id,
                  action,
                  "$method /v1$path",
                  resource,
                  digest(bytes),
                  if (secret) null else bytes.copyOf(),
                  "PENDING",
                  null,
              )
          db.withTransaction {
            api.guard(s)
            val held = dao.get(row.owner, id)
            if (held == null) {
              if (
                  !secret &&
                      dao.list(row.owner).any {
                        it.state == "PENDING" &&
                            it.firstSendBytes != null &&
                            it.action == action &&
                            it.resource == resource
                      }
              )
                  throw BackendException(
                      409,
                      "relation_pending",
                      "Сначала повторите ожидающую операцию",
                  )
              dao.insert(row)
            } else {
              if (
                  held.action != action ||
                      held.route != row.route ||
                      held.resource != resource ||
                      held.rawSha256 != row.rawSha256 ||
                      (!secret && !held.firstSendBytes.contentEquals(bytes))
              )
                  throw BackendException(409, "relation_operation_conflict", "Операция изменена")
              if (secret)
                  throw BackendException(
                      409,
                      "invite_token_not_replayable",
                      "Создайте или введите приглашение заново",
                  )
            }
          }
          val result =
              try {
                api.raw(s, method, path, bytes)
              } catch (error: BackendException) {
                if (error.status in setOf(400, 403, 404, 409, 410, 422)) {
                  db.withTransaction {
                    api.guard(s)
                    dao.finish(row.owner, id, "FAILED", null)
                  }
                }
                throw error
              }
          val sanitized = sanitize(result)
          db.withTransaction {
            api.guard(s)
            dao.finish(row.owner, id, "SUCCEEDED", sanitized)
          }
          api.guard(s)
          result
        }
      }

  private fun relation(raw: ByteArray) =
      ProposalWire.decode<Relation>(raw).also { require(CoachRelationsApi.validRelation(it)) }

  private fun proposal(s: BackendSessionSnapshot, raw: ByteArray) =
      ProposalWire.decode<TrainingProposal>(raw).also {
        require(
            ProposalWire.valid(it) &&
                it.source == ProposalSource.COACH &&
                it.author.accountId == s.tokens.userId
        )
      }

  private inline fun <reified T> encode(value: T) =
      ProposalWire.json.encodeToString(value).encodeToByteArray()

  companion object {
    private val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")

    fun digest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun restartable(error: BackendException) =
        when (error.status) {
          400 -> error.code == "invalid_cursor"
          410 -> error.code in setOf("cursor_expired", "cursor_key_retired")
          409 -> error.code == "relation_snapshot_changed"
          else -> false
        }
  }
}
