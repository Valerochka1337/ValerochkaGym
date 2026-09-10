package com.valerochka1337.valerochkagym.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRequestHistoryTest {

  @Test
  fun `history records each requested permission independently`() = runTest {
    val history = PermissionRequestHistory(FakeDataStore())

    history.markRequested(listOf("notification"))

    assertTrue(history.wasRequested("notification"))
    assertFalse(history.wasRequested("bluetooth"))
  }

  private class FakeDataStore : DataStore<Preferences> {
    private val values = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> = values

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
      return transform(values.value).also { values.value = it }
    }
  }
}
