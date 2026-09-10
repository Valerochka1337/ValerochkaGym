package com.valerochka1337.valerochkagym.domain

import kotlinx.coroutines.flow.Flow

data class HealthConsentSnapshot(
    val localStorageAcknowledgedVersion: Int = 0,
    val backendSyncEnabled: Boolean = false,
    /**
     * Read-only reflection of AI-01 disclosure; mutate it only through
     * HealthAiDisclosureRepository.
     */
    val aiDisclosureEnabled: Boolean = false,
)

interface HealthConsentStore {
  fun observe(): Flow<HealthConsentSnapshot>

  suspend fun acknowledgeCurrentStorageNotice()

  suspend fun setBackendSyncEnabled(enabled: Boolean)
}
