package com.valerochka1337.valerochkagym.ui

import android.app.Activity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.ImportResult
import com.valerochka1337.valerochkagym.data.google.TokenResult
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepository
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.ui.settings.SettingsViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import com.valerochka1337.valerochkagym.worker.UploadScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [SettingsViewModel]. A [FakeGoogleAuth] stands in for Google flows and a real
 * [SettingsRepository] over an in-memory [DataStore] persists settings, so no Android framework is
 * needed. `uiState` is `stateIn(WhileSubscribed(5000))` and stays cold without a subscriber; every
 * test attaches a live collector via [collectUiState] before reading `uiState.value`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val validSpreadsheetId = "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"

    // region rest stepper

    @Test
    fun `changeDefaultRest adds the step`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = SettingsViewModel(settingsRepository(defaultRestSeconds = 120), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository())
        collectUiState(viewModel)

        viewModel.changeDefaultRest(15)

        assertEquals(135, viewModel.uiState.value.settings?.defaultRestSeconds)
    }

    @Test
    fun `changeDefaultRest subtracts the step`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = SettingsViewModel(settingsRepository(defaultRestSeconds = 120), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository())
        collectUiState(viewModel)

        viewModel.changeDefaultRest(-15)

        assertEquals(105, viewModel.uiState.value.settings?.defaultRestSeconds)
    }

    @Test
    fun `changeDefaultRest coerces to the minimum of fifteen`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = SettingsViewModel(settingsRepository(defaultRestSeconds = 20), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository())
            collectUiState(viewModel)

            viewModel.changeDefaultRest(-15)

            assertEquals(15, viewModel.uiState.value.settings?.defaultRestSeconds)
        }

    // endregion

    // region spreadsheet input

    @Test
    fun `setSpreadsheetInput persists the parsed id and clears the error`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository())
            collectUiState(viewModel)

            val url = "https://docs.google.com/spreadsheets/d/$validSpreadsheetId/edit#gid=0"
            viewModel.setSpreadsheetInput(url)

            assertEquals(validSpreadsheetId, viewModel.uiState.value.settings?.spreadsheetId)
            assertFalse(viewModel.uiState.value.spreadsheetError)
        }

    @Test
    fun `setSpreadsheetInput sets the error and does not persist on invalid input`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository())
            collectUiState(viewModel)

            viewModel.setSpreadsheetInput("не ссылка")

            assertTrue(viewModel.uiState.value.spreadsheetError)
            assertNull(viewModel.uiState.value.settings?.spreadsheetId)
        }

    @Test
    fun `setSpreadsheetInput clears a previous error once a valid value is entered`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository())
            collectUiState(viewModel)

            viewModel.setSpreadsheetInput("мусор")
            assertTrue(viewModel.uiState.value.spreadsheetError)

            viewModel.setSpreadsheetInput(validSpreadsheetId)

            assertFalse(viewModel.uiState.value.spreadsheetError)
            assertEquals(validSpreadsheetId, viewModel.uiState.value.settings?.spreadsheetId)
        }

    // endregion

    // region toggles

    @Test
    fun `toggleSound persists the flag`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository())
        collectUiState(viewModel)

        viewModel.toggleSound(false)

        assertFalse(viewModel.uiState.value.settings?.soundEnabled ?: true)
    }

    @Test
    fun `toggleVibration persists the flag`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository())
        collectUiState(viewModel)

        viewModel.toggleVibration(false)

        assertFalse(viewModel.uiState.value.settings?.vibrationEnabled ?: true)
    }

    // endregion

    // region import on link save

    @Test
    fun `saving a valid link triggers import and posts the result message`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val import = FakeImportRepository(ImportResult.Success(3))
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), import)
            collectUiState(viewModel)

            viewModel.setSpreadsheetInput(validSpreadsheetId)

            assertEquals(1, import.calls)
            assertEquals("Импортировано тренировок: 3", viewModel.messages.first())
        }

    @Test
    fun `invalid link does not trigger import`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val import = FakeImportRepository()
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), import)
            collectUiState(viewModel)

            viewModel.setSpreadsheetInput("не ссылка")

            assertEquals(0, import.calls)
        }

    @Test
    fun `nothing to import posts an informational message`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val import = FakeImportRepository(ImportResult.NothingToImport)
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), import)
            collectUiState(viewModel)

            viewModel.setSpreadsheetInput(validSpreadsheetId)

            assertEquals("Нечего импортировать", viewModel.messages.first())
        }

    @Test
    fun `import failure posts the reason`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val import = FakeImportRepository(ImportResult.Failure("Нет доступа к таблице — проверьте вход и права"))
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), import)
            collectUiState(viewModel)

            viewModel.setSpreadsheetInput(validSpreadsheetId)

            assertEquals("Нет доступа к таблице — проверьте вход и права", viewModel.messages.first())
        }

    // endregion

    private fun TestScope.collectUiState(viewModel: SettingsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
    }

    private fun settingsRepository(defaultRestSeconds: Int? = null): SettingsRepository {
        val prefs = if (defaultRestSeconds == null) {
            emptyPreferences()
        } else {
            mutablePreferencesOf(intPreferencesKey("default_rest_seconds") to defaultRestSeconds)
        }
        return SettingsRepository(FakeDataStore(prefs))
    }

    /** [WorkoutImportRepository] с программируемым результатом и счётчиком вызовов. */
    private class FakeImportRepository(
        private val result: ImportResult = ImportResult.Success(3),
    ) : WorkoutImportRepository {
        var calls: Int = 0
            private set
        override suspend fun importAll(): ImportResult {
            calls++
            return result
        }
    }

    /** No-op [UploadScheduler]: these tests never invoke the export path. */
    private class FakeUploadScheduler : UploadScheduler {
        override fun schedule(workoutId: String) = Unit
        override suspend fun retry(workoutId: String) = Unit
        override suspend fun scheduleAllPending(): Int = 0
    }

    /** No-op [GoogleAuth]: rest/spreadsheet/toggle paths never touch Google, so defaults suffice. */
    private class FakeGoogleAuth : GoogleAuth {
        override suspend fun signIn(activity: Activity): Result<String> = Result.success("user@example.com")
        override suspend fun authorize(activity: Activity): AuthorizeOutcome = AuthorizeOutcome.Granted
        override suspend fun getAccessToken(): TokenResult = TokenResult.NeedsConsent
        override suspend fun signOut() = Unit
    }

    /** Minimal in-memory [DataStore] so a real [SettingsRepository] can read and persist [Preferences]. */
    private class FakeDataStore(prefs: Preferences) : DataStore<Preferences> {

        private val state = MutableStateFlow(prefs)

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            state.value = transform(state.value)
            return state.value
        }
    }
}
