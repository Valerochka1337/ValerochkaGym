package com.valerochka1337.valerochkagym.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Removes BYOK remnants after upgrade without ever reading their encrypted contents. */
@Singleton
class LegacyAiSecretCleanup @Inject constructor(@ApplicationContext private val context: Context) {
  suspend fun clear() =
      withContext(Dispatchers.IO) {
        listOf("ai_secrets", "openrouter_secrets").forEach { name ->
          File(context.filesDir, "datastore/$name.preferences_pb").delete()
          File(context.filesDir, "datastore/$name.preferences_pb.tmp").delete()
        }
        runCatching {
          KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(LEGACY_KEY_ALIAS)) deleteEntry(LEGACY_KEY_ALIAS)
          }
        }
      }

  private companion object {
    const val LEGACY_KEY_ALIAS = "valerochka_gym_ai_api_key"
  }
}
