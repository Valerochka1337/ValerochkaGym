package com.valerochka1337.valerochkagym.service

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CoachAlertNotifierTest {
  private val context = ApplicationProvider.getApplicationContext<Application>()
  private val manager = context.getSystemService(NotificationManager::class.java)

  @Test
  fun `visible activities suppress alerts and background restores delivery`() {
    val notifier = CoachAlertNotifier(context)
    val first = Any()
    val second = Any()
    notifier.activityStarted(first)
    notifier.activityStarted(second)
    notifier.activityStopped(first)
    notifier.show("w")
    assertTrue(manager.activeNotifications.isEmpty())
    notifier.activityStopped(second)
    notifier.show("w")
    assertEquals("w", manager.activeNotifications.single().tag)
  }

  @Test
  fun `opening a chat clears its alert after process recreation without clearing other chats`() {
    CoachAlertNotifier(context).apply {
      show("first")
      show("second")
    }
    CoachAlertNotifier(context).chatViewed("first")
    assertEquals("second", manager.activeNotifications.single().tag)
  }
}
