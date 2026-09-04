package com.valerochka1337.valerochkagym.ui

import android.app.Activity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.backup.ClearDataUseCase
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.backup.ExportResult
import com.valerochka1337.valerochkagym.data.backup.PrivateOriginalsClearFailure
import com.valerochka1337.valerochkagym.data.ai.AiModel
import com.valerochka1337.valerochkagym.data.ai.AiModelCatalog
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.ImportResult
import com.valerochka1337.valerochkagym.data.google.TokenResult
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepository
import com.valerochka1337.valerochkagym.data.google.HealthSheetsRepository
import com.valerochka1337.valerochkagym.data.google.HealthImportResult
import com.valerochka1337.valerochkagym.data.google.RemoteClearResult
import com.valerochka1337.valerochkagym.data.google.SheetsRepository
import com.valerochka1337.valerochkagym.data.google.UploadResult
import com.valerochka1337.valerochkagym.data.settings.AiApiKeyStore
import com.valerochka1337.valerochkagym.data.settings.maskedAiApiKeyPreview
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.data.settings.HealthSyncCategory
import com.valerochka1337.valerochkagym.ui.settings.AI_MODEL_CATALOG_TIMEOUT_MILLIS
import com.valerochka1337.valerochkagym.ui.settings.SettingsViewModel
import com.valerochka1337.valerochkagym.ui.settings.RemoteClearUiState
import com.valerochka1337.valerochkagym.ui.settings.healthSyncDisclosure
import com.valerochka1337.valerochkagym.ui.theme.AccentColor
import com.valerochka1337.valerochkagym.ui.theme.PaletteMode
import com.valerochka1337.valerochkagym.ui.theme.ThemeMode
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import com.valerochka1337.valerochkagym.worker.UploadScheduler
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import com.valerochka1337.valerochkagym.worker.RoutineUploadScheduler
import com.valerochka1337.valerochkagym.worker.ConfigurationUploadScheduler
import com.valerochka1337.valerochkagym.worker.HealthSyncScheduler
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

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
    fun `export all schedules workouts measurements and routines`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val settings = settingsRepository().also {
                it.setHealthSyncEnabled(true)
                it.setHealthSyncCategory(HealthSyncCategory.WORKOUTS_AND_CONFIGURATION, true)
                it.setHealthSyncCategory(HealthSyncCategory.MEASUREMENTS, true)
            }
            val workouts = FakeUploadScheduler(pendingCount = 2)
            val measurements = FakeMeasurementUploadScheduler(pendingCount = 3)
            val routines = FakeRoutineUploadScheduler(pendingCount = 4)
            val viewModel = SettingsViewModel(
                settings,
                FakeGoogleAuth(),
                workouts,
                FakeImportRepository(),
                FakeDatabaseExporter(),
                FakeClearData(),
                measurementUploadScheduler = measurements,
                routineUploadScheduler = routines,
            )

            viewModel.exportAll()

            assertEquals("Поставлено в очередь: 9", viewModel.messages.first())
            assertEquals(1, workouts.allCalls)
            assertEquals(1, measurements.allCalls)
            assertEquals(1, routines.allCalls)
        }

    @Test
    fun `legacy configured sheets preserves workouts and measurements but not medical consent`() = runTest {
        val store = FakeDataStore(mutablePreferencesOf(stringPreferencesKey("spreadsheet_id") to validSpreadsheetId))
        val first = SettingsRepository(store)
        val settings = first.settings.first()
        assertTrue(settings.healthSync.isEnabled(HealthSyncCategory.WORKOUTS_AND_CONFIGURATION))
        assertTrue(settings.healthSync.isEnabled(HealthSyncCategory.MEASUREMENTS))
        assertFalse(settings.healthSync.isEnabled(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS))
        assertFalse(settings.healthSync.isEnabled(HealthSyncCategory.HEALTH_RESTRICTIONS))

        val recreated = SettingsRepository(store).settings.first()
        assertEquals(settings.healthSync, recreated.healthSync)
    }

    @Test
    fun `each sync category exposes disclosure and cancel changes nothing`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val settings = settingsRepository().also { it.setHealthSyncEnabled(true) }
        val vm = SettingsViewModel(settings, FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
        HealthSyncCategory.entries.forEach { category ->
            vm.requestHealthSyncCategory(category, true)
            runCurrent()
            val disclosure = vm.healthSyncDisclosure.value!!
            assertEquals(category.healthSyncDisclosure(), disclosure)
            assertTrue(disclosure.warning.contains("доступ"))
            assertTrue(disclosure.localOnly.contains("e1RM"))
            if (category == HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS) assertTrue(disclosure.exclusions.contains("PDF и фото"))
            if (category == HealthSyncCategory.HEALTH_RESTRICTIONS) assertTrue(disclosure.exclusions.contains("исходный свободный текст"))
            vm.cancelHealthSyncDisclosure()
            assertFalse(settings.settings.first().healthSync.categories.contains(category))
        }
    }

    @Test
    fun `remote clear cancellation at both stages does not call repositories or change settings`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val settings = settingsRepository().also { it.setHealthSyncEnabled(true) }
            val before = settings.settings.first()
            val sheets = RecordingRemoteClearSheets()
            val health = RecordingRemoteClearHealth()
            val vm = SettingsViewModel(
                settings, FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(),
                FakeDatabaseExporter(), FakeClearData(), healthSheetsRepository = health,
                sheetsRepository = sheets,
            )

            vm.requestRemoteClear()
            vm.toggleRemoteClearCategory(HealthSyncCategory.MEASUREMENTS)
            vm.cancelRemoteClear()
            assertTrue(sheets.calls.isEmpty())
            assertTrue(health.clearCalls.isEmpty())

            vm.requestRemoteClear()
            vm.toggleRemoteClearCategory(HealthSyncCategory.HEALTH_RESTRICTIONS)
            vm.continueRemoteClear()
            vm.cancelRemoteClear()
            assertTrue(sheets.calls.isEmpty())
            assertTrue(health.clearCalls.isEmpty())
            assertEquals(before, settings.settings.first())
            assertNull(vm.remoteClearState.value)
        }

    @Test
    fun `remote clear freezes selected categories and calls repositories in stable order without changing sync`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val settings = settingsRepository().also {
                it.setHealthSyncEnabled(true)
                it.setHealthSyncCategory(HealthSyncCategory.HEALTH_RESTRICTIONS, true)
            }
            val before = settings.settings.first()
            val calls = mutableListOf<String>()
            val sheets = RecordingRemoteClearSheets(calls = calls)
            val health = RecordingRemoteClearHealth(calls = calls)
            val measurementScheduler = FakeMeasurementUploadScheduler()
            val vm = SettingsViewModel(
                settings, FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(),
                FakeDatabaseExporter(), FakeClearData(), measurementUploadScheduler = measurementScheduler,
                healthSheetsRepository = health, sheetsRepository = sheets,
            )

            vm.requestRemoteClear()
            vm.toggleRemoteClearCategory(HealthSyncCategory.HEALTH_RESTRICTIONS)
            vm.toggleRemoteClearCategory(HealthSyncCategory.MEASUREMENTS)
            vm.toggleRemoteClearCategory(HealthSyncCategory.WORKOUTS_AND_CONFIGURATION)
            vm.continueRemoteClear()
            assertEquals(
                listOf(
                    HealthSyncCategory.WORKOUTS_AND_CONFIGURATION,
                    HealthSyncCategory.MEASUREMENTS,
                    HealthSyncCategory.HEALTH_RESTRICTIONS,
                ),
                (vm.remoteClearState.value as RemoteClearUiState.Confirming).selected,
            )
            vm.confirmRemoteClear()
            advanceUntilIdle()

            assertEquals(listOf("workouts", "measurements", "HEALTH_RESTRICTIONS"), calls)
            assertEquals(before, settings.settings.first())
            assertTrue(measurementScheduler.categoryChanges.isEmpty())
            assertNull(vm.remoteClearState.value)
        }

    @Test
    fun `remote clear reports partial category and managed range without invoking remaining categories`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val settings = settingsRepository()
            val calls = mutableListOf<String>()
            val sheets = RecordingRemoteClearSheets(
                calls = calls,
                measurementResult = RemoteClearResult.Failure(
                    "Нет сети при очистке Google Sheets",
                    listOf("Measurements!A2:AY"),
                    failedRange = "Measurements!A2:AY",
                ),
            )
            val health = RecordingRemoteClearHealth(calls = calls)
            val vm = SettingsViewModel(
                settings, FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(),
                FakeDatabaseExporter(), FakeClearData(), healthSheetsRepository = health,
                sheetsRepository = sheets,
            )

            vm.requestRemoteClear()
            HealthSyncCategory.entries.forEach(vm::toggleRemoteClearCategory)
            vm.continueRemoteClear()
            vm.confirmRemoteClear()
            val message = vm.messages.first()

            assertEquals(listOf("workouts", "measurements"), calls)
            assertTrue(message.contains("Тренировки и программы: Workouts!A2:S"))
            assertTrue(message.contains("Состав тела и InBody: Measurements!A2:AY"))
            assertTrue(message.contains("Не удалось очистить: Состав тела и InBody"))
            assertTrue(message.contains("Не очищено: Состав тела и InBody: Measurements!A2:AY"))
            assertTrue(message.contains("Осталось: Медицинские анализы, Ограничения и важная информация"))
            assertTrue(health.clearCalls.isEmpty())
        }

    @Test
    fun `remote clear pauses and restores the selected effective category workers`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val settings = settingsRepository().also {
                it.setHealthSyncEnabled(true)
                it.setHealthSyncCategory(HealthSyncCategory.MEASUREMENTS, true)
            }
            val measurements = FakeMeasurementUploadScheduler()
            val vm = SettingsViewModel(
                settings, FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(),
                FakeDatabaseExporter(), FakeClearData(), measurementUploadScheduler = measurements,
                sheetsRepository = RecordingRemoteClearSheets(),
            )

            vm.requestRemoteClear()
            vm.toggleRemoteClearCategory(HealthSyncCategory.MEASUREMENTS)
            vm.continueRemoteClear()
            vm.confirmRemoteClear()
            advanceUntilIdle()

            assertEquals(listOf(false, true), measurements.categoryChanges)
        }

    @Test
    fun `remote clear message names failed and unattempted managed ranges inside a category`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val sheets = RecordingRemoteClearSheets(
                workoutsResult = RemoteClearResult.Failure(
                    "Нет сети при очистке Google Sheets",
                    clearedRanges = listOf("Workouts!A2:S", "Routines!A2:M"),
                    failedRange = "Exercises!A2:I",
                    remainingRanges = listOf("ExerciseVariants!A2:F"),
                ),
            )
            val vm = SettingsViewModel(
                settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(),
                FakeDatabaseExporter(), FakeClearData(), sheetsRepository = sheets,
            )
            vm.requestRemoteClear()
            vm.toggleRemoteClearCategory(HealthSyncCategory.WORKOUTS_AND_CONFIGURATION)
            vm.continueRemoteClear()
            vm.confirmRemoteClear()
            val message = vm.messages.first()

            assertTrue(message.contains("Уже очищено: Тренировки и программы: Workouts!A2:S, Тренировки и программы: Routines!A2:M"))
            assertTrue(message.contains("Не очищено: Тренировки и программы: Exercises!A2:I, Тренировки и программы: ExerciseVariants!A2:F"))
        }

    @Test
    fun `remote clear keeps intra medical partial ranges and duplicate confirm submits once`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val health = RecordingRemoteClearHealth(
                reportResult = RemoteClearResult.Failure(
                    "Нет сети при очистке Google Sheets",
                    listOf("HealthReports!A2:L"),
                    failedRange = "HealthObservations!A2:Q",
                ),
                onReports = {
                    started.complete(Unit)
                    release.await()
                },
            )
            val vm = SettingsViewModel(
                settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(),
                FakeDatabaseExporter(), FakeClearData(), healthSheetsRepository = health,
                sheetsRepository = RecordingRemoteClearSheets(),
            )
            vm.requestRemoteClear()
            vm.toggleRemoteClearCategory(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS)
            vm.continueRemoteClear()
            vm.confirmRemoteClear()
            started.await()
            vm.confirmRemoteClear()
            assertEquals(listOf(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS), health.clearCalls)
            release.complete(Unit)
            val message = vm.messages.first()

            assertTrue(message.contains("Медицинские анализы: HealthReports!A2:L"))
            assertTrue(message.contains("Не удалось очистить: Медицинские анализы"))
            assertTrue(message.contains("Не очищено: Медицинские анализы: HealthObservations!A2:Q"))
            assertEquals(1, health.clearCalls.size)
        }

    @Test
    fun `master enable imports a consented category added during another import before its schedule`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val settings = settingsRepository().also {
                it.setHealthSyncCategory(HealthSyncCategory.WORKOUTS_AND_CONFIGURATION, true)
            }
            val importStarted = CompletableDeferred<Unit>()
            val releaseImport = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val imports = object : WorkoutImportRepository {
                override suspend fun importAll() = ImportResult.NothingToImport
                override suspend fun import(categories: Set<HealthSyncCategory>): ImportResult {
                    val category = categories.single()
                    order += "import:$category"
                    if (category == HealthSyncCategory.WORKOUTS_AND_CONFIGURATION) {
                        importStarted.complete(Unit)
                        releaseImport.await()
                    }
                    return ImportResult.NothingToImport
                }
            }
            val measurements = object : MeasurementUploadScheduler {
                override suspend fun schedule(measurementId: String) = Unit
                override suspend fun retry(measurementId: String) = Unit
                override suspend fun scheduleAllPending() = 0
                override suspend fun onCategoryChanged(enabled: Boolean) {
                    order += "schedule:measurements:$enabled"
                }
            }
            val vm = SettingsViewModel(
                settings,
                FakeGoogleAuth(),
                FakeUploadScheduler(),
                imports,
                FakeDatabaseExporter(),
                FakeClearData(),
                measurementUploadScheduler = measurements,
            )

            vm.setHealthSyncEnabled(true)
            runCurrent()
            importStarted.await()
            vm.requestHealthSyncCategory(HealthSyncCategory.MEASUREMENTS, true)
            runCurrent()
            vm.confirmHealthSyncDisclosure()
            releaseImport.complete(Unit)
            advanceUntilIdle()

            assertTrue(settings.settings.first().healthSync.enabled)
            assertTrue(settings.settings.first().healthSync.categories.contains(HealthSyncCategory.MEASUREMENTS))
            assertTrue(order.indexOf("import:MEASUREMENTS") < order.indexOf("schedule:measurements:true"))
        }

    @Test
    fun `stale disclosure confirm and cancel perform zero import persist or schedule`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val settings = settingsRepository().also { it.setHealthSyncEnabled(true) }
        val imports = RecordingScopedImport()
        val measurements = FakeMeasurementUploadScheduler()
        val vm = SettingsViewModel(settings, FakeGoogleAuth(), FakeUploadScheduler(), imports, FakeDatabaseExporter(), FakeClearData(), measurementUploadScheduler = measurements)

        vm.requestHealthSyncCategory(HealthSyncCategory.MEASUREMENTS, true)
        runCurrent()
        vm.cancelHealthSyncDisclosure()
        assertTrue(imports.requests.isEmpty())
        assertTrue(measurements.categoryChanges.isEmpty())
        assertFalse(settings.settings.first().healthSync.categories.contains(HealthSyncCategory.MEASUREMENTS))

        vm.requestHealthSyncCategory(HealthSyncCategory.MEASUREMENTS, true)
        runCurrent()
        settings.setHealthSyncCategory(HealthSyncCategory.MEASUREMENTS, true) // another actor wins while dialog is open
        vm.confirmHealthSyncDisclosure()
        runCurrent()
        assertTrue(imports.requests.isEmpty())
        assertTrue(measurements.categoryChanges.isEmpty())
        assertTrue(settings.settings.first().healthSync.categories.contains(HealthSyncCategory.MEASUREMENTS))
    }

    @Test
    fun `master change during disclosure confirmation preserves fresh off state without importing or rescheduling`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val settings = settingsRepository().also { it.setHealthSyncEnabled(true) }
            val imports = RecordingScopedImport()
            val measurements = FakeMeasurementUploadScheduler()
            val vm = SettingsViewModel(
                settings,
                FakeGoogleAuth(),
                FakeUploadScheduler(),
                imports,
                FakeDatabaseExporter(),
                FakeClearData(),
                measurementUploadScheduler = measurements,
            )

            vm.requestHealthSyncCategory(HealthSyncCategory.MEASUREMENTS, true)
            runCurrent()
            vm.setHealthSyncEnabled(false)
            runCurrent()
            val cancellationCalls = measurements.categoryChanges.toList()

            vm.confirmHealthSyncDisclosure()
            runCurrent()

            val fresh = settings.settings.first().healthSync
            assertFalse(fresh.enabled)
            assertTrue(fresh.categories.contains(HealthSyncCategory.MEASUREMENTS))
            assertTrue(imports.requests.isEmpty())
            assertEquals(cancellationCalls, measurements.categoryChanges)
        }

    @Test
    fun `master enable imports every selected category before persisting or scheduling`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val settings = settingsRepository().also {
                HealthSyncCategory.entries.forEach { category -> it.setHealthSyncCategory(category, true) }
            }
            val order = mutableListOf<String>()
            val imports = OrderedImport(settings, order)
            val health = OrderedHealthImport(settings, order)
            val workouts = OrderedUploadScheduler(order)
            val measurements = OrderedMeasurementScheduler(order)
            val viewModel = SettingsViewModel(
                settings, FakeGoogleAuth(), workouts, imports, FakeDatabaseExporter(), FakeClearData(),
                measurementUploadScheduler = measurements, healthSyncScheduler = OrderedHealthScheduler(order),
                healthSheetsRepository = health,
            )

            viewModel.setHealthSyncEnabled(true)
            runCurrent()

            assertEquals(
                listOf("import:WORKOUTS_AND_CONFIGURATION", "import:MEASUREMENTS", "import:HEALTH_REPORTS_AND_OBSERVATIONS", "import:HEALTH_RESTRICTIONS"),
                order.take(4),
            )
            assertTrue(order.drop(4).any { it.startsWith("schedule:") })
            assertTrue(settings.settings.first().healthSync.enabled)
        }

    @Test
    fun `failed enable import leaves master off and schedules nothing`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val settings = settingsRepository().also { it.setHealthSyncCategory(HealthSyncCategory.MEASUREMENTS, true) }
            val uploads = OrderedUploadScheduler(mutableListOf())
            val measurements = OrderedMeasurementScheduler(mutableListOf())
            val failing = object : WorkoutImportRepository {
                override suspend fun importAll() = ImportResult.Failure("нет сети")
                override suspend fun import(categories: Set<HealthSyncCategory>) = ImportResult.Failure("нет сети")
            }
            val vm = SettingsViewModel(settings, FakeGoogleAuth(), uploads, failing, FakeDatabaseExporter(), FakeClearData(), measurementUploadScheduler = measurements)

            vm.setHealthSyncEnabled(true)
            runCurrent()

            assertFalse(settings.settings.first().healthSync.enabled)
            assertTrue(uploads.categoryChanges.isEmpty())
            assertTrue(measurements.categoryChanges.isEmpty())
        }

    @Test
    fun `incomplete health aggregate leaves category disabled before scheduling`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val settings = settingsRepository().also { it.setHealthSyncEnabled(true) }
            val scheduler = FakeHealthSyncScheduler()
            val health = object : HealthSheetsRepository {
                override suspend fun upload(entry: HealthSyncOutboxEntity) = UploadResult.Success
                override suspend fun import(category: HealthSyncCategory) = 0
                override suspend fun importForEnable(category: HealthSyncCategory) =
                    HealthImportResult.Failure("Неполный агрегат исследования")
                override suspend fun clearAfterConfirmation(category: HealthSyncCategory) =
                    RemoteClearResult.Success(emptyList())
            }
            val viewModel = SettingsViewModel(
                settings, FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(),
                FakeDatabaseExporter(), FakeClearData(), healthSyncScheduler = scheduler,
                healthSheetsRepository = health,
            )

            viewModel.setHealthSyncCategory(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, true)
            runCurrent()

            assertFalse(settings.settings.first().healthSync.isEnabled(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS))
            assertTrue(scheduler.categoryChanges.isEmpty())
            assertEquals("Неполный агрегат исследования", viewModel.messages.first())
        }

    @Test
    fun `individual category imports before persistence then schedules and failure keeps it off`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val settings = settingsRepository().also { it.setHealthSyncEnabled(true) }
            val order = mutableListOf<String>()
            val importer = object : WorkoutImportRepository {
                override suspend fun importAll() = ImportResult.NothingToImport
                override suspend fun import(categories: Set<HealthSyncCategory>): ImportResult {
                    assertFalse(settings.settings.first().healthSync.isEnabled(HealthSyncCategory.MEASUREMENTS))
                    order += "import"
                    return ImportResult.NothingToImport
                }
            }
            val scheduler = OrderedMeasurementScheduler(order)
            val vm = SettingsViewModel(settings, FakeGoogleAuth(), FakeUploadScheduler(), importer, FakeDatabaseExporter(), FakeClearData(), measurementUploadScheduler = scheduler)
            vm.setHealthSyncCategory(HealthSyncCategory.MEASUREMENTS, true)
            runCurrent()
            assertEquals(listOf("import", "schedule:measurements"), order)
            assertTrue(settings.settings.first().healthSync.isEnabled(HealthSyncCategory.MEASUREMENTS))

            val failSettings = settingsRepository().also { it.setHealthSyncEnabled(true) }
            val failing = object : WorkoutImportRepository { override suspend fun importAll()=ImportResult.Failure("fail"); override suspend fun import(categories:Set<HealthSyncCategory>)=ImportResult.Failure("fail") }
            val noSchedule = OrderedMeasurementScheduler(mutableListOf())
            SettingsViewModel(failSettings, FakeGoogleAuth(), FakeUploadScheduler(), failing, FakeDatabaseExporter(), FakeClearData(), measurementUploadScheduler = noSchedule)
                .setHealthSyncCategory(HealthSyncCategory.MEASUREMENTS, true)
            runCurrent()
            assertFalse(failSettings.settings.first().healthSync.isEnabled(HealthSyncCategory.MEASUREMENTS))
            assertTrue(noSchedule.categoryChanges.isEmpty())
        }

    @Test
    fun `selection while master off defers import and schedule until master enable`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val settings = settingsRepository()
            val order = mutableListOf<String>()
            val importer = OrderedImport(settings, order)
            val scheduler = OrderedMeasurementScheduler(order)
            val vm = SettingsViewModel(settings, FakeGoogleAuth(), FakeUploadScheduler(), importer, FakeDatabaseExporter(), FakeClearData(), measurementUploadScheduler = scheduler)
            vm.setHealthSyncCategory(HealthSyncCategory.MEASUREMENTS, true)
            runCurrent()
            assertTrue(settings.settings.first().healthSync.categories.contains(HealthSyncCategory.MEASUREMENTS))
            assertTrue(order.none { it.startsWith("import:") })
            order.clear() // selecting while off may cancel stale tagged work, but never enqueues it.
            vm.setHealthSyncEnabled(true)
            runCurrent()
            assertEquals("import:MEASUREMENTS", order.first())
            assertTrue(order.any { it == "schedule:measurements" })
        }

    @Test
    fun `manual import reads only effective categories and repeats the same scoped request`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val settings = settingsRepository().also {
                it.setHealthSyncEnabled(true)
                it.setHealthSyncCategory(HealthSyncCategory.WORKOUTS_AND_CONFIGURATION, true)
                it.setHealthSyncCategory(HealthSyncCategory.MEASUREMENTS, false)
                it.setHealthSyncCategory(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, true)
            }
            val imports = RecordingScopedImport()
            val health = RecordingHealthImport()
            val vm = SettingsViewModel(settings, FakeGoogleAuth(), FakeUploadScheduler(), imports, FakeDatabaseExporter(), FakeClearData(), healthSheetsRepository = health)

            vm.setSpreadsheetInput(validSpreadsheetId)
            assertEquals("Нечего импортировать", vm.messages.first())
            assertEquals(listOf(setOf(HealthSyncCategory.WORKOUTS_AND_CONFIGURATION)), imports.requests)
            assertEquals(listOf(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS), health.requests)
            vm.setSpreadsheetInput(validSpreadsheetId)
            vm.messages.first()
            assertEquals(2, imports.requests.size)
            assertEquals(2, health.requests.size)

            val offSettings = settingsRepository().also { it.setHealthSyncEnabled(false) }
            val offImports = RecordingScopedImport()
            val offHealth = RecordingHealthImport()
            SettingsViewModel(offSettings, FakeGoogleAuth(), FakeUploadScheduler(), offImports, FakeDatabaseExporter(), FakeClearData(), healthSheetsRepository = offHealth)
                .setSpreadsheetInput(validSpreadsheetId)
            // Wait for the user-visible result before asserting the no-network boundary.
            // A fresh channel is not exposed here; runCurrent drains the ViewModel launch.
            runCurrent()
            assertTrue(offImports.requests.isEmpty())
            assertTrue(offHealth.requests.isEmpty())
        }

    @Test
    fun `export all schedules only freshly effective categories and master off schedules none`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val settings = settingsRepository().also {
                it.setHealthSyncEnabled(true)
                it.setHealthSyncCategory(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, true)
            }
            val workouts = FakeUploadScheduler(2)
            val measurements = FakeMeasurementUploadScheduler(3)
            val routines = FakeRoutineUploadScheduler(4)
            val health = FakeHealthSyncScheduler(pendingCount = 5)
            val viewModel = SettingsViewModel(
                settings, FakeGoogleAuth(), workouts, FakeImportRepository(), FakeDatabaseExporter(), FakeClearData(),
                measurementUploadScheduler = measurements, routineUploadScheduler = routines, healthSyncScheduler = health,
            )

            viewModel.exportAll()
            assertEquals("Поставлено в очередь: 5", viewModel.messages.first())
            assertEquals(listOf(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS), health.pendingCategories)
            assertEquals(0, workouts.allCalls)
            assertEquals(0, measurements.allCalls)
            assertEquals(0, routines.allCalls)

            settings.setHealthSyncEnabled(false)
            viewModel.exportAll()
            assertEquals("Поставлено в очередь: 0", viewModel.messages.first())
            assertEquals(1, health.pendingCategories.size)
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

    // region AI

    @Test
    fun `API key state is exposed without exposing the saved key`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val keyStore = FakeAiApiKeyStore()
            val viewModel = SettingsViewModel(
                settingsRepository(),
                FakeGoogleAuth(),
                FakeUploadScheduler(),
                FakeImportRepository(),
                FakeDatabaseExporter(),
                FakeClearData(),
                aiApiKeyStore = keyStore,
            )
            collectUiState(viewModel)

            viewModel.setAiApiKey("  sk-ai-secret  ")

            assertTrue(viewModel.uiState.value.aiApiKeyConfigured)
            assertEquals("sk-************cret", viewModel.uiState.value.aiApiKeyPreview)
            assertEquals("sk-ai-secret", keyStore.savedKey)
            assertEquals("API key сохранён", viewModel.messages.first())

            viewModel.clearAiApiKey()

            assertFalse(viewModel.uiState.value.aiApiKeyConfigured)
            assertNull(viewModel.uiState.value.aiApiKeyPreview)
            assertNull(keyStore.savedKey)
            assertEquals("API key удалён", viewModel.messages.first())
        }

    @Test
    fun `AI address accepts http and rejects malformed input`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val repository = settingsRepository()
            val viewModel = SettingsViewModel(
                repository,
                FakeGoogleAuth(),
                FakeUploadScheduler(),
                FakeImportRepository(),
                FakeDatabaseExporter(),
                FakeClearData(),
            )
            collectUiState(viewModel)

            viewModel.setAiBaseUrl(" ai.example.com ")

            assertEquals("https://ai.example.com/v1/", viewModel.uiState.value.settings?.aiBaseUrl)
            assertFalse(viewModel.uiState.value.aiBaseUrlError)
            assertEquals("Адрес сохранён", viewModel.messages.first())

            viewModel.setAiBaseUrl("http://ai.example.com")

            assertFalse(viewModel.uiState.value.aiBaseUrlError)
            assertEquals("http://ai.example.com/v1/", viewModel.uiState.value.settings?.aiBaseUrl)

            viewModel.setAiBaseUrl("http://user:pass@ai.example.com")

            assertTrue(viewModel.uiState.value.aiBaseUrlError)
            assertEquals("http://ai.example.com/v1/", viewModel.uiState.value.settings?.aiBaseUrl)
        }

    @Test
    fun `selected AI model persists for both AI scenarios`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = SettingsViewModel(
                settingsRepository(),
                FakeGoogleAuth(),
                FakeUploadScheduler(),
                FakeImportRepository(),
                FakeDatabaseExporter(),
                FakeClearData(),
            )
            collectUiState(viewModel)

            assertNull(viewModel.uiState.value.settings?.aiModelId)

            viewModel.setAiModel(
                AiModel(id = "gpt-5.4", ownedBy = "openai"),
            )

            assertEquals("gpt-5.4", viewModel.uiState.value.settings?.aiModelId)
        }

    @Test
    fun `model catalog timeout exposes retryable error`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val repository = settingsRepository()
            repository.setAiBaseUrl("https://ai.example.com/v1/")
            val keyStore = FakeAiApiKeyStore().apply { save("key") }
            val viewModel = SettingsViewModel(
                repository,
                FakeGoogleAuth(),
                FakeUploadScheduler(),
                FakeImportRepository(),
                FakeDatabaseExporter(),
                FakeClearData(),
                aiApiKeyStore = keyStore,
                aiModelCatalog = HangingAiModelCatalog(),
            )
            collectUiState(viewModel)
            runCurrent()

            advanceTimeBy(AI_MODEL_CATALOG_TIMEOUT_MILLIS.milliseconds)
            runCurrent()

            assertFalse(viewModel.uiState.value.aiModelsLoading)
            assertTrue(viewModel.uiState.value.aiModelsLoadError)
            assertTrue(viewModel.uiState.value.aiModels.isEmpty())
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

    @Test
    fun `appearance defaults to system theme and system palette`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
            collectUiState(viewModel)

            assertEquals(ThemeMode.SYSTEM, viewModel.uiState.value.settings?.themeMode)
            assertEquals(PaletteMode.SYSTEM, viewModel.uiState.value.settings?.paletteMode)
        }

    @Test
    fun `setThemeMode persists the choice`() = runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
        collectUiState(viewModel)

        viewModel.setThemeMode(ThemeMode.LIGHT)

        assertEquals(ThemeMode.LIGHT, viewModel.uiState.value.settings?.themeMode)
    }

    @Test
    fun `setPaletteMode persists the palette and matching launcher accent`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(), FakeDatabaseExporter(), FakeClearData())
            collectUiState(viewModel)

            viewModel.setPaletteMode(PaletteMode.CORAL)

            assertEquals(PaletteMode.CORAL, viewModel.uiState.value.settings?.paletteMode)
            assertEquals(AccentColor.CORAL, viewModel.uiState.value.settings?.accent)
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
    fun `import result names restored measurements and routines`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val import = FakeImportRepository(
                ImportResult.Success(imported = 1, importedMeasurements = 2, importedRoutines = 3),
            )
            val viewModel = SettingsViewModel(
                settingsRepository(),
                FakeGoogleAuth(),
                FakeUploadScheduler(),
                import,
                FakeDatabaseExporter(),
                FakeClearData(),
            )
            collectUiState(viewModel)

            viewModel.setSpreadsheetInput(validSpreadsheetId)

            assertEquals("Импортировано тренировок: 1, замеров: 2, программ: 3", viewModel.messages.first())
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

    @Test
    fun `failed replacement spreadsheet import restores the previous target`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val previous = "1PreviousSpreadsheetId0000000000000000000"
            val settings = settingsRepository().also { it.setSpreadsheetId(previous) }
            val failingImport = FakeImportRepository(ImportResult.Failure("Нет доступа к таблице"))
            settings.setHealthSyncEnabled(true)
            settings.setHealthSyncCategory(HealthSyncCategory.WORKOUTS_AND_CONFIGURATION, true)
            val viewModel = SettingsViewModel(
                settings, FakeGoogleAuth(), FakeUploadScheduler(), failingImport,
                FakeDatabaseExporter(), FakeClearData(),
            )

            viewModel.setSpreadsheetInput(validSpreadsheetId)

            assertEquals(previous, settings.settings.first().spreadsheetId)
            assertEquals("Нет доступа к таблице", viewModel.messages.first())
        }

    // endregion

    // region data card

    @Test
    fun `master and medical categories schedule or cancel only their durable categories`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val settings = settingsRepository()
            val medical = FakeHealthSyncScheduler()
            val measurements = FakeMeasurementUploadScheduler()
            val workouts = FakeUploadScheduler()
            val routines = FakeRoutineUploadScheduler()
            val configuration = FakeConfigurationUploadScheduler()
            val viewModel = SettingsViewModel(
                settings, FakeGoogleAuth(), workouts, FakeImportRepository(),
                FakeDatabaseExporter(), FakeClearData(), measurementUploadScheduler = measurements,
                routineUploadScheduler = routines,
                configurationUploadScheduler = configuration,
                healthSyncScheduler = medical,
            )

            viewModel.setHealthSyncCategory(HealthSyncCategory.WORKOUTS_AND_CONFIGURATION, true)
            runCurrent()
            viewModel.setHealthSyncCategory(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, true)
            runCurrent()
            viewModel.setHealthSyncEnabled(true)
            runCurrent()
            viewModel.setHealthSyncCategory(HealthSyncCategory.HEALTH_RESTRICTIONS, true)
            runCurrent()
            viewModel.setHealthSyncEnabled(false)
            runCurrent()

            assertEquals(
                listOf(
                    HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS to false,
                    HealthSyncCategory.HEALTH_RESTRICTIONS to false,
                    HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS to false,
                    HealthSyncCategory.HEALTH_RESTRICTIONS to false,
                    HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS to true,
                    HealthSyncCategory.HEALTH_RESTRICTIONS to false,
                    HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS to true,
                    HealthSyncCategory.HEALTH_RESTRICTIONS to true,
                    HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS to false,
                    HealthSyncCategory.HEALTH_RESTRICTIONS to false,
                ),
                medical.categoryChanges,
            )
            assertEquals(listOf(false, false, false, false, false), measurements.categoryChanges)
            assertEquals(listOf(false, false, true, true, false), workouts.categoryChanges)
            assertEquals(workouts.categoryChanges, routines.categoryChanges)
            assertEquals(workouts.categoryChanges, configuration.categoryChanges)
        }

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

    @Test
    fun `clearAllData reports partial cleanup rather than success`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = SettingsViewModel(
                settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository(),
                FakeDatabaseExporter(), PartialClearData(),
            )

            viewModel.clearAllData()

            assertEquals(
                "Не удалось удалить локальные оригиналы: health_documents",
                viewModel.messages.first(),
            )
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

    private class OrderedImport(
        private val settings: SettingsRepository,
        private val order: MutableList<String>,
    ) : WorkoutImportRepository {
        override suspend fun importAll() = ImportResult.NothingToImport
        override suspend fun import(categories: Set<HealthSyncCategory>): ImportResult {
            check(!settings.settings.first().healthSync.enabled)
            order += "import:${categories.single()}"
            return ImportResult.NothingToImport
        }
    }

    private class RecordingScopedImport : WorkoutImportRepository {
        val requests = mutableListOf<Set<HealthSyncCategory>>()
        override suspend fun importAll() = ImportResult.NothingToImport
        override suspend fun import(categories: Set<HealthSyncCategory>): ImportResult {
            requests += categories
            return ImportResult.NothingToImport
        }
    }

    private class RecordingHealthImport : HealthSheetsRepository {
        val requests = mutableListOf<HealthSyncCategory>()
        override suspend fun upload(entry: HealthSyncOutboxEntity) = UploadResult.Success
        override suspend fun import(category: HealthSyncCategory): Int { requests += category; return 0 }
        override suspend fun clearAfterConfirmation(category: HealthSyncCategory) = RemoteClearResult.Success(emptyList())
    }

    private class OrderedHealthImport(
        private val settings: SettingsRepository,
        private val order: MutableList<String>,
    ) : HealthSheetsRepository {
        override suspend fun upload(entry: HealthSyncOutboxEntity) = UploadResult.Success
        override suspend fun import(category: HealthSyncCategory) = 0
        override suspend fun importForEnable(category: HealthSyncCategory): HealthImportResult {
            check(!settings.settings.first().healthSync.enabled)
            order += "import:$category"
            return HealthImportResult.NothingToImport
        }
        override suspend fun clearAfterConfirmation(category: HealthSyncCategory) = RemoteClearResult.Success(emptyList())
    }

    private class RecordingRemoteClearSheets(
        val calls: MutableList<String> = mutableListOf(),
        private val workoutsResult: RemoteClearResult = RemoteClearResult.Success(listOf("Workouts!A2:S")),
        private val measurementResult: RemoteClearResult = RemoteClearResult.Success(listOf("Measurements!A2:AY")),
    ) : SheetsRepository {
        override suspend fun uploadWorkout(workoutId: String) = UploadResult.Success
        override suspend fun uploadMeasurement(measurementId: String) = UploadResult.Success
        override suspend fun uploadRoutine(routineSyncId: String) = UploadResult.Success
        override suspend fun uploadRoutineDeletion(routineSyncId: String, updatedAt: Long) = UploadResult.Success
        override suspend fun clearWorkoutsAndConfigurationAfterConfirmation(): RemoteClearResult {
            calls += "workouts"
            return workoutsResult
        }
        override suspend fun clearMeasurementsAfterConfirmation(): RemoteClearResult {
            calls += "measurements"
            return measurementResult
        }
    }

    private class RecordingRemoteClearHealth(
        private val calls: MutableList<String> = mutableListOf(),
        private val reportResult: RemoteClearResult = RemoteClearResult.Success(listOf("HealthReports!A2:L", "HealthObservations!A2:Q")),
        private val restrictionResult: RemoteClearResult = RemoteClearResult.Success(listOf("HealthRestrictions!A2:L")),
        private val onReports: suspend () -> Unit = {},
    ) : HealthSheetsRepository {
        val clearCalls = mutableListOf<HealthSyncCategory>()
        override suspend fun upload(entry: HealthSyncOutboxEntity) = UploadResult.Success
        override suspend fun import(category: HealthSyncCategory) = 0
        override suspend fun clearAfterConfirmation(category: HealthSyncCategory): RemoteClearResult {
            clearCalls += category
            calls += category.name
            if (category == HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS) onReports()
            return when (category) {
                HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS -> reportResult
                HealthSyncCategory.HEALTH_RESTRICTIONS -> restrictionResult
                else -> RemoteClearResult.Failure("unsupported")
            }
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

    private class PartialClearData : ClearDataUseCase {
        override suspend fun invoke(): Nothing = throw PrivateOriginalsClearFailure(
            listOf(File("health_documents")),
        )
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
        val categoryChanges = mutableListOf<Boolean>()
        override suspend fun onCategoryChanged(enabled: Boolean) { categoryChanges += enabled }
    }

    private class OrderedUploadScheduler(private val order: MutableList<String>) : UploadScheduler {
        val categoryChanges = mutableListOf<Boolean>()
        override fun schedule(workoutId: String) = Unit
        override suspend fun retry(workoutId: String) = Unit
        override suspend fun scheduleAllPending() = 0
        override suspend fun onCategoryChanged(enabled: Boolean) { categoryChanges += enabled; order += "schedule:workouts" }
    }

    private class FakeMeasurementUploadScheduler(private val pendingCount: Int = 0) : MeasurementUploadScheduler {
        var allCalls: Int = 0
            private set
        override suspend fun schedule(measurementId: String) = Unit
        override suspend fun retry(measurementId: String) = Unit
        override suspend fun scheduleAllPending(): Int {
            allCalls++
            return pendingCount
        }
        val categoryChanges = mutableListOf<Boolean>()
        override suspend fun onCategoryChanged(enabled: Boolean) { categoryChanges += enabled }
    }

    private class OrderedMeasurementScheduler(private val order: MutableList<String>) : MeasurementUploadScheduler {
        val categoryChanges = mutableListOf<Boolean>()
        override suspend fun schedule(measurementId: String) = Unit
        override suspend fun retry(measurementId: String) = Unit
        override suspend fun scheduleAllPending() = 0
        override suspend fun onCategoryChanged(enabled: Boolean) { categoryChanges += enabled; order += "schedule:measurements" }
    }

    private class OrderedHealthScheduler(private val order: MutableList<String>) : HealthSyncScheduler {
        override suspend fun schedule(entry: HealthSyncOutboxEntity) = Unit
        override suspend fun schedulePending(category: HealthSyncCategory) = 0
        override suspend fun onCategoryChanged(category: HealthSyncCategory, enabled: Boolean) { order += "schedule:$category" }
    }

    private class FakeHealthSyncScheduler(private val pendingCount: Int = 0) : HealthSyncScheduler {
        val categoryChanges = mutableListOf<Pair<HealthSyncCategory, Boolean>>()
        val pendingCategories = mutableListOf<HealthSyncCategory>()
        override suspend fun schedule(entry: HealthSyncOutboxEntity) = Unit
        override suspend fun schedulePending(category: HealthSyncCategory): Int {
            pendingCategories += category
            return pendingCount
        }
        override suspend fun onCategoryChanged(category: HealthSyncCategory, enabled: Boolean) {
            categoryChanges += category to enabled
        }
    }

    private class FakeRoutineUploadScheduler(private val pendingCount: Int = 0) : RoutineUploadScheduler {
        var allCalls: Int = 0
            private set
        override fun schedule(syncId: String) = Unit
        override fun scheduleDeletion(syncId: String, updatedAt: Long) = Unit
        override suspend fun scheduleAll(): Int {
            allCalls++
            return pendingCount
        }
        val categoryChanges = mutableListOf<Boolean>()
        override suspend fun onCategoryChanged(enabled: Boolean) { categoryChanges += enabled }
    }

    private class FakeConfigurationUploadScheduler : ConfigurationUploadScheduler {
        val categoryChanges = mutableListOf<Boolean>()
        override fun scheduleExercise(syncId: String) = Unit
        override fun scheduleGym(syncId: String) = Unit
        override fun scheduleGymDeletion(syncId: String, updatedAt: Long) = Unit
        override suspend fun scheduleAll() = 0
        override suspend fun onCategoryChanged(enabled: Boolean) { categoryChanges += enabled }
    }

    private class FakeAiApiKeyStore : AiApiKeyStore {
        private val configured = MutableStateFlow(false)

        var savedKey: String? = null
            private set

        override val isConfigured: Flow<Boolean> = configured

        override suspend fun save(value: String) {
            savedKey = value
            configured.value = true
        }

        override suspend fun read(): String? = savedKey

        override suspend fun preview(): String? = savedKey?.let(::maskedAiApiKeyPreview)

        override suspend fun clear() {
            savedKey = null
            configured.value = false
        }
    }

    private class HangingAiModelCatalog : AiModelCatalog {
        override suspend fun getModels(): List<AiModel> = awaitCancellation()
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
