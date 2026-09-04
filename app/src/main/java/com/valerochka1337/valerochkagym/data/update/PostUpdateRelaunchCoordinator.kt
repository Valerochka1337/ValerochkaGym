package com.valerochka1337.valerochkagym.data.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.valerochka1337.valerochkagym.MainActivity
import com.valerochka1337.valerochkagym.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Coordinates durable post-update state with Android's user-tappable notification surface. */
@Singleton
class PostUpdateRelaunchCoordinator
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val store: PostUpdateRelaunchStore,
    private val notificationPublisher: PostUpdateNotificationPublisher,
) {

  suspend fun recordReplacement(versionCode: Long) {
    store.recordReplacement(versionCode)
    reconcilePending()
  }

  suspend fun reconcilePending() {
    val versionCode = store.pendingVersion() ?: return
    val deliveryAvailable =
        try {
          notificationPublisher.isDeliveryAvailable()
        } catch (_: SecurityException) {
          false
        }
    if (!deliveryAvailable) return

    val posted =
        try {
          notificationPublisher.post(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
          false
        }
    if (posted) store.markDelivered(versionCode)
  }

  private fun buildNotification(): Notification =
      Notification.Builder(context, CHANNEL_ID)
          .setSmallIcon(R.drawable.ic_notification_gym)
          .setContentTitle("Обновление установлено")
          .setContentText("Нажмите, чтобы открыть ValerochkaGym")
          .setContentIntent(openAppPendingIntent())
          .setAutoCancel(true)
          .build()

  private fun openAppPendingIntent(): PendingIntent {
    val intent =
        Intent(context, MainActivity::class.java).apply {
          action = Intent.ACTION_MAIN
          addCategory(Intent.CATEGORY_LAUNCHER)
          flags =
              Intent.FLAG_ACTIVITY_NEW_TASK or
                  Intent.FLAG_ACTIVITY_CLEAR_TOP or
                  Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    return PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  companion object {
    const val CHANNEL_ID = "post_update_relaunch"
    const val NOTIFICATION_ID = 4102
    private const val REQUEST_OPEN_APP = 4102
  }
}

/** Android boundary kept injectable so recovery behavior has deterministic handwritten fakes. */
interface PostUpdateNotificationPublisher {
  fun isDeliveryAvailable(): Boolean

  fun post(notificationId: Int, notification: Notification): Boolean
}

@Singleton
class AndroidPostUpdateNotificationPublisher
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
) : PostUpdateNotificationPublisher {

  override fun isDeliveryAvailable(): Boolean {
    val notificationManager =
        context.getSystemService(NotificationManager::class.java) ?: return false
    notificationManager.createNotificationChannel(
        NotificationChannel(
            PostUpdateRelaunchCoordinator.CHANNEL_ID,
            "Открыть приложение после обновления",
            NotificationManager.IMPORTANCE_DEFAULT,
        ),
    )
    return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED &&
        notificationManager.areNotificationsEnabled() &&
        notificationManager
            .getNotificationChannel(PostUpdateRelaunchCoordinator.CHANNEL_ID)
            ?.importance
            ?.let { it != NotificationManager.IMPORTANCE_NONE } == true
  }

  override fun post(notificationId: Int, notification: Notification): Boolean {
    val notificationManager =
        context.getSystemService(NotificationManager::class.java) ?: return false
    return try {
      notificationManager.notify(notificationId, notification)
      true
    } catch (_: SecurityException) {
      false
    }
  }
}
