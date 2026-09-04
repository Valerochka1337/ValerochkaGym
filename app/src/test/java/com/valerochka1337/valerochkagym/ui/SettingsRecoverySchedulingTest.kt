package com.valerochka1337.valerochkagym.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.backup.ClearDataUseCase
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.backup.ExportResult
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.ImportResult
import com.valerochka1337.valerochkagym.data.google.TokenResult
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepository
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.ui.settings.SettingsViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import com.valerochka1337.valerochkagym.worker.UploadScheduler
import com.valerochka1337.valerochkagym.worker.WeeklyScheduleRecoveryScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRecoverySchedulingTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun `successful consent schedules paused weekly recovery without an app restart`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val consent =
            PendingIntent.getActivity(
                context,
                1,
                Intent(context, Activity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val auth = ConsentThenGrantedGoogleAuth(consent)
        val recovery = FakeWeeklyScheduleRecoveryScheduler()
        val viewModel =
            SettingsViewModel(
                settingsRepository = SettingsRepository(FakeDataStore()),
                googleAuth = auth,
                uploadScheduler = NoOpUploadScheduler,
                importRepository = NoOpImportRepository,
                databaseExporter = NoOpDatabaseExporter,
                clearDataUseCase = NoOpClearDataUseCase,
                weeklyScheduleRecoveryScheduler = recovery,
            )
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        viewModel.signIn(activity)
        runCurrent()
        assertEquals(0, recovery.enqueues)
        assertEquals(0, recovery.wakes)

        viewModel.consentResolved(activity)
        runCurrent()

        assertEquals(2, auth.authorizeCalls)
        assertEquals(0, recovery.enqueues)
        assertEquals(1, recovery.wakes)
      }

  private class ConsentThenGrantedGoogleAuth(
      private val consent: PendingIntent,
  ) : GoogleAuth {
    var authorizeCalls = 0
      private set

    override suspend fun signIn(activity: Activity): Result<String> =
        Result.success("user@example.com")

    override suspend fun authorize(activity: Activity): AuthorizeOutcome =
        if (authorizeCalls++ == 0) {
          AuthorizeOutcome.NeedsConsent(consent)
        } else {
          AuthorizeOutcome.Granted
        }

    override suspend fun getAccessToken(): TokenResult = TokenResult.NeedsConsent

    override suspend fun signOut() = Unit
  }

  private class FakeWeeklyScheduleRecoveryScheduler : WeeklyScheduleRecoveryScheduler {
    var enqueues = 0
      private set

    var wakes = 0
      private set

    override fun enqueue() {
      enqueues++
    }

    override fun wake() {
      wakes++
    }
  }

  private class FakeDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(mutablePreferencesOf())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = transform(state.value).also { state.value = it }
  }

  private data object NoOpUploadScheduler : UploadScheduler {
    override fun schedule(workoutId: String) = Unit

    override suspend fun retry(workoutId: String) = Unit

    override suspend fun scheduleAllPending(): Int = 0
  }

  private data object NoOpImportRepository : WorkoutImportRepository {
    override suspend fun importAll(): ImportResult = ImportResult.NothingToImport
  }

  private data object NoOpDatabaseExporter : DatabaseExporter {
    override suspend fun export(target: Uri): ExportResult = ExportResult.Success
  }

  private data object NoOpClearDataUseCase : ClearDataUseCase {
    override suspend fun invoke() = Unit
  }
}
