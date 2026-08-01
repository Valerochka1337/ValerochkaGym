package com.valerochka1337.valerochkagym.data.google

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.valerochka1337.valerochkagym.R
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OAuth scopes, необходимые приложению: запись в Google Sheets и создание событий календаря. */
private val REQUIRED_SCOPES = listOf(
    Scope("https://www.googleapis.com/auth/spreadsheets"),
    Scope("https://www.googleapis.com/auth/calendar.events"),
)

/**
 * Реализация [GoogleAuth] на Credential Manager (вход) и AuthorizationClient (OAuth-доступ).
 *
 * Вход и запрос согласия требуют Activity-контекст, поэтому приходят в методы параметром;
 * получение токена без UI ([getAccessToken]) работает от application-контекста. Класс не
 * дёргает Sheets/Calendar API — это seam для Стадий 19/21.
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : GoogleAuth {

    private val credentialManager: CredentialManager by lazy { CredentialManager.create(context) }

    override suspend fun signIn(activity: Activity): Result<String> {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(context.getString(R.string.google_web_client_id))
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
        return try {
            val response = credentialManager.getCredential(activity, request)
            val credential = GoogleIdTokenCredential.createFrom(response.credential.data)
            val email = credential.id
            settingsRepository.setGoogleEmail(email)
            Result.success(email)
        } catch (cancellation: GetCredentialCancellationException) {
            // Пользователь закрыл диалог выбора аккаунта — тихо, без побочных эффектов.
            Result.failure(cancellation)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun authorize(activity: Activity): AuthorizeOutcome =
        try {
            val result = requestAuthorization(activity)
            val pendingIntent = result.pendingIntent
            if (result.hasResolution() && pendingIntent != null) {
                AuthorizeOutcome.NeedsConsent(pendingIntent)
            } else {
                AuthorizeOutcome.Granted
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AuthorizeOutcome.Failed(e)
        }

    override suspend fun getAccessToken(): TokenResult =
        try {
            val result = requestAuthorization(context)
            val token = result.accessToken
            when {
                // Есть resolution → требуется согласие пользователя, токена пока нет.
                result.hasResolution() -> TokenResult.NeedsConsent
                token != null -> TokenResult.Success(token)
                else -> TokenResult.NeedsConsent
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            TokenResult.Failed(e)
        }

    override suspend fun signOut() {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (c: CancellationException) {
            throw c
        } catch (_: ClearCredentialException) {
            // Не критично: даже если очистка состояния Credential Manager не удалась,
            // всё равно стираем сохранённый email — для пользователя это и есть «выход».
        }
        settingsRepository.setGoogleEmail(null)
    }

    private suspend fun requestAuthorization(authContext: Context): AuthorizationResult {
        val request = AuthorizationRequest.Builder()
            .setRequestedScopes(REQUIRED_SCOPES)
            .build()
        return Identity.getAuthorizationClient(authContext).authorize(request).await()
    }
}

/** Ожидание результата GMS [Task] в корутине без зависимости от play-services-coroutines. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
