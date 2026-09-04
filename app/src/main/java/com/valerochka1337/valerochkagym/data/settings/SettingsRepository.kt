package com.valerochka1337.valerochkagym.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.ai.AiModel
import com.valerochka1337.valerochkagym.ui.theme.AccentColor
import com.valerochka1337.valerochkagym.ui.theme.PaletteMode
import com.valerochka1337.valerochkagym.ui.theme.ThemeMode
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

data class GymSettings(
    val googleEmail: String? = null,
    val spreadsheetId: String? = null,
    val defaultRestSeconds: Int = DEFAULT_REST_SECONDS,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    /**
     * Тактильный отклик интерфейса (гейтит
     * [com.valerochka1337.valerochkagym.ui.haptics.GymHaptics]).
     */
    val hapticsEnabled: Boolean = true,
    /**
     * Автостарт таймера отдыха после отметки подхода; выключен — отдых только не запускается сам.
     */
    val restAutostart: Boolean = true,
    /** Завершать автостартованный отдых по свежему пульсу вместо заданной длительности. */
    val heartRateRestEnabled: Boolean = false,
    /** Порог завершения отдыха по пульсу. */
    val heartRateRestThresholdBpm: Int = DEFAULT_HEART_RATE_REST_THRESHOLD_BPM,
    /** Сколько секунд пульс должен непрерывно держаться не выше порога. */
    val heartRateRestHoldSeconds: Int = DEFAULT_HEART_RATE_REST_HOLD_SECONDS,
    /** HTTP(S) base URL пользовательского OpenAI-совместимого сервера с завершающим `/`. */
    val aiBaseUrl: String? = null,
    /** Модель, общая для генерации упражнений и распознавания фото InBody. */
    val aiModelId: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val paletteMode: PaletteMode = PaletteMode.SYSTEM,
    val accent: AccentColor = AccentColor.DEFAULT,
    /** GitHub Release, для которого пользователь выбрал «Не напоминать». */
    val ignoredUpdateTag: String? = null,
) {
  companion object {
    const val DEFAULT_REST_SECONDS: Int = 120
    const val DEFAULT_HEART_RATE_REST_THRESHOLD_BPM: Int = 110
    const val DEFAULT_HEART_RATE_REST_HOLD_SECONDS: Int = 10
  }
}

@Singleton
class SettingsRepository
@Inject
constructor(
    private val dataStore: DataStore<Preferences>,
) {

  private object Keys {
    val GOOGLE_EMAIL = stringPreferencesKey("google_email")
    val SPREADSHEET_ID = stringPreferencesKey("spreadsheet_id")
    val DEFAULT_REST_SECONDS = intPreferencesKey("default_rest_seconds")
    val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
    val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
    val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
    val REST_AUTOSTART = booleanPreferencesKey("rest_autostart")
    val HEART_RATE_REST_ENABLED = booleanPreferencesKey("heart_rate_rest_enabled")
    val HEART_RATE_REST_THRESHOLD_BPM = intPreferencesKey("heart_rate_rest_threshold_bpm")
    val HEART_RATE_REST_HOLD_SECONDS = intPreferencesKey("heart_rate_rest_hold_seconds")
    val AI_BASE_URL = stringPreferencesKey("ai_base_url")
    val AI_MODEL_ID = stringPreferencesKey("ai_model_id")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val PALETTE_MODE = stringPreferencesKey("palette_mode")
    val ACCENT_COLOR = stringPreferencesKey("accent_color")
    val IGNORED_UPDATE_TAG = stringPreferencesKey("ignored_update_tag")
  }

  val settings: Flow<GymSettings> =
      dataStore.data
          .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
          .map { prefs ->
            GymSettings(
                googleEmail = prefs[Keys.GOOGLE_EMAIL],
                spreadsheetId = prefs[Keys.SPREADSHEET_ID],
                defaultRestSeconds =
                    prefs[Keys.DEFAULT_REST_SECONDS] ?: GymSettings.DEFAULT_REST_SECONDS,
                soundEnabled = prefs[Keys.SOUND_ENABLED] ?: true,
                vibrationEnabled = prefs[Keys.VIBRATION_ENABLED] ?: true,
                hapticsEnabled = prefs[Keys.HAPTICS_ENABLED] ?: true,
                restAutostart = prefs[Keys.REST_AUTOSTART] ?: true,
                heartRateRestEnabled = prefs[Keys.HEART_RATE_REST_ENABLED] ?: false,
                heartRateRestThresholdBpm =
                    (prefs[Keys.HEART_RATE_REST_THRESHOLD_BPM]
                            ?: GymSettings.DEFAULT_HEART_RATE_REST_THRESHOLD_BPM)
                        .coerceIn(
                            MIN_HEART_RATE_REST_THRESHOLD_BPM,
                            MAX_HEART_RATE_REST_THRESHOLD_BPM,
                        ),
                heartRateRestHoldSeconds =
                    (prefs[Keys.HEART_RATE_REST_HOLD_SECONDS]
                            ?: GymSettings.DEFAULT_HEART_RATE_REST_HOLD_SECONDS)
                        .coerceIn(
                            MIN_HEART_RATE_REST_HOLD_SECONDS,
                            MAX_HEART_RATE_REST_HOLD_SECONDS,
                        ),
                aiBaseUrl = prefs[Keys.AI_BASE_URL]?.takeIf { it.isNotBlank() },
                aiModelId = prefs[Keys.AI_MODEL_ID]?.takeIf { it.isNotBlank() },
                themeMode = ThemeMode.fromId(prefs[Keys.THEME_MODE]),
                paletteMode = PaletteMode.fromId(prefs[Keys.PALETTE_MODE]),
                accent = AccentColor.fromId(prefs[Keys.ACCENT_COLOR]),
                ignoredUpdateTag = prefs[Keys.IGNORED_UPDATE_TAG],
            )
          }

  suspend fun setGoogleEmail(value: String?) =
      dataStore.edit { prefs ->
        if (value == null) prefs.remove(Keys.GOOGLE_EMAIL) else prefs[Keys.GOOGLE_EMAIL] = value
      }

  suspend fun setSpreadsheetId(value: String?) =
      dataStore.edit { prefs ->
        if (value == null) prefs.remove(Keys.SPREADSHEET_ID) else prefs[Keys.SPREADSHEET_ID] = value
      }

  suspend fun setDefaultRestSeconds(value: Int) =
      dataStore.edit { prefs -> prefs[Keys.DEFAULT_REST_SECONDS] = value }

  suspend fun setSoundEnabled(value: Boolean) =
      dataStore.edit { prefs -> prefs[Keys.SOUND_ENABLED] = value }

  suspend fun setVibrationEnabled(value: Boolean) =
      dataStore.edit { prefs -> prefs[Keys.VIBRATION_ENABLED] = value }

  suspend fun setHapticsEnabled(value: Boolean) =
      dataStore.edit { prefs -> prefs[Keys.HAPTICS_ENABLED] = value }

  suspend fun setRestAutostart(value: Boolean) =
      dataStore.edit { prefs -> prefs[Keys.REST_AUTOSTART] = value }

  suspend fun setHeartRateRestEnabled(value: Boolean) =
      dataStore.edit { prefs -> prefs[Keys.HEART_RATE_REST_ENABLED] = value }

  suspend fun setHeartRateRestThresholdBpm(value: Int) =
      dataStore.edit { prefs ->
        prefs[Keys.HEART_RATE_REST_THRESHOLD_BPM] =
            value.coerceIn(
                MIN_HEART_RATE_REST_THRESHOLD_BPM,
                MAX_HEART_RATE_REST_THRESHOLD_BPM,
            )
      }

  suspend fun setHeartRateRestHoldSeconds(value: Int) =
      dataStore.edit { prefs ->
        prefs[Keys.HEART_RATE_REST_HOLD_SECONDS] =
            value.coerceIn(
                MIN_HEART_RATE_REST_HOLD_SECONDS,
                MAX_HEART_RATE_REST_HOLD_SECONDS,
            )
      }

  suspend fun setAiBaseUrl(value: String) =
      dataStore.edit { prefs ->
        require(value.isNotBlank()) { "Base URL не должен быть пустым" }
        val previous = prefs[Keys.AI_BASE_URL]
        prefs[Keys.AI_BASE_URL] = value
        if (previous != value) prefs.remove(Keys.AI_MODEL_ID)
      }

  suspend fun setAiModel(value: AiModel) =
      dataStore.edit { prefs ->
        require(value.id.isNotBlank()) { "ID модели не должен быть пустым" }
        prefs[Keys.AI_MODEL_ID] = value.id.trim()
      }

  suspend fun setAccent(value: AccentColor) =
      dataStore.edit { prefs -> prefs[Keys.ACCENT_COLOR] = value.id }

  suspend fun setThemeMode(value: ThemeMode) =
      dataStore.edit { prefs -> prefs[Keys.THEME_MODE] = value.id }

  suspend fun setPaletteMode(value: PaletteMode) =
      dataStore.edit { prefs ->
        prefs[Keys.PALETTE_MODE] = value.id
        value.accent?.let { prefs[Keys.ACCENT_COLOR] = it.id }
      }

  suspend fun setIgnoredUpdateTag(value: String?) =
      dataStore.edit { prefs ->
        if (value == null) {
          prefs.remove(Keys.IGNORED_UPDATE_TAG)
        } else {
          prefs[Keys.IGNORED_UPDATE_TAG] = value
        }
      }

  private companion object {
    const val MIN_HEART_RATE_REST_THRESHOLD_BPM = 40
    const val MAX_HEART_RATE_REST_THRESHOLD_BPM = 220
    const val MIN_HEART_RATE_REST_HOLD_SECONDS = 5
    const val MAX_HEART_RATE_REST_HOLD_SECONDS = 60
  }
}
