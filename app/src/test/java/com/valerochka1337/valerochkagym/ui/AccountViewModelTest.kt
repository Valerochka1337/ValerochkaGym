package com.valerochka1337.valerochkagym.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.ui.account.AccountViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountViewModelTest : RoomDaoTest() {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private class Store : BackendSessionStore {
    override val session = MutableStateFlow<BackendTokens?>(null)

    override fun save(tokens: BackendTokens?) {
      session.value = tokens
    }
  }

  private class Api : BackendTransport {
    override val json = Json
    var failure: BackendException? = null
    val calls = mutableListOf<String>()

    override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement {
      calls += path
      failure?.let { throw it }
      return buildJsonObject {}
    }

    override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement =
        error("Unexpected authorized request")
  }

  private fun model(api: Api): AccountViewModel {
    val store = Store()
    return AccountViewModel(
        api,
        store,
        BackendSync(db, api, store),
        BackendSyncScheduler(ApplicationProvider.getApplicationContext<Context>(), db),
    )
  }

  @Test
  fun `successful registration advances to email verification`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val api = Api()
        val vm = model(api)
        vm.showMode("register")
        vm.submit("register", "test@example.com", "long-password", "")
        advanceUntilIdle()
        assertEquals("verify", vm.mode.value)
        assertEquals(listOf("/auth/register"), api.calls)
        assertFalse(vm.busy.value)
      }

  @Test
  fun `mail failure keeps registration available for retry`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val api =
            Api().apply { failure = BackendException(503, "mail_unavailable", "Повторите позже") }
        val vm = model(api)
        vm.showMode("register")
        vm.submit("register", "test@example.com", "long-password", "")
        advanceUntilIdle()
        assertEquals("register", vm.mode.value)
        assertEquals("Повторите позже", vm.message.value)
        assertFalse(vm.busy.value)
      }

  @Test
  fun `unverified login opens the code form`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val api =
            Api().apply { failure = BackendException(403, "email_unverified", "Подтвердите email") }
        val vm = model(api)
        vm.submit("login", "test@example.com", "long-password", "")
        advanceUntilIdle()
        assertEquals("verify", vm.mode.value)
        assertNull(vm.session.value)
      }

  @Test
  fun `password recovery advances to reset and then back to login`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val vm = model(Api())
        vm.showMode("request-reset")
        vm.submit("request-reset", "test@example.com", "", "")
        advanceUntilIdle()
        assertEquals("reset", vm.mode.value)
        vm.submit("reset", "test@example.com", "new-long-password", "12345678")
        advanceUntilIdle()
        assertEquals("login", vm.mode.value)
        assertNotNull(vm.message.value)
        vm.showMode("register")
        assertNull(vm.message.value)
      }
}
