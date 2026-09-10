package com.valerochka1337.valerochkagym.data.health

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HealthConsentStoreTest {
  @Test
  fun `storage acknowledgement and sync choice survive recreation without changing ai disclosure`() =
      runTest {
        val data = Store()
        val settings = SettingsRepository(data)
        settings.setHealthAiDisclosureEnabled(true)
        val consent = HealthConsentStoreImpl(settings)
        consent.acknowledgeCurrentStorageNotice()
        assertFalse(consent.observe().first().backendSyncEnabled)
        consent.setBackendSyncEnabled(true)
        val restored = HealthConsentStoreImpl(SettingsRepository(data)).observe().first()
        assertEquals(
            HealthConsentStoreImpl.CURRENT_NOTICE_VERSION,
            restored.localStorageAcknowledgedVersion,
        )
        assertTrue(restored.backendSyncEnabled)
        assertTrue(restored.aiDisclosureEnabled)
        consent.setBackendSyncEnabled(false)
        assertEquals(
            restored.localStorageAcknowledgedVersion,
            consent.observe().first().localStorageAcknowledgedVersion,
        )
        assertTrue(consent.observe().first().aiDisclosureEnabled)
      }

  @Test
  fun `acknowledging an older notice never lowers historical acknowledgement`() = runTest {
    val settings = SettingsRepository(Store())
    settings.setLocalHealthStorageAcknowledgedVersion(
        HealthConsentStoreImpl.CURRENT_NOTICE_VERSION + 1
    )
    HealthConsentStoreImpl(settings).acknowledgeCurrentStorageNotice()
    val state = HealthConsentStoreImpl(settings).observe().first()
    assertEquals(
        HealthConsentStoreImpl.CURRENT_NOTICE_VERSION + 1,
        state.localStorageAcknowledgedVersion,
    )
    assertFalse(state.backendSyncEnabled)
    assertFalse(state.aiDisclosureEnabled)
  }
}

private class Store : DataStore<Preferences> {
  override val data = MutableStateFlow<Preferences>(emptyPreferences())

  override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
      transform(data.value).also { data.value = it }
}
