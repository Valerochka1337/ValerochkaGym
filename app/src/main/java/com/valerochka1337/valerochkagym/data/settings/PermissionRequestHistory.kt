package com.valerochka1337.valerochkagym.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Durable per-permission request facts; grants and pending UI work are deliberately not stored. */
@Singleton
class PermissionRequestHistory @Inject constructor(private val dataStore: DataStore<Preferences>) {

  suspend fun wasRequested(permission: String): Boolean =
      dataStore.data.first()[booleanPreferencesKey(key(permission))] ?: false

  suspend fun markRequested(permissions: Collection<String>) {
    dataStore.edit { preferences ->
      permissions.forEach { permission ->
        preferences[booleanPreferencesKey(key(permission))] = true
      }
    }
  }

  private fun key(permission: String) = "permission_requested_$permission"
}
