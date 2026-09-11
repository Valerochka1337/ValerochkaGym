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

  private fun vm(source: SyncReadySource, sessions: Sessions = Sessions()): CalendarAiViewModel {
    val api = Server()
    val clock = WallClock { 100L }
    return CalendarAiViewModel(
        CalendarAiRepository(api, source, db, clock),
        sessions,
        BackendSync(db, api, sessions),
        AiProfilePromptGate(SettingsRepository(Store()), Profile(), clock),
        clock,
        TestExercises(db.exerciseDao()),
        TestGyms(db.gymDao()),
    )
  }

  @Test
  fun `generation failure keeps edited form and exposes safe reason`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        for ((failure, expected) in
            listOf(
                BackendException(504, "ai_timeout", "SECRET") to "ai_timeout",
                java.io.IOException("SECRET") to "network_error",
                IllegalStateException("SECRET") to "ai_sync_failed",
            )) {
          val viewModel =
              vm(
                  object : SyncReadySource {
                    override suspend fun await() = SyncReady.Failure("SECRET", failure)
                  }
              )
          val collector =
              backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect {}
              }
          advanceUntilIdle()
          viewModel.setPreferences("Мои пожелания")
          viewModel.setDuration("75")
          advanceUntilIdle()
          val form = viewModel.uiState.value.form
          viewModel.generate()
          advanceUntilIdle()
          assertEquals(form, viewModel.uiState.value.form)
          assertFalse(viewModel.uiState.value.generating)
          assertTrue(viewModel.uiState.value.error!!.contains(expected))
          assertFalse(viewModel.uiState.value.error!!.contains("SECRET"))
          collector.cancel()
          viewModel.viewModelScope.cancel()
        }
      }

  @Test
  fun `cancelled generation clears progress without an error or navigation`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel =
            vm(
                object : SyncReadySource {
                  override suspend fun await(): SyncReady = throw CancellationException("SECRET")
                }
            )
        val proposals = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.uiState.collect {}
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.openProposal.collect { proposals += it }
        }
        advanceUntilIdle()
        viewModel.generate()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.generating)
        assertNull(viewModel.uiState.value.error)
        assertTrue(proposals.isEmpty())
        viewModel.viewModelScope.cancel()
      }

  @Test
  fun `late failure after account change cannot overwrite invalidated form`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val sessions = Sessions()
        val viewModel =
            vm(
                object : SyncReadySource {
                  override suspend fun await(): SyncReady =
                      withContext(NonCancellable) {
                        started.complete(Unit)
                        release.await()
                        throw BackendException(504, "ai_timeout", "SECRET")
                      }
                },
                sessions,
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          viewModel.uiState.collect {}
        }
        advanceUntilIdle()
        viewModel.setPreferences("Первый аккаунт")
        viewModel.generate()
        runCurrent()
        assertTrue(started.isCompleted)
        sessions.save(BackendTokens("other", "", "", ""))
        runCurrent()
        val invalidated = viewModel.uiState.value
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(invalidated, viewModel.uiState.value)
        assertEquals("", viewModel.uiState.value.form.preferences)
        assertFalse(viewModel.uiState.value.generating)
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
