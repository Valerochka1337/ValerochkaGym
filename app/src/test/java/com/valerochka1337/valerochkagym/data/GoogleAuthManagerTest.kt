package com.valerochka1337.valerochkagym.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.google.GoogleAuthManager
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GoogleAuthManagerTest {
    private val manager = GoogleAuthManager(
        ApplicationProvider.getApplicationContext(),
        SettingsRepository(FakeDataStore()),
    )

    @Test
    fun `authorization request is bound to normalized expected account`() {
        val account = manager.buildAuthorizationRequest(" Owner@Example.COM ").account
        assertEquals("owner@example.com", account?.name)
        assertEquals("com.google", account?.type)
    }

    @Test
    fun `legacy token request has no forced account`() {
        assertNull(manager.buildAuthorizationRequest(null).account)
    }

    private class FakeDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(mutablePreferencesOf())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            state.value = transform(state.value)
            return state.value
        }
    }
}
