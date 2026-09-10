package com.valerochka1337.valerochkagym.data.health

import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.HealthConsentSnapshot
import com.valerochka1337.valerochkagym.domain.HealthConsentStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class HealthConsentStoreImpl @Inject constructor(private val settings: SettingsRepository) :
    HealthConsentStore {
  override fun observe(): Flow<HealthConsentSnapshot> =
      settings.settings.map {
        HealthConsentSnapshot(
            localStorageAcknowledgedVersion = it.localHealthStorageAcknowledgedVersion,
            backendSyncEnabled = it.healthBackendSyncEnabled,
            aiDisclosureEnabled = it.healthAiDisclosureEnabled,
        )
      }

  override suspend fun acknowledgeCurrentStorageNotice() {
    settings.setLocalHealthStorageAcknowledgedVersion(CURRENT_NOTICE_VERSION)
  }

  override suspend fun setBackendSyncEnabled(enabled: Boolean) {
    settings.setHealthBackendSyncEnabled(enabled)
  }

  companion object {
    const val CURRENT_NOTICE_VERSION = 1
  }
}
