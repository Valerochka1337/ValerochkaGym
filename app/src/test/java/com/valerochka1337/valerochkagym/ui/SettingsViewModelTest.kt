package com.valerochka1337.valerochkagym.ui

import android.app.Activity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.valerochka1337.valerochkagym.data.backup.ClearDataUseCase
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.backup.ExportResult
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.ImportResult
import com.valerochka1337.valerochkagym.data.google.TokenResult
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepository
import com.valerochka1337.valerochkagym.data.settings.OpenRouterKeyStore
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.ui.settings.SettingsViewModel
import com.valerochka1337.valerochkagym.ui.theme.AccentColor
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import com.valerochka1337.valerochkagym.worker.UploadScheduler
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
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
    fun `export all schedules both workouts and measurements`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val workouts = FakeUploadScheduler(pendingCount = 2)
            val measurements = FakeMeasurementUploadScheduler(pendingCount = 3)
            val viewModel = SettingsViewModel(
                settingsRepository(),
                FakeGoogleAuth(),
                workouts,
                FakeImportRepository(),
                FakeDatabaseExporter(),
                FakeClearData(),
                measurementUploadScheduler = measurements,
            )

            viewModel.exportAll()

            assertEquals("Поставлено в очередь: 5", viewModel.messages.first())
            assertEquals(1, workouts.allCalls)
            assertEquals(1, measurements.allCalls)
        }

    @Test
    fun `changeDefaultRest adds the step`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = SettingsViewModel(settingsRepository(defaultRestSeconds = 120), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
        collectUiState(viewModel)

        viewModel.changeDefaultRest(15)

        assertEquals(135, viewModel.uiState.value.settings?.defaultRestSeconds)
    }

    @Test
    fun `changeDefaultRest subtracts the step`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = SettingsViewModel(settingsRepository(defaultRestSeconds = 120), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
        collectUiState(viewModel)

        viewModel.changeDefaultRest(-15)

        assertEquals(105, viewModel.uiState.value.settings?.defaultRestSeconds)
    }

    @Test
    fun `changeDefaultRest coerces to the minimum of fifteen`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = SettingsViewModel(settingsRepository(defaultRestSeconds = 20), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
            collectUiState(viewModel)

            viewModel.changeDefaultRest(-15)

            assertEquals(15, viewModel.uiState.value.settings?.defaultRestSeconds)
        }

    @Test
    fun `heart rate rest defaults to disabled with a threshold and hold duration`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
            collectUiState(viewModel)

            assertFalse(viewModel.uiState.value.settings?.heartRateRestEnabled ?: true)
            assertEquals(110, viewModel.uiState.value.settings?.heartRateRestThresholdBpm)
            assertEquals(10, viewModel.uiState.value.settings?.heartRateRestHoldSeconds)
        }

    @Test
    fun `heart rate rest toggle and threshold stay within their bounds`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
            collectUiState(viewModel)

            viewModel.toggleHeartRateRest(true)
            viewModel.changeHeartRateRestThreshold(1_000)
            assertTrue(viewModel.uiState.value.settings?.heartRateRestEnabled ?: false)
            assertEquals(220, viewModel.uiState.value.settings?.heartRateRestThresholdBpm)

            viewModel.changeHeartRateRestThreshold(-1_000)
            assertEquals(40, viewModel.uiState.value.settings?.heartRateRestThresholdBpm)

            viewModel.changeHeartRateRestHoldSeconds(1_000)
            assertEquals(60, viewModel.uiState.value.settings?.heartRateRestHoldSeconds)
            viewModel.changeHeartRateRestHoldSeconds(-1_000)
            assertEquals(5, viewModel.uiState.value.settings?.heartRateRestHoldSeconds)
        }

    // endregion

    // region spreadsheet input

    @Test
    fun `setSpreadsheetInput persists the parsed id and clears the error`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
            collectUiState(viewModel)

            val url = "https://docs.google.com/spreadsheets/d/$validSpreadsheetId/edit#gid=0"
            viewModel.setSpreadsheetInput(url)

            assertEquals(validSpreadsheetId, viewModel.uiState.value.settings?.spreadsheetId)
            assertFalse(viewModel.uiState.value.spreadsheetError)
        }

    @Test
    fun `setSpreadsheetInput sets the error and does not persist on invalid input`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
            collectUiState(viewModel)

            viewModel.setSpreadsheetInput("не ссылка")

            assertTrue(viewModel.uiState.value.spreadsheetError)
            assertNull(viewModel.uiState.value.settings?.spreadsheetId)
        }

    @Test
    fun `setSpreadsheetInput clears a previous error once a valid value is entered`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
            collectUiState(viewModel)

            viewModel.setSpreadsheetInput("мусор")
            assertTrue(viewModel.uiState.value.spreadsheetError)

            viewModel.setSpreadsheetInput(validSpreadsheetId)

            assertFalse(viewModel.uiState.value.spreadsheetError)
            assertEquals(validSpreadsheetId, viewModel.uiState.value.settings?.spreadsheetId)
        }

    // endregion

    // region OpenRouter key

    @Test
    fun `OpenRouter key state is exposed without exposing the saved key`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val keyStore = FakeOpenRouterKeyStore()
            val viewModel = SettingsViewModel(
                settingsRepository(),
                FakeGoogleAuth(),
                FakeUploadScheduler(),
                FakeImportRepository(),
                FakeDatabaseExporter(),
                FakeClearData(),
                openRouterKeyStore = keyStore,
            )
            collectUiState(viewModel)

            viewModel.setOpenRouterKey("  sk-or-v1-secret  ")

            assertTrue(viewModel.uiState.value.openRouterKeyConfigured)
            assertEquals("sk-or-v1-secret", keyStore.savedKey)
            assertEquals("Ключ OpenRouter сохранён", viewModel.messages.first())

            viewModel.clearOpenRouterKey()

            assertFalse(viewModel.uiState.value.openRouterKeyConfigured)
            assertNull(keyStore.savedKey)
            assertEquals("Ключ OpenRouter удалён", viewModel.messages.first())
        }

    // endregion

    // region toggles

    @Test
    fun `toggleSound persists the flag`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
        collectUiState(viewModel)

        viewModel.toggleSound(false)

        assertFalse(viewModel.uiState.value.settings?.soundEnabled ?: true)
    }

    @Test
    fun `toggleVibration persists the flag`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
        collectUiState(viewModel)

        viewModel.toggleVibration(false)

        assertFalse(viewModel.uiState.value.settings?.vibrationEnabled ?: true)
    }

    @Test
    fun `toggleHaptics persists the flag independently of the timer vibration`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
            collectUiState(viewModel)

            viewModel.toggleHaptics(false)

            assertFalse(viewModel.uiState.value.settings?.hapticsEnabled ?: true)
            // Вибрация уведомления таймера — отдельная настройка, не трогается.
            assertTrue(viewModel.uiState.value.settings?.vibrationEnabled ?: false)
        }

    @Test
    fun `toggleRestAutostart persists the flag`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
            collectUiState(viewModel)

            viewModel.toggleRestAutostart(false)

            assertFalse(viewModel.uiState.value.settings?.restAutostart ?: true)
        }

    // endregion

    // region accent

    @Test
    fun `setAccent persists the choice`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
        collectUiState(viewModel)

        viewModel.setAccent(AccentColor.CYAN)

        assertEquals(AccentColor.CYAN, viewModel.uiState.value.settings?.accent)
    }

    @Test
    fun `accent defaults to green`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
        collectUiState(viewModel)

        assertEquals(AccentColor.GREEN, viewModel.uiState.value.settings?.accent)
    }

    // endregion

    // region import on link save

    @Test
    fun `saving a valid link triggers import and posts the result message`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val import = FakeImportRepository(ImportResult.Success(3))
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), import, FakeDatabaseExporter(), FakeClearData())
            collectUiState(viewModel)

            viewModel.setSpreadsheetInput(validSpreadsheetId)

            assertEquals(1, import.calls)
            assertEquals("Импортировано тренировок: 3", viewModel.messages.first())
        }

    @Test
    fun `invalid link does not trigger import`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val import = FakeImportRepository()
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), import, FakeDatabaseExporter(), FakeClearData())
            collectUiState(viewModel)

            viewModel.setSpreadsheetInput("не ссылка")

            assertEquals(0, import.calls)
        }

    @Test
    fun `nothing to import posts an informational message`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val import = FakeImportRepository(ImportResult.NothingToImport)
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), import, FakeDatabaseExporter(), FakeClearData())
            collectUiState(viewModel)

            viewModel.setSpreadsheetInput(validSpreadsheetId)

            assertEquals("Нечего импортировать", viewModel.messages.first())
        }

    @Test
    fun `import failure posts the reason`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val import = FakeImportRepository(ImportResult.Failure("Нет доступа к таблице — проверьте вход и права"))
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), import, FakeDatabaseExporter(), FakeClearData())
            collectUiState(viewModel)

            viewModel.setSpreadsheetInput(validSpreadsheetId)

            assertEquals("Нет доступа к таблице — проверьте вход и права", viewModel.messages.first())
        }

    // endregion

    // region data card

    @Test
    fun `clearAllData wipes the database and posts a confirmation`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val clear = FakeClearData()
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), clear)
            collectUiState(viewModel)

            viewModel.clearAllData()

            assertEquals(1, clear.calls)
            assertEquals("Данные очищены", viewModel.messages.first())
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

    /** No-op [DatabaseExporter]: экспорт покрыт Robolectric-тестом настоящей реализации. */
    private class FakeDatabaseExporter : DatabaseExporter {
        override suspend fun export(target: android.net.Uri): ExportResult = ExportResult.Success
    }

    /** [ClearDataUseCase] со счётчиком вызовов. */
    private class FakeClearData : ClearDataUseCase {
        var calls: Int = 0
            private set
        override suspend fun invoke() {
            calls++
        }
    }

    /** No-op [UploadScheduler]: these tests never invoke the export path. */
    private class FakeUploadScheduler(private val pendingCount: Int = 0) : UploadScheduler {
        var allCalls: Int = 0
            private set
        override fun schedule(workoutId: String) = Unit
        override suspend fun retry(workoutId: String) = Unit
        override suspend fun scheduleAllPending(): Int {
            allCalls++
            return pendingCount
        }
    }

    private class FakeMeasurementUploadScheduler(private val pendingCount: Int = 0) : MeasurementUploadScheduler {
        var allCalls: Int = 0
            private set
        override fun schedule(measurementId: String) = Unit
        override suspend fun retry(measurementId: String) = Unit
        override suspend fun scheduleAllPending(): Int {
            allCalls++
            return pendingCount
        }
    }

    private class FakeOpenRouterKeyStore : OpenRouterKeyStore {
        private val configured = MutableStateFlow(false)

        var savedKey: String? = null
            private set

        override val isConfigured: Flow<Boolean> = configured

        override suspend fun save(value: String) {
            savedKey = value
            configured.value = true
        }

        override suspend fun read(): String? = savedKey

        override suspend fun clear() {
            savedKey = null
            configured.value = false
        }
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
