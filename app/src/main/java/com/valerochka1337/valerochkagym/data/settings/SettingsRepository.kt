package com.valerochka1337.valerochkagym.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class GymSettings(
    val googleEmail: String? = null,
    val spreadsheetId: String? = null,
    val defaultRestSeconds: Int = DEFAULT_REST_SECONDS,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_REST_SECONDS: Int = 120
    }
}

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private object Keys {
        val GOOGLE_EMAIL = stringPreferencesKey("google_email")
        val SPREADSHEET_ID = stringPreferencesKey("spreadsheet_id")
        val DEFAULT_REST_SECONDS = intPreferencesKey("default_rest_seconds")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
    }

    val settings: Flow<GymSettings> = dataStore.data.map { prefs ->
        GymSettings(
            googleEmail = prefs[Keys.GOOGLE_EMAIL],
            spreadsheetId = prefs[Keys.SPREADSHEET_ID],
            defaultRestSeconds = prefs[Keys.DEFAULT_REST_SECONDS] ?: GymSettings.DEFAULT_REST_SECONDS,
            soundEnabled = prefs[Keys.SOUND_ENABLED] ?: true,
            vibrationEnabled = prefs[Keys.VIBRATION_ENABLED] ?: true,
        )
    }

    suspend fun setGoogleEmail(value: String?) = dataStore.edit { prefs ->
        if (value == null) prefs.remove(Keys.GOOGLE_EMAIL) else prefs[Keys.GOOGLE_EMAIL] = value
    }

    suspend fun setSpreadsheetId(value: String?) = dataStore.edit { prefs ->
        if (value == null) prefs.remove(Keys.SPREADSHEET_ID) else prefs[Keys.SPREADSHEET_ID] = value
    }

    suspend fun setDefaultRestSeconds(value: Int) = dataStore.edit { prefs ->
        prefs[Keys.DEFAULT_REST_SECONDS] = value
    }

    suspend fun setSoundEnabled(value: Boolean) = dataStore.edit { prefs ->
        prefs[Keys.SOUND_ENABLED] = value
    }

    suspend fun setVibrationEnabled(value: Boolean) = dataStore.edit { prefs ->
        prefs[Keys.VIBRATION_ENABLED] = value
    }
}
