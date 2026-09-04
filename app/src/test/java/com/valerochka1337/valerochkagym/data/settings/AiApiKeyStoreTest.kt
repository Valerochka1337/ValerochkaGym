package com.valerochka1337.valerochkagym.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiApiKeyStoreTest {

  @Test
  fun `saving a key stores ciphertext and a masked preview`() = runTest {
    val dataStore = FakeDataStore(emptyPreferences())
    val store = EncryptedAiApiKeyStore(dataStore, FakeCipher())

    store.save("  sk-ai-secret  ")

    assertTrue(store.isConfigured.first())
    assertEquals("sk-ai-secret", store.read())
    assertEquals("sk-************cret", store.preview())
    assertEquals(
        "encrypted:sk-ai-secret",
        dataStore.data.first()[stringPreferencesKey("ai_api_key")],
    )
    assertEquals(
        "sk-************cret",
        dataStore.data.first()[stringPreferencesKey("ai_api_key_preview")],
    )
  }

  @Test
  fun `clearing a key removes it from the secret store`() = runTest {
    val store = EncryptedAiApiKeyStore(FakeDataStore(emptyPreferences()), FakeCipher())
    store.save("key")

    store.clear()

    assertFalse(store.isConfigured.first())
    assertNull(store.read())
    assertNull(store.preview())
  }

  @Test
  fun `preview backfills the suffix for a key saved before previews existed`() = runTest {
    val dataStore =
        FakeDataStore(
            androidx.datastore.preferences.core.mutablePreferencesOf(
                stringPreferencesKey("ai_api_key") to "encrypted:sk-legacy-key-1234",
            ),
        )
    val store = EncryptedAiApiKeyStore(dataStore, FakeCipher())

    assertEquals("sk-************1234", store.preview())
    assertEquals(
        "sk-************1234",
        dataStore.data.first()[stringPreferencesKey("ai_api_key_preview")],
    )
  }

  @Test
  fun `an unreadable ciphertext is removed instead of being treated as a key`() = runTest {
    val dataStore =
        FakeDataStore(
            androidx.datastore.preferences.core.mutablePreferencesOf(
                stringPreferencesKey("ai_api_key") to "broken",
            ),
        )
    val store =
        EncryptedAiApiKeyStore(
            dataStore,
            object : SecretCipher {
              override fun encrypt(plainText: String): String = plainText

              override fun decrypt(cipherText: String): String = error("cannot decrypt")
            },
        )

    assertNull(store.read())
    assertFalse(store.isConfigured.first())
  }

  private class FakeCipher : SecretCipher {
    override fun encrypt(plainText: String): String = "encrypted:$plainText"

    override fun decrypt(cipherText: String): String = cipherText.removePrefix("encrypted:")
  }

  private class FakeDataStore(initial: Preferences) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences = transform(state.value).also { state.value = it }
  }
}
