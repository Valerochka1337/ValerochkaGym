package com.valerochka1337.valerochkagym.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import com.valerochka1337.valerochkagym.data.db.dao.MuscleLoadUpgradeNoticeDao

/** Durable one-shot gate; it never reads or writes workout rows. */
interface MuscleLoadUpgradeNotice {
    /** Claims a pending upgrade message without acknowledging it; cancellation may redeliver it. */
    suspend fun pendingIfNeeded(hasHistoricalWorkouts: Boolean): Boolean
    /** Called by the UI only after it has accepted the buffered message for rendering. */
    suspend fun acknowledge()
}

@Singleton
class DataStoreMuscleLoadUpgradeNotice @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : MuscleLoadUpgradeNotice {
    override suspend fun pendingIfNeeded(hasHistoricalWorkouts: Boolean): Boolean {
        var pending = false
        dataStore.edit { preferences ->
            if (preferences[INITIALIZED] != true) {
                // First install has no prior model to explain. An upgraded install already has
                // workout history when the analysis screen is first opened.
                preferences[INITIALIZED] = true
                if (hasHistoricalWorkouts) preferences[PENDING] = true
            }
            pending = preferences[PENDING] == true && preferences[ACKNOWLEDGED] != true
        }
        return pending
    }

    override suspend fun acknowledge() {
        dataStore.edit { preferences ->
            if (preferences[PENDING] == true) preferences[ACKNOWLEDGED] = true
        }
    }

    private companion object {
        val INITIALIZED = booleanPreferencesKey("muscle_load_v13_notice_initialized")
        val PENDING = booleanPreferencesKey("muscle_load_v13_notice_pending")
        val ACKNOWLEDGED = booleanPreferencesKey("muscle_load_v13_notice_acknowledged")
    }
}

object NoOpMuscleLoadUpgradeNotice : MuscleLoadUpgradeNotice {
    override suspend fun pendingIfNeeded(hasHistoricalWorkouts: Boolean): Boolean = false
    override suspend fun acknowledge() = Unit
}

@Singleton
class RoomMuscleLoadUpgradeNotice @Inject constructor(
    private val dao: MuscleLoadUpgradeNoticeDao,
) : MuscleLoadUpgradeNotice {
    override suspend fun pendingIfNeeded(hasHistoricalWorkouts: Boolean): Boolean {
        if (!dao.isPending()) return false
        if (!hasHistoricalWorkouts) {
            dao.acknowledge()
            return false
        }
        return true
    }
    override suspend fun acknowledge() = dao.acknowledge()
}
