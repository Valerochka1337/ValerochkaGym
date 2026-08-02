package com.valerochka1337.valerochkagym.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.ui.theme.AccentColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class GymSettings(
    val googleEmail: String? = null,
    val spreadsheetId: String? = null,
    val defaultRestSeconds: Int = DEFAULT_REST_SECONDS,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    /** Тактильный отклик интерфейса (гейтит [com.valerochka1337.valerochkagym.ui.haptics.GymHaptics]). */
    val hapticsEnabled: Boolean = true,
    val accent: AccentColor = AccentColor.DEFAULT,
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
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
    }

    val settings: Flow<GymSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
        GymSettings(
            googleEmail = prefs[Keys.GOOGLE_EMAIL],
            spreadsheetId = prefs[Keys.SPREADSHEET_ID],
            defaultRestSeconds = prefs[Keys.DEFAULT_REST_SECONDS] ?: GymSettings.DEFAULT_REST_SECONDS,
            soundEnabled = prefs[Keys.SOUND_ENABLED] ?: true,
            vibrationEnabled = prefs[Keys.VIBRATION_ENABLED] ?: true,
            hapticsEnabled = prefs[Keys.HAPTICS_ENABLED] ?: true,
            accent = AccentColor.fromId(prefs[Keys.ACCENT_COLOR]),
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

    suspend fun setHapticsEnabled(value: Boolean) = dataStore.edit { prefs ->
        prefs[Keys.HAPTICS_ENABLED] = value
    }

    suspend fun setAccent(value: AccentColor) = dataStore.edit { prefs ->
        prefs[Keys.ACCENT_COLOR] = value.id
    }
}
