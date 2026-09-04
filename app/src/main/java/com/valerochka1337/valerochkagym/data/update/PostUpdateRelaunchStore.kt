package com.valerochka1337.valerochkagym.data.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Durable delivery state for the notification shown after this package is replaced. */
@Singleton
class PostUpdateRelaunchStore
@Inject
constructor(
    private val dataStore: DataStore<Preferences>,
) {

  suspend fun recordReplacement(versionCode: Long) {
    dataStore.edit { preferences ->
      val highestKnownVersion =
          maxOf(
              preferences[PENDING_VERSION] ?: Long.MIN_VALUE,
              preferences[DELIVERED_VERSION] ?: Long.MIN_VALUE,
          )
      if (versionCode > highestKnownVersion) {
        preferences[PENDING_VERSION] = versionCode
      }
    }
  }

  suspend fun pendingVersion(): Long? = dataStore.data.first()[PENDING_VERSION]

  /** Acknowledges only the exact pending version that was successfully handed to Android. */
  suspend fun markDelivered(versionCode: Long) {
    dataStore.edit { preferences ->
      if (preferences[PENDING_VERSION] == versionCode) {
        preferences[DELIVERED_VERSION] = versionCode
        preferences.remove(PENDING_VERSION)
      }
    }
  }

  suspend fun deliveredVersion(): Long? = dataStore.data.first()[DELIVERED_VERSION]

  private companion object {
    val PENDING_VERSION = longPreferencesKey("post_update_relaunch_pending_version")
    val DELIVERED_VERSION = longPreferencesKey("post_update_relaunch_delivered_version")
  }
}
