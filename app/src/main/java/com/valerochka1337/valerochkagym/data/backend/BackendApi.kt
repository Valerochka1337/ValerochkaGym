package com.valerochka1337.valerochkagym.data.backend

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

interface BackendTransport {
  val json: Json

  suspend fun public(method: String, path: String, body: JsonElement? = null): JsonElement

  suspend fun authorized(method: String, path: String, body: JsonElement? = null): JsonElement
}

interface BackendSessionStore {
  val session: kotlinx.coroutines.flow.StateFlow<BackendTokens?>

  fun save(tokens: BackendTokens?)
}

@Singleton
class BackendTokenStore @Inject constructor(@param:ApplicationContext context: Context) :
    BackendSessionStore {
  private val file = AtomicFile(File(context.noBackupFilesDir, "backend-session.bin"))
  private val json = Json { ignoreUnknownKeys = true }
  private val state = MutableStateFlow(load())
  override val session = state.asStateFlow()

  private fun key(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    return store.getKey("gym_backend_session", null) as? SecretKey
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
          init(
              KeyGenParameterSpec.Builder(
                      "gym_backend_session",
                      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                  )
                  .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                  .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                  .build()
          )
          generateKey()
        }
  }

  private fun load(): BackendTokens? =
      try {
        val bytes = file.readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        json.decodeFromString<BackendTokens>(
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()
        )
      } catch (_: Exception) {
        null
      }

  @Synchronized
  override fun save(tokens: BackendTokens?) {
    if (tokens == null) file.delete()
    else {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key())
      val bytes = cipher.iv + cipher.doFinal(json.encodeToString(tokens).toByteArray())
      val stream = file.startWrite()
      try {
        stream.write(bytes)
        file.finishWrite(stream)
      } catch (e: Exception) {
        file.failWrite(stream)
        throw e
      }
    }
    state.value = tokens
  }
}

@Singleton
class BackendApi @Inject constructor(private val tokens: BackendTokenStore) : BackendTransport {
  override val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
  }
  private val client =
      OkHttpClient.Builder()
          .connectTimeout(15, TimeUnit.SECONDS)
          .callTimeout(90, TimeUnit.SECONDS)
          .retryOnConnectionFailure(false)
          .build()
  private val refreshMutex = Mutex()

  private fun execute(
      method: String,
      path: String,
      body: JsonElement?,
      token: String?,
  ): JsonElement {
    val request =
        Request.Builder()
            .url("https://api.valerochkagym.tech/v1$path")
            .header("X-Gym-Sync-Version", "2")
            .method(
                method,
                if (method in setOf("GET", "HEAD")) null
                else (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()),
            )
    if (token != null) request.header("Authorization", "Bearer $token")
    client.newCall(request.build()).execute().use { response ->
      val bytes =
          response.body.byteStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var size = 0
            while (true) {
              val read = input.read(buffer)
              if (read < 0) break
              size += read
              if (size > 20 * 1024 * 1024)
                  throw BackendException(413, "response_too_large", "Ответ сервера слишком большой")
              out.write(buffer, 0, read)
            }
            out.toByteArray()
          }
      val parsed =
          try {
            json.parseToJsonElement(bytes.decodeToString().ifBlank { "{}" })
          } catch (_: Exception) {
            JsonObject(emptyMap())
          }
      if (!response.isSuccessful) {
        val error = parsed as? JsonObject
        throw BackendException(
            response.code,
            error?.get("code")?.jsonPrimitive?.content ?: "http_error",
            error?.get("message")?.jsonPrimitive?.content ?: "Сервер недоступен. Повторите позже",
        )
      }
      return parsed
    }
  }

  override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement =
      withContext(Dispatchers.IO) { execute(method, path, body, null) }

  override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement =
      withContext(Dispatchers.IO) {
        val session =
            tokens.session.value ?: throw BackendException(401, "unauthorized", "Войдите в аккаунт")
        try {
          execute(method, path, body, session.accessToken)
        } catch (e: BackendException) {
          if (e.status != 401) throw e
          refreshMutex.withLock {
            val current = tokens.session.value ?: throw e
            if (current.userId != session.userId) throw e
            if (current.accessToken == session.accessToken) {
              try {
                val updated =
                    json.decodeFromJsonElement<BackendTokens>(
                        execute(
                            "POST",
                            "/auth/refresh",
                            buildJsonObject { put("refreshToken", current.refreshToken) },
                            null,
                        )
                    )
                // Never restore a session which the user logged out of while the request was in
                // flight.
                if (tokens.session.value?.refreshToken != current.refreshToken) throw e
                tokens.save(updated)
              } catch (failure: BackendException) {
                if (failure.status == 401) tokens.save(null)
                throw failure
              }
            }
          }
          val refreshed = tokens.session.value?.takeIf { it.userId == session.userId } ?: throw e
          execute(method, path, body, refreshed.accessToken)
        }
      }
}
