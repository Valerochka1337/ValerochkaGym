package com.valerochka1337.valerochkagym.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.backup.ClearDataUseCase
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.backup.ExportResult
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.ImportResult
import com.valerochka1337.valerochkagym.data.google.TokenResult
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepository
import com.valerochka1337.valerochkagym.data.settings.CalendarAccountIdentity
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.ui.settings.SettingsViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import com.valerochka1337.valerochkagym.worker.UploadScheduler
import com.valerochka1337.valerochkagym.worker.WeeklyScheduleRecoveryScheduler
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

        viewModel.connectOther(activity)
        runCurrent()
        assertEquals(0, recovery.enqueues)
        assertEquals(0, recovery.wakes)

        val request = viewModel.consentRequests.first()
        viewModel.consentResolved(activity, request.operationNonce, granted = true)
        runCurrent()

        assertEquals(2, auth.authorizeCalls)
        assertEquals(0, recovery.enqueues)
        assertEquals(1, recovery.wakes)
      }

  @Test
  fun `switch keeps account A until exact account B token is verified`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val consent =
            PendingIntent.getActivity(
                context,
                2,
                Intent(context, Activity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val auth = SwitchingGoogleAuth(consent)
        val identity = FakeIdentity("a@example.com")
        val vm = model(auth, identity)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        vm.connectOther(activity)
        vm.connectOther(activity)
        runCurrent()
        assertEquals(1, auth.selectCalls)
        assertEquals("a@example.com", identity.connectedCalendarEmail.value)
        assertEquals("b@example.com", identity.preferredCalendarEmail.value)
        val request = vm.consentRequests.first()
        vm.consentResolved(activity, request.operationNonce, granted = true)
        runCurrent()

        assertEquals("b@example.com", identity.connectedCalendarEmail.value)
      }

  @Test
  fun `cancel and stale consent result retain the connected account`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val consent =
            PendingIntent.getActivity(
                context,
                3,
                Intent(context, Activity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val identity = FakeIdentity("a@example.com")
        val vm = model(SwitchingGoogleAuth(consent), identity)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        vm.connectOther(activity)
        runCurrent()
        val request = vm.consentRequests.first()
        vm.consentResolved(activity, "stale", granted = true)
        runCurrent()
        assertEquals("a@example.com", identity.connectedCalendarEmail.value)
        vm.consentResolved(activity, request.operationNonce, granted = false)
        runCurrent()

        assertEquals("a@example.com", identity.connectedCalendarEmail.value)
        assertFalse(vm.uiState.value.authBusy)
      }

  @Test
  fun `disconnect revokes only the connected calendar account`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val auth = SwitchingGoogleAuth(null)
        val identity = FakeIdentity("a@example.com")
        val vm = model(auth, identity)

        vm.disconnectCalendar()
        runCurrent()

        assertEquals(listOf("a@example.com"), auth.revoked)
        assertNull(identity.connectedCalendarEmail.value)
        assertEquals(0, auth.signOutCalls)
      }

  @Test
  fun `recreated view model reissues the saved target rather than a changed preference`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val consent =
            PendingIntent.getActivity(
                context,
                4,
                Intent(context, Activity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val auth = AlwaysConsentGoogleAuth(consent)
        val identity =
            FakeIdentity("a@example.com").apply { preferredCalendarEmail.value = "b@example.com" }
        val saved = SavedStateHandle()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val first = model(auth, identity, saved)

        first.connectPreferred(activity)
        runCurrent()
        first.consentRequests.first()
        identity.preferredCalendarEmail.value = "c@example.com"
        val recreated = model(auth, identity, saved)
        recreated.resumePendingCalendarOperation(activity)
        runCurrent()

        assertEquals(listOf("b@example.com", "b@example.com"), auth.authorizedTargets)
        assertEquals("a@example.com", identity.connectedCalendarEmail.value)
      }

  @Test
  fun `duplicate resume and consent result each launch one attempt for the saved nonce`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val consent =
            PendingIntent.getActivity(
                context,
                5,
                Intent(context, Activity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val auth = AlwaysConsentGoogleAuth(consent)
        val identity =
            FakeIdentity("a@example.com").apply { preferredCalendarEmail.value = "b@example.com" }
        val saved = SavedStateHandle()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val first = model(auth, identity, saved)

        first.connectPreferred(activity)
        runCurrent()
        first.consentRequests.first()
        val recreated = model(auth, identity, saved)
        recreated.resumePendingCalendarOperation(activity)
        recreated.resumePendingCalendarOperation(activity)
        runCurrent()
        val resumedRequest = recreated.consentRequests.first()
        assertEquals(2, auth.authorizedTargets.size)

        recreated.consentResolved(activity, resumedRequest.operationNonce, granted = true)
        recreated.consentResolved(activity, resumedRequest.operationNonce, granted = true)
        runCurrent()

        assertEquals(3, auth.authorizedTargets.size)
        assertEquals(
            listOf("b@example.com", "b@example.com", "b@example.com"),
            auth.authorizedTargets,
        )
      }

  @Test
  fun `disconnect claims busy synchronously against duplicate disconnect and connect`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val gate = CompletableDeferred<Unit>()
        val auth = GatedSecurityGoogleAuth(gate)
        val identity = FakeIdentity("a@example.com")
        val vm = model(auth, identity)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        vm.disconnectCalendar()
        vm.disconnectCalendar()
        vm.connectOther(activity)
        runCurrent()

        assertEquals(listOf("a@example.com"), auth.revoked)
        assertEquals(0, auth.selectCalls)
        assertEquals("a@example.com", identity.connectedCalendarEmail.value)

        gate.complete(Unit)
        runCurrent()
        vm.connectOther(activity)
        runCurrent()

        assertEquals(1, auth.selectCalls)
        assertEquals("b@example.com", identity.connectedCalendarEmail.value)
      }

  @Test
  fun `cancelled account A cannot apply late authorize consent or token after account B starts`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val consent =
            PendingIntent.getActivity(
                context,
                6,
                Intent(context, Activity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        for (lateStage in LateStage.entries) {
          val gate = CompletableDeferred<Unit>()
          val auth = LateOutcomeGoogleAuth(consent, lateStage, gate)
          val identity =
              FakeIdentity("existing@example.com").apply {
                preferredCalendarEmail.value = "a@example.com"
              }
          val vm = model(auth, identity)

          vm.connectPreferred(activity)
          runCurrent()
          val request = vm.consentRequests.first()
          vm.consentResolved(activity, request.operationNonce, granted = true)
          runCurrent()
          vm.consentResolved(activity, request.operationNonce, granted = false)
          vm.connectOther(activity)
          runCurrent()

          assertEquals("b@example.com", identity.connectedCalendarEmail.value)
          gate.complete(Unit)
          runCurrent()

          assertEquals("b@example.com", identity.connectedCalendarEmail.value)
          assertEquals(
              if (lateStage == LateStage.TOKEN) listOf("a@example.com", "b@example.com")
              else listOf("b@example.com"),
              auth.tokenTargets,
          )
          val unexpectedConsent = async { vm.consentRequests.first() }
          runCurrent()
          assertFalse(unexpectedConsent.isCompleted)
          unexpectedConsent.cancel()
        }
      }

  @Test
  fun `cancelled account A cannot overwrite B at the final identity write`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val consent =
            PendingIntent.getActivity(
                context,
                8,
                Intent(context, Activity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val finalWriteGate = CompletableDeferred<Unit>()
        val identity =
            FinalWriteIdentity(finalWriteGate).apply {
              preferredCalendarEmail.value = "a@example.com"
            }
        val vm = model(FinalWriteGoogleAuth(consent), identity)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        vm.connectPreferred(activity)
        runCurrent()
        val request = vm.consentRequests.first()
        vm.consentResolved(activity, request.operationNonce, granted = true)
        identity.accountAFinalWrite.await()

        vm.consentResolved(activity, request.operationNonce, granted = false)
        vm.connectOther(activity)
        runCurrent()
        assertEquals("b@example.com", identity.connectedCalendarEmail.value)

        finalWriteGate.complete(Unit)
        runCurrent()

        assertEquals("b@example.com", identity.connectedCalendarEmail.value)
      }

  @Test
  fun `revoke failure and exception retain calendar connection and backend session`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        for (failure in listOf<Result<Unit>?>(Result.failure(IOException("denied")), null)) {
          val auth = FailingRevokeGoogleAuth(failure)
          val identity = FakeIdentity("a@example.com")
          val vm = model(auth, identity)

          vm.disconnectCalendar()
          runCurrent()

          assertEquals("a@example.com", identity.connectedCalendarEmail.value)
          assertEquals(0, auth.signOutCalls)
          assertFalse(vm.uiState.value.authBusy)
        }
      }

  @Test
  fun `malformed saved calendar operation clears its busy gate after an activity result`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (malformed in
            listOf(
                mapOf(
                    "calendar_auth_busy" to true,
                    "calendar_auth_kind" to "UNKNOWN",
                    "calendar_auth_nonce" to "nonce",
                ),
                mapOf(
                    "calendar_auth_busy" to true,
                    "calendar_auth_kind" to "AUTHORIZE_OTHER",
                    "calendar_auth_nonce" to " ",
                ),
            )) {
          val saved = SavedStateHandle(malformed)
          val auth =
              AlwaysConsentGoogleAuth(
                  PendingIntent.getActivity(
                      context,
                      7,
                      Intent(context, Activity::class.java),
                      PendingIntent.FLAG_IMMUTABLE,
                  )
              )
          val identity = FakeIdentity("a@example.com")
          val vm = model(auth, identity, saved)
          val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

          vm.consentResolved(activity, "nonce", granted = false)
          runCurrent()

          assertFalse(saved["calendar_auth_busy"] ?: true)
          assertNull(saved["calendar_auth_kind"])
          assertNull(saved["calendar_auth_target"])
          assertNull(saved["calendar_auth_nonce"])
          assertEquals("a@example.com", identity.connectedCalendarEmail.value)
        }
      }

  private fun model(
      auth: GoogleAuth,
      identity: CalendarAccountIdentity,
      savedStateHandle: SavedStateHandle = SavedStateHandle(),
  ): SettingsViewModel =
      SettingsViewModel(
          settingsRepository = SettingsRepository(FakeDataStore()),
          googleAuth = auth,
          uploadScheduler = NoOpUploadScheduler,
          importRepository = NoOpImportRepository,
          databaseExporter = NoOpDatabaseExporter,
          clearDataUseCase = NoOpClearDataUseCase,
          calendarIdentity = identity,
          savedStateHandle = savedStateHandle,
      )

  private class AlwaysConsentGoogleAuth(private val consent: PendingIntent) : GoogleAuth {
    val authorizedTargets = mutableListOf<String>()

    override suspend fun selectAccount(activity: Activity) =
        Result.failure<String>(IllegalStateException())

    override suspend fun signIn(activity: Activity) =
        Result.failure<String>(IllegalStateException())

    override suspend fun authorize(activity: Activity) = AuthorizeOutcome.Failed(null)

    override suspend fun authorizeForAccount(
        activity: Activity,
        expectedEmail: String,
    ): AuthorizeOutcome {
      authorizedTargets += expectedEmail
      return AuthorizeOutcome.NeedsConsent(consent)
    }

    override suspend fun revokeCalendarAccess(expectedEmail: String): Result<Unit> =
        Result.failure(IllegalStateException())

    override suspend fun getAccessToken() = TokenResult.NeedsConsent

    override suspend fun getAccessTokenForAccount(expectedEmail: String) = TokenResult.NeedsConsent

    override suspend fun signOut() = Unit
  }

  private class SwitchingGoogleAuth(private val consent: PendingIntent?) : GoogleAuth {
    var authorizeCalls = 0
    var selectCalls = 0
    var signOutCalls = 0
    val revoked = mutableListOf<String>()

    override suspend fun signIn(activity: Activity) = Result.success("b@example.com")

    override suspend fun selectAccount(activity: Activity) =
        Result.success("b@example.com").also { selectCalls++ }

    override suspend fun authorize(activity: Activity): AuthorizeOutcome = AuthorizeOutcome.Granted

    override suspend fun authorizeForAccount(
        activity: Activity,
        expectedEmail: String,
    ): AuthorizeOutcome =
        if (consent != null && authorizeCalls++ == 0) AuthorizeOutcome.NeedsConsent(consent)
        else AuthorizeOutcome.Granted

    override suspend fun getAccessToken() = TokenResult.Success("token")

    override suspend fun getAccessTokenForAccount(expectedEmail: String) =
        TokenResult.Success("token")

    override suspend fun revokeCalendarAccess(expectedEmail: String): Result<Unit> =
        Result.success(Unit).also { revoked += expectedEmail }

    override suspend fun signOut() {
      signOutCalls++
    }
  }

  private class GatedSecurityGoogleAuth(
      private val revokeGate: CompletableDeferred<Unit>,
  ) : GoogleAuth {
    val revoked = mutableListOf<String>()
    var selectCalls = 0

    override suspend fun selectAccount(activity: Activity): Result<String> {
      selectCalls++
      return Result.success("b@example.com")
    }

    override suspend fun authorizeForAccount(
        activity: Activity,
        expectedEmail: String,
    ) = AuthorizeOutcome.Granted

    override suspend fun revokeCalendarAccess(expectedEmail: String): Result<Unit> {
      revoked += expectedEmail
      revokeGate.await()
      return Result.success(Unit)
    }

    override suspend fun signIn(activity: Activity) = Result.success("b@example.com")

    override suspend fun authorize(activity: Activity) = AuthorizeOutcome.Granted

    override suspend fun getAccessToken() = TokenResult.Success("token")

    override suspend fun getAccessTokenForAccount(expectedEmail: String) =
        TokenResult.Success("token")

    override suspend fun signOut() = Unit
  }

  private enum class LateStage {
    AUTHORIZE_GRANTED,
    TOKEN,
    NEEDS_CONSENT,
  }

  private class LateOutcomeGoogleAuth(
      private val consent: PendingIntent,
      private val lateStage: LateStage,
      private val gate: CompletableDeferred<Unit>,
  ) : GoogleAuth {
    private var accountAAuthorizeCalls = 0
    val tokenTargets = mutableListOf<String>()

    override suspend fun selectAccount(activity: Activity) = Result.success("b@example.com")

    override suspend fun authorizeForAccount(
        activity: Activity,
        expectedEmail: String,
    ): AuthorizeOutcome {
      if (expectedEmail == "b@example.com") return AuthorizeOutcome.Granted
      accountAAuthorizeCalls++
      if (accountAAuthorizeCalls == 1) return AuthorizeOutcome.NeedsConsent(consent)
      return when (lateStage) {
        LateStage.AUTHORIZE_GRANTED -> {
          gate.await()
          AuthorizeOutcome.Granted
        }
        LateStage.TOKEN -> AuthorizeOutcome.Granted
        LateStage.NEEDS_CONSENT -> {
          gate.await()
          AuthorizeOutcome.NeedsConsent(consent)
        }
      }
    }

    override suspend fun revokeCalendarAccess(expectedEmail: String) = Result.success(Unit)

    override suspend fun signIn(activity: Activity) = Result.success("b@example.com")

    override suspend fun authorize(activity: Activity) = AuthorizeOutcome.Granted

    override suspend fun getAccessToken() = TokenResult.Success("token")

    override suspend fun getAccessTokenForAccount(expectedEmail: String): TokenResult {
      tokenTargets += expectedEmail
      if (expectedEmail == "a@example.com" && lateStage == LateStage.TOKEN) gate.await()
      return TokenResult.Success("token")
    }

    override suspend fun signOut() = Unit
  }

  private class FailingRevokeGoogleAuth(
      private val failure: Result<Unit>?,
  ) : GoogleAuth {
    var signOutCalls = 0

    override suspend fun selectAccount(activity: Activity) = Result.success("b@example.com")

    override suspend fun authorizeForAccount(
        activity: Activity,
        expectedEmail: String,
    ) = AuthorizeOutcome.Granted

    override suspend fun revokeCalendarAccess(expectedEmail: String): Result<Unit> =
        failure ?: throw IOException("network")

    override suspend fun signIn(activity: Activity) = Result.success("b@example.com")

    override suspend fun authorize(activity: Activity) = AuthorizeOutcome.Granted

    override suspend fun getAccessToken() = TokenResult.Success("token")

    override suspend fun getAccessTokenForAccount(expectedEmail: String) =
        TokenResult.Success("token")

    override suspend fun signOut() {
      signOutCalls++
    }
  }

  private class FakeIdentity(connected: String?) : CalendarAccountIdentity {
    override val preferredCalendarEmail = MutableStateFlow<String?>(null)
    override val connectedCalendarEmail = MutableStateFlow(connected)

    override suspend fun setPreferredCalendarEmail(email: String) {
      preferredCalendarEmail.value = email
    }

    override suspend fun setConnectedCalendarEmail(email: String) {
      connectedCalendarEmail.value = email
    }

    override suspend fun commitConnectedCalendarEmail(
        email: String,
        canCommit: () -> Boolean,
    ): Boolean {
      if (!canCommit()) return false
      connectedCalendarEmail.value = email
      return true
    }

    override suspend fun clearConnectedCalendarEmail(expectedEmail: String): Boolean {
      if (connectedCalendarEmail.value != expectedEmail) return false
      connectedCalendarEmail.value = null
      return true
    }
  }

  private class FinalWriteIdentity(
      private val accountAFinalWriteGate: CompletableDeferred<Unit>,
  ) : CalendarAccountIdentity {
    val accountAFinalWrite = CompletableDeferred<Unit>()
    override val preferredCalendarEmail = MutableStateFlow<String?>(null)
    override val connectedCalendarEmail = MutableStateFlow<String?>("existing@example.com")

    override suspend fun setPreferredCalendarEmail(email: String) {
      preferredCalendarEmail.value = email
    }

    override suspend fun setConnectedCalendarEmail(email: String) {
      connectedCalendarEmail.value = email
    }

    override suspend fun commitConnectedCalendarEmail(
        email: String,
        canCommit: () -> Boolean,
    ): Boolean {
      if (email == "a@example.com") {
        accountAFinalWrite.complete(Unit)
        accountAFinalWriteGate.await()
      }
      if (!canCommit()) return false
      connectedCalendarEmail.value = email
      return true
    }

    override suspend fun clearConnectedCalendarEmail(expectedEmail: String): Boolean {
      if (connectedCalendarEmail.value != expectedEmail) return false
      connectedCalendarEmail.value = null
      return true
    }
  }

  private class FinalWriteGoogleAuth(private val consent: PendingIntent) : GoogleAuth {
    private var accountAAuthorizeCalls = 0

    override suspend fun selectAccount(activity: Activity) = Result.success("b@example.com")

    override suspend fun authorizeForAccount(
        activity: Activity,
        expectedEmail: String,
    ): AuthorizeOutcome =
        if (expectedEmail == "a@example.com" && accountAAuthorizeCalls++ == 0) {
          AuthorizeOutcome.NeedsConsent(consent)
        } else {
          AuthorizeOutcome.Granted
        }

    override suspend fun revokeCalendarAccess(expectedEmail: String) = Result.success(Unit)

    override suspend fun signIn(activity: Activity) = Result.success("b@example.com")

    override suspend fun authorize(activity: Activity) = AuthorizeOutcome.Granted

    override suspend fun getAccessToken() = TokenResult.Success("token")

    override suspend fun getAccessTokenForAccount(expectedEmail: String) =
        TokenResult.Success("token")

    override suspend fun signOut() = Unit
  }

  private class ConsentThenGrantedGoogleAuth(
      private val consent: PendingIntent,
  ) : GoogleAuth {
    var authorizeCalls = 0
      private set

    override suspend fun signIn(activity: Activity): Result<String> =
        Result.success("user@example.com")

    override suspend fun selectAccount(activity: Activity): Result<String> = signIn(activity)

    override suspend fun authorize(activity: Activity): AuthorizeOutcome =
        if (authorizeCalls++ == 0) {
          AuthorizeOutcome.NeedsConsent(consent)
        } else {
          AuthorizeOutcome.Granted
        }

    override suspend fun authorizeForAccount(
        activity: Activity,
        expectedEmail: String,
    ): AuthorizeOutcome = authorize(activity)

    override suspend fun revokeCalendarAccess(expectedEmail: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun getAccessToken(): TokenResult = TokenResult.NeedsConsent

    override suspend fun getAccessTokenForAccount(expectedEmail: String): TokenResult =
        if (authorizeCalls >= 2) TokenResult.Success("token") else TokenResult.NeedsConsent

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
