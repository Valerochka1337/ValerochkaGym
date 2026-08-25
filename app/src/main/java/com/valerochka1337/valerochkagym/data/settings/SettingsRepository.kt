package com.valerochka1337.valerochkagym.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.ai.DEFAULT_OPEN_ROUTER_MODEL_ID
import com.valerochka1337.valerochkagym.data.ai.OpenRouterFreeModel
import com.valerochka1337.valerochkagym.data.ai.OpenRouterJsonMode
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
    /** Автостарт таймера отдыха после отметки подхода; выключен — отдых только не запускается сам. */
    val restAutostart: Boolean = true,
    /** Завершать автостартованный отдых по свежему пульсу вместо заданной длительности. */
    val heartRateRestEnabled: Boolean = false,
    /** Порог завершения отдыха по пульсу. */
    val heartRateRestThresholdBpm: Int = DEFAULT_HEART_RATE_REST_THRESHOLD_BPM,
    /** Сколько секунд пульс должен непрерывно держаться не выше порога. */
    val heartRateRestHoldSeconds: Int = DEFAULT_HEART_RATE_REST_HOLD_SECONDS,
    /** Free-модель OpenRouter для генерации упражнений и распознавания фото InBody. */
    val openRouterModelId: String = DEFAULT_OPEN_ROUTER_MODEL_ID,
    /** Совместимый с выбранной моделью способ запросить JSON. */
    val openRouterModelJsonMode: OpenRouterJsonMode = OpenRouterJsonMode.JSON_SCHEMA,
    /** Подходящий модели уровень reasoning; null сохраняет стандартное поведение OpenRouter. */
    val openRouterModelReasoningEffort: String? = null,
    val accent: AccentColor = AccentColor.DEFAULT,
) {
    companion object {
        const val DEFAULT_REST_SECONDS: Int = 120
        const val DEFAULT_HEART_RATE_REST_THRESHOLD_BPM: Int = 110
        const val DEFAULT_HEART_RATE_REST_HOLD_SECONDS: Int = 10
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
        val REST_AUTOSTART = booleanPreferencesKey("rest_autostart")
        val HEART_RATE_REST_ENABLED = booleanPreferencesKey("heart_rate_rest_enabled")
        val HEART_RATE_REST_THRESHOLD_BPM = intPreferencesKey("heart_rate_rest_threshold_bpm")
        val HEART_RATE_REST_HOLD_SECONDS = intPreferencesKey("heart_rate_rest_hold_seconds")
        val OPEN_ROUTER_MODEL_ID = stringPreferencesKey("openrouter_model_id")
        val OPEN_ROUTER_MODEL_JSON_MODE = stringPreferencesKey("openrouter_model_json_mode")
        val OPEN_ROUTER_MODEL_REASONING_EFFORT = stringPreferencesKey("openrouter_model_reasoning_effort")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
    }

    val settings: Flow<GymSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
        val openRouterModelId = prefs[Keys.OPEN_ROUTER_MODEL_ID]
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_OPEN_ROUTER_MODEL_ID
        GymSettings(
            googleEmail = prefs[Keys.GOOGLE_EMAIL],
            spreadsheetId = prefs[Keys.SPREADSHEET_ID],
            defaultRestSeconds = prefs[Keys.DEFAULT_REST_SECONDS] ?: GymSettings.DEFAULT_REST_SECONDS,
            soundEnabled = prefs[Keys.SOUND_ENABLED] ?: true,
            vibrationEnabled = prefs[Keys.VIBRATION_ENABLED] ?: true,
            hapticsEnabled = prefs[Keys.HAPTICS_ENABLED] ?: true,
            restAutostart = prefs[Keys.REST_AUTOSTART] ?: true,
            heartRateRestEnabled = prefs[Keys.HEART_RATE_REST_ENABLED] ?: false,
            heartRateRestThresholdBpm = (prefs[Keys.HEART_RATE_REST_THRESHOLD_BPM]
                ?: GymSettings.DEFAULT_HEART_RATE_REST_THRESHOLD_BPM).coerceIn(
                MIN_HEART_RATE_REST_THRESHOLD_BPM,
                MAX_HEART_RATE_REST_THRESHOLD_BPM,
            ),
            heartRateRestHoldSeconds = (prefs[Keys.HEART_RATE_REST_HOLD_SECONDS]
                ?: GymSettings.DEFAULT_HEART_RATE_REST_HOLD_SECONDS).coerceIn(
                MIN_HEART_RATE_REST_HOLD_SECONDS,
                MAX_HEART_RATE_REST_HOLD_SECONDS,
            ),
            openRouterModelId = openRouterModelId,
            openRouterModelJsonMode = OpenRouterJsonMode.fromStored(
                value = prefs[Keys.OPEN_ROUTER_MODEL_JSON_MODE],
                modelId = openRouterModelId,
            ),
            openRouterModelReasoningEffort = prefs[Keys.OPEN_ROUTER_MODEL_REASONING_EFFORT]
                ?.takeIf { it.isNotBlank() },
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

    suspend fun setRestAutostart(value: Boolean) = dataStore.edit { prefs ->
        prefs[Keys.REST_AUTOSTART] = value
    }

    suspend fun setHeartRateRestEnabled(value: Boolean) = dataStore.edit { prefs ->
        prefs[Keys.HEART_RATE_REST_ENABLED] = value
    }

    suspend fun setHeartRateRestThresholdBpm(value: Int) = dataStore.edit { prefs ->
        prefs[Keys.HEART_RATE_REST_THRESHOLD_BPM] = value.coerceIn(
            MIN_HEART_RATE_REST_THRESHOLD_BPM,
            MAX_HEART_RATE_REST_THRESHOLD_BPM,
        )
    }

    suspend fun setHeartRateRestHoldSeconds(value: Int) = dataStore.edit { prefs ->
        prefs[Keys.HEART_RATE_REST_HOLD_SECONDS] = value.coerceIn(
            MIN_HEART_RATE_REST_HOLD_SECONDS,
            MAX_HEART_RATE_REST_HOLD_SECONDS,
        )
    }

    suspend fun setOpenRouterModel(value: OpenRouterFreeModel) = dataStore.edit { prefs ->
        prefs[Keys.OPEN_ROUTER_MODEL_ID] = value.id
        prefs[Keys.OPEN_ROUTER_MODEL_JSON_MODE] = value.jsonMode.name
        if (value.reasoningEffort == null) {
            prefs.remove(Keys.OPEN_ROUTER_MODEL_REASONING_EFFORT)
        } else {
            prefs[Keys.OPEN_ROUTER_MODEL_REASONING_EFFORT] = value.reasoningEffort
        }
    }

    suspend fun setAccent(value: AccentColor) = dataStore.edit { prefs ->
        prefs[Keys.ACCENT_COLOR] = value.id
    }

    private companion object {
        const val MIN_HEART_RATE_REST_THRESHOLD_BPM = 40
        const val MAX_HEART_RATE_REST_THRESHOLD_BPM = 220
        const val MIN_HEART_RATE_REST_HOLD_SECONDS = 5
        const val MAX_HEART_RATE_REST_HOLD_SECONDS = 60
    }
}
