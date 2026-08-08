package com.valerochka1337.valerochkagym.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.di.OpenRouterSecrets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Хранилище пользовательского API key: наружу отдаёт ключ только на время запроса к OpenRouter. */
interface OpenRouterKeyStore {
    val isConfigured: Flow<Boolean>

    suspend fun save(value: String)

    suspend fun read(): String?

    suspend fun clear()
}

/** Изолированная криптография упрощает unit-тесты хранилища без Android Keystore. */
interface SecretCipher {
    fun encrypt(plainText: String): String

    fun decrypt(cipherText: String): String
}

/**
 * Хранит только AES/GCM-шифротекст в отдельном DataStore. Если ключ Keystore утрачен (например,
 * после переустановки), повреждённое значение удаляется и пользователь вводит key заново.
 */
@Singleton
class EncryptedOpenRouterKeyStore @Inject constructor(
    @param:OpenRouterSecrets private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) : OpenRouterKeyStore {

    private object Keys {
        val OPEN_ROUTER_API_KEY = stringPreferencesKey("openrouter_api_key")
    }

    override val isConfigured: Flow<Boolean> = dataStore.data
        .map { preferences -> !preferences[Keys.OPEN_ROUTER_API_KEY].isNullOrBlank() }

    override suspend fun save(value: String) {
        val key = value.trim()
        require(key.isNotEmpty()) { "OpenRouter API key не должен быть пустым" }
        val encrypted = cipher.encrypt(key)
        dataStore.edit { preferences -> preferences[Keys.OPEN_ROUTER_API_KEY] = encrypted }
    }

    override suspend fun read(): String? {
        val encrypted = dataStore.data.first()[Keys.OPEN_ROUTER_API_KEY] ?: return null
        return try {
            cipher.decrypt(encrypted).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            // Ключ Android Keystore не переживает переустановку; не оставляем «сохранённый» мусор.
            clear()
            null
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences -> preferences.remove(Keys.OPEN_ROUTER_API_KEY) }
    }
}

/** AES/GCM-шифрование с неэкспортируемым ключом Android Keystore. */
@Singleton
class AndroidKeystoreSecretCipher @Inject constructor() : SecretCipher {

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(plainText.encodeToByteArray()), Base64.NO_WRAP)
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
        const val KEY_ALIAS = "valerochka_gym_openrouter_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DELIMITER = ":"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
