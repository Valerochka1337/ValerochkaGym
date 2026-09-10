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
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

interface BackendTransport {
  val json: Json

  /** Accepted optional capabilities for the current server response chain. */
  val acceptedCapabilities: Set<String>
    get() = emptySet()

  suspend fun public(method: String, path: String, body: JsonElement? = null): JsonElement

  suspend fun authorized(method: String, path: String, body: JsonElement? = null): JsonElement

  /** Additional request headers for owner-scoped optional backend features. */
  suspend fun authorized(
      method: String,
      path: String,
      body: JsonElement? = null,
      headers: Map<String, String>,
  ): JsonElement = authorized(method, path, body)

  /**
   * Legacy JSON convenience for non-owner-sensitive callers. Owner-sensitive flows must consume a
   * response-bound [BackendResponse] from a production transport and reject a null owner.
   */
  suspend fun authorizedResponse(
      method: String,
      path: String,
      body: JsonElement? = null,
      headers: Map<String, String> = emptyMap(),
      retryOnUnauthorized: Boolean = true,
  ): BackendResponse {
    val response = authorized(method, path, body, headers)
    return BackendResponse(
        body = response,
        rawBody = json.encodeToString(JsonElement.serializer(), response).encodeToByteArray(),
        acceptedCapabilities = acceptedCapabilities,
        owner = null,
    )
  }

  /** Sends previously journaled bytes verbatim; unsupported transports fail closed. */
  suspend fun authorizedRawResponse(
      method: String,
      path: String,
      rawBody: ByteArray,
      headers: Map<String, String> = emptyMap(),
      expectedOwner: String? = null,
      expectedSessionEpoch: Long? = null,
      retryOnUnauthorized: Boolean = false,
      maxResponseBytes: Int? = null,
  ): BackendResponse = throw UnsupportedOperationException("Raw backend transport is required")
}

data class BackendResponse(
    val body: JsonElement,
    val rawBody: ByteArray,
    val acceptedCapabilities: Set<String>,
    val owner: String?,
    /** Snapshot of the authenticated session that dispatched this exact request. */
    val sessionEpoch: Long = 0L,
)

data class BackendSessionSnapshot(val tokens: BackendTokens, val epoch: Long)

interface BackendSessionStore {
  val session: kotlinx.coroutines.flow.StateFlow<BackendTokens?>

  fun save(tokens: BackendTokens?)

  /** Monotonically changes on every save, including A → B → A with equal token values. */
  val sessionEpoch: Long
    get() = 0L

  /** The production implementation returns tokens and epoch under one lock. */
  fun snapshot(): BackendSessionSnapshot? =
      session.value?.let { BackendSessionSnapshot(it, sessionEpoch) }

  fun replaceIfCurrent(expectedRefreshToken: String, replacement: BackendTokens?): Boolean {
    if (session.value?.refreshToken != expectedRefreshToken) return false
    save(replacement)
    return true
  }
}

@Singleton
class BackendTokenStore @Inject constructor(@ApplicationContext context: Context) :
    BackendSessionStore {
  private val file = AtomicFile(File(context.noBackupFilesDir, "backend-session.bin"))
  private val json = Json { ignoreUnknownKeys = true }
  private val state = MutableStateFlow(load())
  private var epoch = 0L
  override val session = state.asStateFlow()
  override val sessionEpoch: Long
    @Synchronized get() = epoch

  @Synchronized
  override fun snapshot(): BackendSessionSnapshot? =
      state.value?.let { BackendSessionSnapshot(it, epoch) }

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
  override fun replaceIfCurrent(
      expectedRefreshToken: String,
      replacement: BackendTokens?,
  ): Boolean {
    if (state.value?.refreshToken != expectedRefreshToken) return false
    save(replacement)
    return true
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
    epoch++
    state.value = tokens
  }
}

@Singleton
class BackendApi @Inject constructor(private val tokens: BackendSessionStore) : BackendTransport {
  internal constructor(
      tokens: BackendSessionStore,
      client: OkHttpClient,
      baseUrl: String,
  ) : this(tokens) {
    this.client = client
    this.baseUrl = baseUrl.toHttpUrl().toString().ensureTrailingSlash()
  }

  override val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
  }
  private var client = defaultClient()
  private var baseUrl = DEFAULT_BASE_URL
  private val refreshMutex = Mutex()

  private fun execute(
      method: String,
      path: String,
      body: ByteArray?,
      token: String?,
      headers: Map<String, String> = emptyMap(),
      owner: String? = null,
      sessionEpoch: Long = 0L,
      maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
  ): BackendResponse {
    val requestedCapabilities =
        (setOf(
                "calendar-plans",
                "exercise-hint",
                "annotated-workout-writes",
                "profile",
                "health-ledger-v1",
            ) +
                headers["X-Gym-Capabilities"]
                    .orEmpty()
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty))
            .sorted()
            .joinToString(",")
    val request =
        Request.Builder()
            .url("${baseUrl}v1$path")
            .header("X-Gym-Sync-Version", "2")
            .header("X-Gym-Capabilities", requestedCapabilities)
            .method(
                method,
                if (method in setOf("GET", "HEAD")) null
                else
                    (body ?: "{}".encodeToByteArray()).toRequestBody(
                        "application/json".toMediaType()
                    ),
            )
    if (token != null) request.header("Authorization", "Bearer $token")
    headers
        .filterKeys { it != "X-Gym-Capabilities" }
        .forEach { (name, value) -> request.header(name, value) }
    client.newCall(request.build()).execute().use { response ->
      val accepted =
          response
              .header("X-Gym-Capabilities")
              ?.split(',')
              ?.map(String::trim)
              ?.filter(String::isNotEmpty)
              ?.toSet() ?: emptySet()
      val bytes =
          response.body.byteStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var size = 0
            while (true) {
              val read = input.read(buffer)
              if (read < 0) break
              size += read
              if (size > maxResponseBytes)
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
      return BackendResponse(parsed, bytes, accepted, owner, sessionEpoch)
    }
  }

  override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement =
      withContext(Dispatchers.IO) {
        execute(method, path, body?.toString()?.encodeToByteArray(), null).body
      }

  override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement =
      authorized(method, path, body, emptyMap())

  override suspend fun authorized(
      method: String,
      path: String,
      body: JsonElement?,
      headers: Map<String, String>,
  ): JsonElement = authorizedResponse(method, path, body, headers).body

  override suspend fun authorizedResponse(
      method: String,
      path: String,
      body: JsonElement?,
      headers: Map<String, String>,
      retryOnUnauthorized: Boolean,
  ): BackendResponse =
      authorizedRawResponse(
          method = method,
          path = path,
          rawBody = (body?.toString() ?: "{}").encodeToByteArray(),
          headers = headers,
          retryOnUnauthorized = retryOnUnauthorized,
      )

  override suspend fun authorizedRawResponse(
      method: String,
      path: String,
      rawBody: ByteArray,
      headers: Map<String, String>,
      expectedOwner: String?,
      expectedSessionEpoch: Long?,
      retryOnUnauthorized: Boolean,
      maxResponseBytes: Int?,
  ): BackendResponse =
      withContext(Dispatchers.IO) {
        val dispatch =
            tokens.snapshot() ?: throw BackendException(401, "unauthorized", "Войдите в аккаунт")
        val session = dispatch.tokens
        val epoch = dispatch.epoch
        if (
            (expectedOwner != null && session.userId != expectedOwner) ||
                (expectedSessionEpoch != null && epoch != expectedSessionEpoch)
        )
            throw BackendException(401, "owner_changed", "Аккаунт изменился")
        if (tokens.snapshot() != dispatch) {
          throw BackendException(401, "owner_changed", "Аккаунт изменился")
        }
        try {
          execute(
              method,
              path,
              rawBody,
              session.accessToken,
              headers,
              session.userId,
              epoch,
              maxResponseBytes ?: DEFAULT_MAX_RESPONSE_BYTES,
          )
        } catch (e: BackendException) {
          if (e.status != 401 || !retryOnUnauthorized) throw e
          refreshMutex.withLock {
            val current = tokens.snapshot()?.tokens ?: throw e
            if (current.userId != session.userId) throw e
            if (current.accessToken == session.accessToken) {
              try {
                val updated =
                    json.decodeFromJsonElement<BackendTokens>(
                        execute(
                                "POST",
                                "/auth/refresh",
                                buildJsonObject { put("refreshToken", current.refreshToken) }
                                    .toString()
                                    .encodeToByteArray(),
                                null,
                            )
                            .body
                    )
                // Never restore a session which the user logged out of while the request was in
                // flight.
                if (!tokens.replaceIfCurrent(current.refreshToken, updated)) throw e
              } catch (failure: BackendException) {
                if (failure.status == 401) tokens.replaceIfCurrent(current.refreshToken, null)
                throw failure
              }
            }
          }
          val refreshedDispatch = tokens.snapshot() ?: throw e
          val refreshed = refreshedDispatch.tokens.takeIf { it.userId == session.userId } ?: throw e
          val refreshedEpoch = refreshedDispatch.epoch
          if (
              (expectedOwner != null && refreshed.userId != expectedOwner) ||
                  (expectedSessionEpoch != null && refreshedEpoch != expectedSessionEpoch)
          )
              throw e
          if (tokens.snapshot() != refreshedDispatch) throw e
          execute(
              method,
              path,
              rawBody,
              refreshed.accessToken,
              headers,
              refreshed.userId,
              refreshedEpoch,
              maxResponseBytes ?: DEFAULT_MAX_RESPONSE_BYTES,
          )
        }
      }

  private companion object {
    const val DEFAULT_BASE_URL = "https://api.valerochkagym.tech/"
    const val DEFAULT_MAX_RESPONSE_BYTES = 20 * 1024 * 1024

    fun defaultClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
  }
}

private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"
