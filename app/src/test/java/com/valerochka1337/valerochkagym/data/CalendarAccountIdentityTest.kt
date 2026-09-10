package com.valerochka1337.valerochkagym.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarAccountIdentityTest {
  @Test
  fun `legacy email seeds only preferred while connected remains empty`() = runTest {
    val store =
        FakeDataStore(
            mutablePreferencesOf(stringPreferencesKey("google_email") to " User@Example.COM ")
        )
    val identity = SettingsRepository(store)

    assertEquals("user@example.com", identity.preferredCalendarEmail.first())
    assertNull(identity.connectedCalendarEmail.first())
  }

  @Test
  fun `verified connection is normalized and exact stale clear cannot remove it`() = runTest {
    val identity = SettingsRepository(FakeDataStore())
    identity.setConnectedCalendarEmail(" User@Example.COM ")

    assertFalse(identity.clearConnectedCalendarEmail("other@example.com"))
    assertEquals("user@example.com", identity.connectedCalendarEmail.first())
    assertTrue(identity.clearConnectedCalendarEmail("USER@example.com"))
    assertNull(identity.connectedCalendarEmail.first())
  }

  @Test
  fun `conditional verified connection writes only when its final gate remains current`() =
      runTest {
        val identity = SettingsRepository(FakeDataStore())

        assertFalse(identity.commitConnectedCalendarEmail("a@example.com") { false })
        assertNull(identity.connectedCalendarEmail.first())
        assertTrue(identity.commitConnectedCalendarEmail(" A@Example.COM ") { true })
        assertEquals("a@example.com", identity.connectedCalendarEmail.first())
      }

  private class FakeDataStore(initial: Preferences = mutablePreferencesOf()) :
      DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
  }
}
