package com.valerochka1337.valerochkagym.data.ai

import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.data.settings.AiApiKeyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class AiApiConnection(
    val baseUrl: String,
    val apiKey: String,
)

data class AiApiRequestConfiguration(
    val connection: AiApiConnection,
    val modelId: String,
)

/** Единый источник готовности и секретов для обоих ИИ-сценариев. */
interface AiApiConfigurationProvider {
    val isConfigured: Flow<Boolean>

    /** Адрес и ключ достаточны для запроса каталога моделей. */
    suspend fun connection(): AiApiConnection?

    /** Полная конфигурация для генерации: адрес, ключ и выбранная модель. */
    suspend fun requestConfiguration(): AiApiRequestConfiguration?
}

@Singleton
class StoredAiApiConfigurationProvider @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val keyStore: AiApiKeyStore,
) : AiApiConfigurationProvider {

    override val isConfigured: Flow<Boolean> = combine(
        settingsRepository.settings,
        keyStore.isConfigured,
    ) { settings, hasKey ->
        hasKey &&
            normalizeAiBaseUrl(settings.aiBaseUrl.orEmpty()) != null &&
            !settings.aiModelId.isNullOrBlank()
    }.distinctUntilChanged()

    override suspend fun connection(): AiApiConnection? {
        val settings = settingsRepository.settings.first()
        val baseUrl = normalizeAiBaseUrl(settings.aiBaseUrl.orEmpty()) ?: return null
        val apiKey = keyStore.read()?.takeIf { it.isNotBlank() } ?: return null
        return AiApiConnection(baseUrl = baseUrl, apiKey = apiKey)
    }

    override suspend fun requestConfiguration(): AiApiRequestConfiguration? {
        val settings = settingsRepository.settings.first()
        val baseUrl = normalizeAiBaseUrl(settings.aiBaseUrl.orEmpty()) ?: return null
        val apiKey = keyStore.read()?.takeIf { it.isNotBlank() } ?: return null
        val modelId = settings.aiModelId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return AiApiRequestConfiguration(
            connection = AiApiConnection(baseUrl = baseUrl, apiKey = apiKey),
            modelId = modelId,
        )
    }
}

/**
 * Храним HTTP(S) base URL с завершающим `/`. Пользователь может вставить домен, base URL или
 * один из полных endpoint-ов. Если путь ещё не заканчивается на `/v1`, добавляем этот API-префикс;
 * если схема не указана, выбираем HTTPS.
 */
fun normalizeAiBaseUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val candidate = if (SCHEME_SEPARATOR in trimmed) trimmed else "https://$trimmed"
    val url = candidate.toHttpUrlOrNull() ?: return null
    if (url.scheme !in SUPPORTED_SCHEMES) return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    if (url.query != null || url.fragment != null) return null

    var path = url.encodedPath.trimEnd('/')
    path = when {
        path.endsWith(CHAT_COMPLETIONS_PATH) -> path.removeSuffix(CHAT_COMPLETIONS_PATH)
        path.endsWith(MODELS_PATH) -> path.removeSuffix(MODELS_PATH)
        else -> path
    }
    if (!path.endsWith(API_VERSION_PATH)) path += API_VERSION_PATH
    return url.newBuilder()
        .encodedPath("$path/")
        .build()
        .toString()
}

internal fun aiApiChatCompletionsEndpoint(baseUrl: String): String =
    requireNotNull(baseUrl.toHttpUrlOrNull()?.resolve("chat/completions")) {
        "Некорректный base URL"
    }.toString()

internal fun aiModelsEndpoint(baseUrl: String): String =
    requireNotNull(baseUrl.toHttpUrlOrNull()?.resolve("models")) {
        "Некорректный base URL"
    }.toString()

private const val SCHEME_SEPARATOR = "://"
private val SUPPORTED_SCHEMES = setOf("http", "https")
private const val API_VERSION_PATH = "/v1"
private const val CHAT_COMPLETIONS_PATH = "/chat/completions"
private const val MODELS_PATH = "/models"
