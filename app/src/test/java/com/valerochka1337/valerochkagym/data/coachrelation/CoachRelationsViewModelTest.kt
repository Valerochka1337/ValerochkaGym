package com.valerochka1337.valerochkagym.data.coachrelation

import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalWire
import com.valerochka1337.valerochkagym.ui.coachrelation.CoachRelationsViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CoachRelationsViewModelTest : RoomDaoTest() {
  @get:Rule val main = MainDispatcherRule()

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
    var denied = false
    var stale = false
    var calendarCalls = 0

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
      val s = store.snapshot()!!
      val value =
          if (path.contains("/calendar")) {
            calendarCalls++
            if (denied) throw BackendException(404, "relation_not_found", "")
            if (stale && path.contains("cursor=")) {
              stale = false
              throw BackendException(409, "relation_snapshot_changed", "")
            }
            json.encodeToJsonElement(
                CalendarProjectionPage(
                    listOf(CalendarProjectionItem(PLAN, ROUTINE, 1, "UTC", "План", emptyList())),
                    "next",
                    if (calendarCalls > 1) 2 else 1,
                )
            )
          } else
              json.encodeToJsonElement(
                  DirectoryPage(listOf(Relation(REL, B, "ACTIVE", true, true, 1, null)), null, 1)
              )
      return BackendResponse(
          value,
          value.toString().encodeToByteArray(),
          emptySet(),
          s.tokens.userId,
          s.epoch,
      )
    }
  }

  private fun vm(s: Store, server: Server) =
      CoachRelationsViewModel(
          CoachRelationsRepository(db, CoachRelationsApi(server, s), s),
          s,
          BackendSync(db, server, s),
          db,
          db.exerciseDao(),
      )

  @Test
  fun `terminal relation denial clears loaded projection and capability without restart`() =
      runTest(main.testDispatcher.scheduler) {
        val s = Store()
        val server = Server(s)
        val vm = vm(s, server)
        val collect = backgroundScope.launch { vm.uiState.collect() }
        try {
          vm.refresh(true, REL)
          vm.uiState.first { !it.busy && it.relation != null }
          vm.projection(true)
          vm.uiState.first { !it.busy && it.calendar.isNotEmpty() }
          server.denied = true
          vm.projection(true, true)
          val state = vm.uiState.first { !it.busy && it.error != null }
          assertNull(state.relation)
          assertTrue(state.calendar.isEmpty())
          assertTrue(state.completed.isEmpty())
          assertEquals(2, server.calendarCalls)
        } finally {
          collect.cancel()
          vm.viewModelScope.cancel()
        }
      }

  @Test
  fun `changed projection revision restarts once and account switch clears pages`() =
      runTest(main.testDispatcher.scheduler) {
        val s = Store()
        val server = Server(s)
        val vm = vm(s, server)
        val collect = backgroundScope.launch { vm.uiState.collect() }
        try {
          vm.refresh(true, REL)
          vm.uiState.first { !it.busy && it.relation != null }
          vm.projection(true)
          vm.uiState.first { !it.busy && it.calendar.isNotEmpty() }
          server.stale = true
          vm.projection(true, true)
          val state = vm.uiState.first { !it.busy && it.recipientRevision == 2L }
          assertEquals(1, state.calendar.size)
          assertEquals(3, server.calendarCalls)
          s.save(s.session.value?.copy(userId = B))
          val cleared = vm.uiState.first { it.relation == null }
          assertTrue(cleared.calendar.isEmpty())
          assertTrue(cleared.relations.isEmpty())
        } finally {
          collect.cancel()
          vm.viewModelScope.cancel()
        }
      }

  companion object {
    const val A = "11111111-1111-4111-8111-111111111111"
    const val B = "22222222-2222-4222-8222-222222222222"
    const val REL = "33333333-3333-4333-8333-333333333333"
    const val PLAN = "44444444-4444-4444-8444-444444444444"
    const val ROUTINE = "55555555-5555-4555-8555-555555555555"
  }
}
