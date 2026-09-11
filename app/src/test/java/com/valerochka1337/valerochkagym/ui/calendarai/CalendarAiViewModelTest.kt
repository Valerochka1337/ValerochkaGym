package com.valerochka1337.valerochkagym.ui.calendarai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.ai.CalendarAiRepository
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.profile.AiProfilePromptGate
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.*
import com.valerochka1337.valerochkagym.service.WallClock
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CalendarAiViewModelTest : RoomDaoTest() {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private suspend fun vm(sessions: Sessions = Sessions()): CalendarAiViewModel {
    val api = Server()
    val clock = WallClock { 100L }
    val source =
        object : SyncReadySource {
          override suspend fun await(): SyncReady = error("No network during enqueue")
        }
    val sync = BackendSync(db, api, sessions)
    sync.claim("owner")
    return CalendarAiViewModel(
        com.valerochka1337.valerochkagym.data.ai.WorkoutPreparationRepository(
            db,
            sessions,
            sync,
            CalendarAiRepository(api, source, db, clock),
            source,
            api,
            clock,
        ),
        sessions,
        sync,
        AiProfilePromptGate(SettingsRepository(Store()), Profile(), clock),
        clock,
        TestExercises(db.exerciseDao()),
        TestGyms(db.gymDao()),
    )
  }

  @Test
  fun `saving offline closes form only after durable enqueue and preserves conditions`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.uiState.collect {}
        }
        val saved = CompletableDeferred<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.saved.collect { saved.complete(it) }
        }
        advanceUntilIdle()
        viewModel.setDuration("75")
        viewModel.generate()
        advanceUntilIdle()
        saved.await()
        assertTrue(saved.isCompleted)
        assertNotNull(db.preparationDao().get("owner"))
        assertEquals("WAITING", db.preparationDao().get("owner")!!.state)
        assertFalse(viewModel.uiState.value.generating)
        assertNull(viewModel.uiState.value.error)
        viewModel.viewModelScope.cancel()
      }

  @Test
  fun `invalid date keeps form open without saving a request`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.uiState.collect {}
        }
        advanceUntilIdle()
        viewModel.setDate("invalid")
        viewModel.generate()
        advanceUntilIdle()
        assertNull(db.preparationDao().get("owner"))
        assertNotNull(viewModel.uiState.value.error)
        viewModel.viewModelScope.cancel()
      }

  @Test
  fun `account change clears private form fields`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val sessions = Sessions()
        val viewModel = vm(sessions)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.uiState.collect {}
        }
        advanceUntilIdle()
        viewModel.setPreferences("Первый аккаунт")
        sessions.save(BackendTokens("other", "", "", ""))
        advanceUntilIdle()
        assertEquals("", viewModel.uiState.value.form.preferences)
        viewModel.viewModelScope.cancel()
      }
}

private class Sessions : BackendSessionStore {
  override val session = MutableStateFlow<BackendTokens?>(BackendTokens("owner", "", "", ""))
  override var sessionEpoch = 0L

  override fun save(tokens: BackendTokens?) {
    sessionEpoch++
    session.value = tokens
  }
}

private class Server : BackendTransport {
  override val json = Json

  override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement =
      error("unexpected HTTP")

  override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement =
      error("unexpected HTTP")
}

private class Store : DataStore<Preferences> {
  override val data = MutableStateFlow<Preferences>(emptyPreferences())

  override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
      transform(data.value).also { data.value = it }
}

private class Profile : ProfileRepository {
  override fun observeCurrent(): Flow<ProfileEditorSnapshot?> = flowOf(null)

  override suspend fun openEditor(): ProfileEditorSnapshot? = null

  override fun observe(target: ProfileEditTarget): Flow<BasicProfile?> = flowOf(null)

  override suspend fun save(target: ProfileEditTarget, profile: BasicProfile) =
      ProfileSaveResult.Saved
}

private class TestExercises(
    private val delegate: com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
) : com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao by delegate {
  override fun getAll() =
      flowOf(emptyList<com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity>())
}

private class TestGyms(private val delegate: com.valerochka1337.valerochkagym.data.db.dao.GymDao) :
    com.valerochka1337.valerochkagym.data.db.dao.GymDao by delegate {
  override fun observeGyms() =
      flowOf(emptyList<com.valerochka1337.valerochkagym.data.db.entity.GymEntity>())
}
