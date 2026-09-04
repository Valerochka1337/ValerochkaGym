package com.valerochka1337.valerochkagym.data.update

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.MainActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PostUpdateRelaunchTest {

  @Test
  fun `recording a replacement keeps only a newer pending version across recreated stores`() =
      runTest {
        val dataStore = FakeDataStore(emptyPreferences())
        val firstStore = PostUpdateRelaunchStore(dataStore)

        firstStore.recordReplacement(22L)
        firstStore.markDelivered(22L)

        val recreatedStore = PostUpdateRelaunchStore(dataStore)
        recreatedStore.recordReplacement(21L)
        recreatedStore.recordReplacement(22L)
        recreatedStore.recordReplacement(23L)

        assertEquals(23L, recreatedStore.pendingVersion())
        assertEquals(22L, recreatedStore.deliveredVersion())
      }

  @Test
  fun `a delivered replacement posts one fixed notification with a safe app intent`() = runTest {
    val publisher = FakeNotificationPublisher()
    val coordinator = coordinator(FakeDataStore(emptyPreferences()), publisher)

    coordinator.recordReplacement(22L)
    coordinator.reconcilePending()

    assertEquals(1, publisher.notifications.size)
    assertEquals(
        PostUpdateRelaunchCoordinator.NOTIFICATION_ID,
        publisher.notifications.single().first,
    )
    val notification = publisher.notifications.single().second
    assertEquals(
        "Обновление установлено",
        notification.extras.getCharSequence(Notification.EXTRA_TITLE),
    )
    assertEquals(
        "Нажмите, чтобы открыть ValerochkaGym",
        notification.extras.getCharSequence(Notification.EXTRA_TEXT),
    )
    val contentIntent = requireNotNull(notification.contentIntent)
    assertTrue(contentIntent.isImmutable)
    val intent = shadowOf(contentIntent).savedIntent
    assertEquals(MainActivity::class.java.name, intent.component?.className)
    assertEquals(Intent.ACTION_MAIN, intent.action)
    assertTrue(intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
  }

  @Test
  fun `disabled notifications retain the pending marker without opening the app`() = runTest {
    val dataStore = FakeDataStore(emptyPreferences())
    val publisher = FakeNotificationPublisher(appNotificationsEnabled = false)
    val coordinator = coordinator(dataStore, publisher)

    coordinator.recordReplacement(22L)

    assertEquals(22L, PostUpdateRelaunchStore(dataStore).pendingVersion())
    assertNull(PostUpdateRelaunchStore(dataStore).deliveredVersion())
    assertTrue(publisher.notifications.isEmpty())
  }

  @Test
  fun `a blocked channel remains pending until reenabled reconciliation posts it`() = runTest {
    val dataStore = FakeDataStore(emptyPreferences())
    val publisher =
        FakeNotificationPublisher(
            channelImportance = NotificationManager.IMPORTANCE_NONE,
        )
    val firstCoordinator = coordinator(dataStore, publisher)

    firstCoordinator.recordReplacement(22L)

    assertEquals(22L, PostUpdateRelaunchStore(dataStore).pendingVersion())
    assertTrue(publisher.notifications.isEmpty())

    publisher.channelImportance = NotificationManager.IMPORTANCE_DEFAULT
    coordinator(dataStore, publisher).reconcilePending()

    assertEquals(1, publisher.notifications.size)
    assertNull(PostUpdateRelaunchStore(dataStore).pendingVersion())
    assertEquals(22L, PostUpdateRelaunchStore(dataStore).deliveredVersion())
  }

  @Test
  fun `a security failure retains the pending marker for later reconciliation`() = runTest {
    val dataStore = FakeDataStore(emptyPreferences())
    val publisher = FakeNotificationPublisher(throwsSecurityException = true)

    coordinator(dataStore, publisher).recordReplacement(22L)

    assertEquals(22L, PostUpdateRelaunchStore(dataStore).pendingVersion())
    assertFalse(publisher.notifications.isNotEmpty())
  }

  @Test
  fun `only the package replacement action is accepted by the receiver boundary`() {
    assertTrue(Intent(Intent.ACTION_MY_PACKAGE_REPLACED).isMyPackageReplacement())
    assertFalse(
        Intent(AppUpdateInstallStatusReceiver.ACTION_INSTALL_STATUS).isMyPackageReplacement()
    )
    assertFalse(Intent("unrelated.action").isMyPackageReplacement())
  }

  private fun coordinator(
      dataStore: DataStore<Preferences>,
      publisher: PostUpdateNotificationPublisher,
  ): PostUpdateRelaunchCoordinator =
      PostUpdateRelaunchCoordinator(
          ApplicationProvider.getApplicationContext<Context>(),
          PostUpdateRelaunchStore(dataStore),
          publisher,
      )

  private class FakeNotificationPublisher(
      var appNotificationsEnabled: Boolean = true,
      var channelImportance: Int = NotificationManager.IMPORTANCE_DEFAULT,
      private val throwsSecurityException: Boolean = false,
  ) : PostUpdateNotificationPublisher {
    val notifications = mutableListOf<Pair<Int, Notification>>()

    override fun isDeliveryAvailable(): Boolean =
        appNotificationsEnabled && channelImportance != NotificationManager.IMPORTANCE_NONE

    override fun post(notificationId: Int, notification: Notification): Boolean {
      if (throwsSecurityException) throw SecurityException("Notifications disabled")
      notifications += notificationId to notification
      return true
    }
  }

  private class FakeDataStore(initial: Preferences) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = transform(state.value).also { state.value = it }
  }
}
