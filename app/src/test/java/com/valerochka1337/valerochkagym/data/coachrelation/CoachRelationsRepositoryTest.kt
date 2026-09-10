package com.valerochka1337.valerochkagym.data.coachrelation

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.trainingproposal.*
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CoachRelationsRepositoryTest : RoomDaoTest() {
  private class Store : BackendSessionStore {
    override val session = MutableStateFlow<BackendTokens?>(BackendTokens(A, "a@b", "a", "r"))
    override var sessionEpoch = 1L

    override fun save(tokens: BackendTokens?) {
      sessionEpoch++
      session.value = tokens
    }
  }

  private class Server(val store: Store) : BackendTransport {
    override val json = ProposalWire.json
    val bodies = mutableListOf<ByteArray>()
    var fail = false
    var hook: () -> Unit = {}
    var custom: ByteArray? = null

    override suspend fun public(method: String, path: String, body: JsonElement?) = error("unused")

    override suspend fun authorized(method: String, path: String, body: JsonElement?) =
        error("unused")

    override suspend fun authorizedRawResponse(
        method: String,
        path: String,
        rawBody: ByteArray,
        headers: Map<String, String>,
        expectedOwner: String?,
        expectedSessionEpoch: Long?,
        retryOnUnauthorized: Boolean,
        maxResponseBytes: Int?,
    ): BackendResponse {
      assertFalse(retryOnUnauthorized)
      assertEquals(1048576, maxResponseBytes)
      val owner = store.snapshot()!!
      bodies += rawBody.copyOf()
      hook()
      if (fail) {
        fail = false
        throw IOException("lost")
      }
      val data =
          custom
              ?: when {
                path.endsWith("/invitations") ->
                    json
                        .encodeToJsonElement(InvitationCreated(INVITE, TOKEN, 10000))
                        .toString()
                        .encodeToByteArray()
                path.endsWith("/invitations/accept") ->
                    json.encodeToJsonElement(relation()).toString().encodeToByteArray()
                path.endsWith("/revoke") ->
                    json
                        .encodeToJsonElement(
                            relation().copy(state = "REVOKED", revokedAtMillis = 2)
                        )
                        .toString()
                        .encodeToByteArray()
                else -> error("unexpected")
              }
      return BackendResponse(
          json.parseToJsonElement(data.decodeToString()),
          data,
          emptySet(),
          owner.tokens.userId,
          owner.epoch,
      )
    }
  }

  private fun repo(s: Store, server: Server) =
      CoachRelationsRepository(db, CoachRelationsApi(server, s), s)

  @Test
  fun `invitation create and accept never persist token or raw secret request`() = runTest {
    val store = Store()
    val server = Server(store)
    val repo = repo(store, server)
    val s = store.snapshot()!!
    assertEquals(TOKEN, repo.createInvite(s).token)
    assertTrue(repo.acceptInvite(s, TOKEN, true, false).calendar)
    val rows = db.coachRelationOperationDao().list(A)
    assertEquals(2, rows.size)
    rows.forEach {
      assertNull(it.firstSendBytes)
      assertFalse(it.toString().contains(TOKEN))
      assertEquals("SUCCEEDED", it.state)
    }
    val held = rows.first()
    assertTrue(
        runCatching { repo.retry(s, held.operationId) }.exceptionOrNull() is BackendException
    )
    assertEquals(2, server.bodies.size)
  }

  @Test
  fun `lost revoke response replays exact persisted bytes after recreation`() = runTest {
    val s = Store()
    val server = Server(s)
    val repo = repo(s, server)
    server.fail = true
    assertTrue(
        runCatching { repo.revoke(s.snapshot()!!, REL, OP) }.exceptionOrNull() is IOException
    )
    val held = db.coachRelationOperationDao().get(A, OP)!!
    assertEquals("PENDING", held.state)
    repo(s, server).retry(s.snapshot()!!, OP)
    assertArrayEquals(held.firstSendBytes, server.bodies.last())
    assertArrayEquals(server.bodies.first(), server.bodies.last())
    assertEquals("SUCCEEDED", db.coachRelationOperationDao().get(A, OP)!!.state)
  }

  @Test
  fun `changed route and changed secret body never dispatch under an existing operation`() =
      runTest {
        val s = Store()
        val server = Server(s)
        val repo = repo(s, server)
        server.fail = true
        runCatching { repo.revoke(s.snapshot()!!, REL, OP) }
        assertTrue(
            runCatching { repo.revoke(s.snapshot()!!, INVITE, OP) }.exceptionOrNull()
                is BackendException
        )
        assertEquals(1, server.bodies.size)
        repo.acceptInvite(s.snapshot()!!, TOKEN, true, false, INVITE)
        assertTrue(
            runCatching { repo.acceptInvite(s.snapshot()!!, TOKEN, false, true, INVITE) }
                .exceptionOrNull() is BackendException
        )
        assertEquals(2, server.bodies.size)
      }

  @Test
  fun `late owner round trip cannot store or expose relation response`() = runTest {
    val s = Store()
    val server = Server(s)
    val repo = repo(s, server)
    server.hook = {
      val original = s.session.value
      s.save(original?.copy(userId = B))
      s.save(original)
    }
    assertTrue(
        runCatching { repo.createInvite(s.snapshot()!!, OP) }.exceptionOrNull() is BackendException
    )
    val row = db.coachRelationOperationDao().get(A, OP)!!
    assertNull(row.resultJson)
    assertNull(row.firstSendBytes)
    s.save(s.session.value?.copy(userId = B))
    assertTrue(repo.operations(s.snapshot()!!).isEmpty())
  }

  @Test
  fun `strict completed projection preserves explicit null zero and rejects private fields`() =
      runTest {
        val s = Store()
        val server = Server(s)
        val api = CoachRelationsApi(server, s)
        val set = ProjectionSet(null, 0, 0, 0.0, 0.0)
        val page =
            CompletedWorkoutProjectionPage(
                listOf(
                    CompletedWorkoutProjectionItem(
                        INVITE,
                        100,
                        listOf(CompletedWorkoutExercise(OP, "Жим", listOf(set))),
                    )
                ),
                null,
                3,
            )
        server.custom = ProposalWire.json.encodeToJsonElement(page).toString().encodeToByteArray()
        val result = api.completed(s.snapshot()!!, REL, null)
        assertNull(result.items.single().exercises.single().sets.single().weightKg)
        assertEquals(0L, result.items.single().exercises.single().sets.single().reps)
        server.custom =
            server.custom!!
                .decodeToString()
                .replace("\"name\":\"Жим\"", "\"name\":\"Жим\",\"note\":\"private\"")
                .encodeToByteArray()
        assertTrue(runCatching { api.completed(s.snapshot()!!, REL, null) }.isFailure)
      }

  @Test
  fun `projection bounds accept empty and maximum arrays without truncation`() = runTest {
    val s = Store()
    val server = Server(s)
    val api = CoachRelationsApi(server, s)
    val item =
        CompletedWorkoutProjectionItem(
            INVITE,
            1,
            List(200) { CompletedWorkoutExercise(OP, "Жим", emptyList()) },
        )
    var page = CompletedWorkoutProjectionPage(listOf(item), null, 0)
    server.custom = ProposalWire.json.encodeToJsonElement(page).toString().encodeToByteArray()
    assertEquals(200, api.completed(s.snapshot()!!, REL, null).items.single().exercises.size)
    page =
        page.copy(
            items =
                listOf(
                    item.copy(
                        exercises =
                            listOf(
                                CompletedWorkoutExercise(
                                    OP,
                                    "Жим",
                                    List(1000) { ProjectionSet(null, null, null, null, null) },
                                )
                            )
                    )
                )
        )
    server.custom = ProposalWire.json.encodeToJsonElement(page).toString().encodeToByteArray()
    assertEquals(
        1000,
        api.completed(s.snapshot()!!, REL, null).items.single().exercises.single().sets.size,
    )
  }

  @Test
  fun `only frozen cursor errors permit restart and revoked relation is terminal`() {
    assertTrue(
        CoachRelationsRepository.restartable(BackendException(409, "relation_snapshot_changed", ""))
    )
    assertTrue(CoachRelationsRepository.restartable(BackendException(410, "cursor_expired", "")))
    assertFalse(
        CoachRelationsRepository.restartable(BackendException(404, "relation_not_found", ""))
    )
    assertFalse(
        CoachRelationsRepository.restartable(
            BackendException(409, "relation_operation_conflict", "")
        )
    )
  }

  @Test
  fun `pending coach creation survives recreation and blocks a second intent until exact retry`() =
      runTest {
        val store = Store()
        val server = Server(store)
        val repository = repo(store, server)
        val session = store.snapshot()!!
        val draft =
            ApprovalDraft(
                "Жим",
                emptyList(),
                listOf(
                    ProposalPlannedExercise(
                        OP,
                        60,
                        listOf(ProposalPlannedSet(null, 10, null, null, null)),
                    )
                ),
                1893456000000,
                "UTC",
            )
        val proposal =
            TrainingProposal(
                INVITE,
                ProposalAuthor(ProposalSource.COACH, A),
                B,
                ProposalSource.COACH,
                ProposalStatus.PENDING,
                1,
                1,
                1,
                9999999999999,
                ProposalSnapshot(1, draft, 1, 2, 1),
            )
        server.custom =
            ProposalWire.json.encodeToJsonElement(proposal).toString().encodeToByteArray()
        server.fail = true
        assertTrue(
            runCatching { repository.createProposal(session, REL, 1, 2, draft, OP) }
                .exceptionOrNull() is IOException
        )
        assertTrue(
            runCatching { repository.createProposal(session, REL, 1, 2, draft, INVITE) }
                .exceptionOrNull() is BackendException
        )
        assertEquals(1, server.bodies.size)
        repo(store, server).retry(session, OP)
        assertArrayEquals(server.bodies.first(), server.bodies.last())
        assertEquals("SUCCEEDED", db.coachRelationOperationDao().get(A, OP)!!.state)
      }

  companion object {
    const val A = "11111111-1111-4111-8111-111111111111"
    const val B = "22222222-2222-4222-8222-222222222222"
    const val REL = "33333333-3333-4333-8333-333333333333"
    const val INVITE = "44444444-4444-4444-8444-444444444444"
    const val OP = "55555555-5555-4555-8555-555555555555"
    val TOKEN = "a".repeat(43)

    fun relation() = Relation(REL, B, "ACTIVE", true, false, 1, null)
  }
}
