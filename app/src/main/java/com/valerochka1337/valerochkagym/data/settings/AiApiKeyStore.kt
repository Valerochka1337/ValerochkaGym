package com.valerochka1337.valerochkagym.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.di.AiApiSecrets
import com.valerochka1337.valerochkagym.di.ComputeDispatcher
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Хранилище пользовательского API key: наружу отдаёт ключ только на время сетевого запроса. */
interface AiApiKeyStore {
  val isConfigured: Flow<Boolean>

  suspend fun save(value: String)

  suspend fun read(): String?

  /** Безопасное локальное представление для UI, например `sk-************1234`. */
  suspend fun preview(): String?

  suspend fun clear()
}

/** Изолированная криптография упрощает unit-тесты хранилища без Android Keystore. */
interface SecretCipher {
  fun encrypt(plainText: String): String

  fun decrypt(cipherText: String): String
}

/**
 * Хранит AES/GCM-шифротекст и безопасное превью с последними четырьмя символами в отдельном
 * DataStore. Если ключ Keystore утрачен, повреждённое значение и превью удаляются.
 */
@Singleton
class EncryptedAiApiKeyStore
@Inject
constructor(
    @param:AiApiSecrets private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    @param:ComputeDispatcher
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AiApiKeyStore {

  private object Keys {
    val AI_API_API_KEY = stringPreferencesKey("ai_api_key")
    val AI_API_KEY_PREVIEW = stringPreferencesKey("ai_api_key_preview")
  }

  override val isConfigured: Flow<Boolean> =
      dataStore.data.map { preferences -> !preferences[Keys.AI_API_API_KEY].isNullOrBlank() }

  override suspend fun save(value: String) {
    val key = value.trim()
    require(key.isNotEmpty()) { "API key не должен быть пустым" }
    val encrypted = withContext(computeDispatcher) { cipher.encrypt(key) }
    val preview = maskedAiApiKeyPreview(key)
    dataStore.edit { preferences ->
      preferences[Keys.AI_API_API_KEY] = encrypted
      preferences[Keys.AI_API_KEY_PREVIEW] = preview
    }
  }

  override suspend fun read(): String? {
    val encrypted = dataStore.data.first()[Keys.AI_API_API_KEY] ?: return null
    return decryptOrClear(encrypted)
  }

  override suspend fun preview(): String? {
    val preferences = dataStore.data.first()
    val encrypted = preferences[Keys.AI_API_API_KEY] ?: return null
    preferences[Keys.AI_API_KEY_PREVIEW]
        ?.takeIf { it.isNotBlank() }
        ?.let {
          return it
        }

    val key = decryptOrClear(encrypted) ?: return null
    val preview = maskedAiApiKeyPreview(key)
    dataStore.edit { current ->
      if (current[Keys.AI_API_API_KEY] == encrypted) {
        current[Keys.AI_API_KEY_PREVIEW] = preview
      }
    }
    return preview
  }

  override suspend fun clear() {
    dataStore.edit { preferences ->
      preferences.remove(Keys.AI_API_API_KEY)
      preferences.remove(Keys.AI_API_KEY_PREVIEW)
    }
  }

  private suspend fun decryptOrClear(encrypted: String): String? =
      try {
        withContext(computeDispatcher) { cipher.decrypt(encrypted).takeIf { it.isNotBlank() } }
      } catch (_: Exception) {
        // Ключ Android Keystore не переживает переустановку; не оставляем «сохранённый» мусор.
        clear()
        null
      }
}

internal fun maskedAiApiKeyPreview(key: String): String {
  val normalized = key.trim()
  val suffix = normalized.takeLast(API_KEY_PREVIEW_SUFFIX_LENGTH)
  return "$API_KEY_PREVIEW_PREFIX$API_KEY_PREVIEW_MASK$suffix"
}

private const val API_KEY_PREVIEW_PREFIX = "sk-"
private const val API_KEY_PREVIEW_MASK = "************"
private const val API_KEY_PREVIEW_SUFFIX_LENGTH = 4

/** AES/GCM-шифрование с неэкспортируемым ключом Android Keystore. */
@Singleton
class AndroidKeystoreSecretCipher @Inject constructor() : SecretCipher {

  override fun encrypt(plainText: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey())
    val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    val encrypted =
        Base64.encodeToString(cipher.doFinal(plainText.encodeToByteArray()), Base64.NO_WRAP)
    return "$iv:$encrypted"
  }

  override fun decrypt(cipherText: String): String {
    val (ivPart, encryptedPart) = cipherText.split(DELIMITER, limit = 2)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(
        Cipher.DECRYPT_MODE,
        secretKey(),
        GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(ivPart, Base64.NO_WRAP)),
    )
    return cipher.doFinal(Base64.decode(encryptedPart, Base64.NO_WRAP)).decodeToString()
  }

  @Synchronized
  private fun secretKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    if (existing != null) return existing

    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        .apply {
          init(
              KeyGenParameterSpec.Builder(
                      KEY_ALIAS,
                      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                  )
                  .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                  .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                  .build(),
          )
        }
        .generateKey()
  }

  private companion object {
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "valerochka_gym_ai_api_key"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val DELIMITER = ":"
    const val GCM_TAG_LENGTH_BITS = 128
  }
}
