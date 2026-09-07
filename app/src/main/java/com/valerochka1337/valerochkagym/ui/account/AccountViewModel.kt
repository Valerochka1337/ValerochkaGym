package com.valerochka1337.valerochkagym.ui.account

import android.app.Activity
import android.os.Build
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.valerochka1337.valerochkagym.R
import com.valerochka1337.valerochkagym.data.backend.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@HiltViewModel
class AccountViewModel
@Inject
constructor(
    private val api: BackendApi,
    private val tokens: BackendTokenStore,
    private val sync: BackendSync,
    private val scheduler: BackendSyncScheduler,
    private val exporter: com.valerochka1337.valerochkagym.data.backup.DatabaseExporter,
) : ViewModel() {
  val session = tokens.session
  val status = sync.status
  val conflict = sync.conflict
  val busy = MutableStateFlow(false)
  val message = MutableStateFlow<String?>(null)
  val sessions = MutableStateFlow<List<BackendSession>>(emptyList())

  private fun task(block: suspend () -> Unit) {
    if (busy.value) return
    viewModelScope.launch {
      busy.value = true
      message.value = null
      try {
        block()
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        message.value =
            if (e is BackendException) e.message
            else "Не удалось выполнить действие. Проверьте подключение и повторите"
      } finally {
        busy.value = false
      }
    }
  }

  private suspend fun accept(value: JsonElement) {
    val session = api.json.decodeFromJsonElement<BackendTokens>(value)
    sync.mutex.withLock {
      sync.claim(session.userId)
      withContext(Dispatchers.IO) { tokens.save(session) }
    }
    scheduler.enqueue()
  }

  fun submit(mode: String, email: String, password: String, code: String) = task {
    val body = buildJsonObject {
      put("email", email.trim())
      if (mode in setOf("login", "register", "reset")) put("password", password)
      if (mode in setOf("verify", "reset")) put("code", code)
      if (mode == "login") put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".take(100))
    }
    val path =
        when (mode) {
          "reset" -> "/auth/password/reset"
          "request-reset" -> "/auth/password/request"
          "resend" -> "/auth/verify/request"
          else -> "/auth/$mode"
        }
    val result = api.public("POST", path, body)
    if (mode == "login") accept(result)
    else
        message.value =
            when (mode) {
              "register",
              "resend",
              "request-reset" -> "Проверьте почту. Код действует 10 минут"
              "verify" -> "Email подтверждён. Теперь войдите с паролем"
              else -> "Пароль изменён. Войдите с новым паролем"
            }
  }

  fun google(activity: Activity, link: Boolean = false) = task {
    val nonce =
        api.public("POST", "/auth/google/nonce").jsonObject.getValue("nonce").jsonPrimitive.content
    val option =
        GetGoogleIdOption.Builder()
            .setServerClientId(activity.getString(R.string.google_web_client_id))
            .setFilterByAuthorizedAccounts(false)
            .setNonce(nonce)
            .build()
    val response =
        CredentialManager.create(activity)
            .getCredential(
                activity,
                GetCredentialRequest.Builder().addCredentialOption(option).build(),
            )
    val credential = GoogleIdTokenCredential.createFrom(response.credential.data)
    val body = buildJsonObject {
      put("idToken", credential.idToken)
      put("nonce", nonce)
      put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".take(100))
    }
    accept(
        if (link) api.authorized("POST", "/me/google", body)
        else api.public("POST", "/auth/google", body)
    )
  }

  fun synchronize(choice: String? = null) = task { sync.run(choice) }

  fun logout(all: Boolean = false) = task {
    if (sync.hasActiveWorkout())
        throw BackendException(409, "workout_active", "Сначала завершите тренировку")
    sync.mutex.withLock {
      api.authorized("POST", if (all) "/logout-all" else "/logout")
      withContext(Dispatchers.IO) { tokens.save(null) }
      sessions.value = emptyList()
    }
  }

  fun loadSessions() = task {
    sessions.value = api.json.decodeFromJsonElement(api.authorized("GET", "/sessions"))
  }

  fun revoke(id: String) = task {
    api.authorized("DELETE", "/sessions/$id")
    sessions.value = api.json.decodeFromJsonElement(api.authorized("GET", "/sessions"))
  }

  fun deletionCode() = task {
    api.authorized("POST", "/me/delete-code")
    message.value = "Введите код из письма, чтобы удалить аккаунт"
  }

  fun exportLocal(uri: android.net.Uri) = task {
    message.value =
        when (val result = exporter.export(uri)) {
          com.valerochka1337.valerochkagym.data.backup.ExportResult.Success ->
              "Локальная копия сохранена"
          is com.valerochka1337.valerochkagym.data.backup.ExportResult.Failure -> result.reason
        }
  }

  fun delete(code: String) = task {
    if (sync.hasActiveWorkout())
        throw BackendException(409, "workout_active", "Сначала завершите тренировку")
    sync.mutex.withLock {
      api.authorized("DELETE", "/me", buildJsonObject { put("code", code) })
      withContext(Dispatchers.IO) { tokens.save(null) }
      message.value = "Аккаунт удалён. Локальная копия сохранена для экспорта"
    }
  }
}
