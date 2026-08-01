package com.valerochka1337.valerochkagym.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.valerochka1337.valerochkagym.MainActivity
import com.valerochka1337.valerochkagym.R
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground-сервис активной тренировки. Держит постоянное уведомление сессии (название, живой
 * хронометр, текущее упражнение) и, пока идёт отдых, переключается на отсчёт «Отдых: M:SS» с
 * действиями «+15 сек» / «Пропустить». По окончании отдыха даёт звук/вибрацию (по настройкам) и
 * отдельное heads-up уведомление «Отдых окончен».
 *
 * Жизненный цикл: [start] вызывается из UI при старте тренировки (приложение на переднем плане —
 * запуск FGS разрешён). Явный stop не нужен: сервис подписан на [ActiveWorkoutRepository.observeActive]
 * и сам вызывает stopSelf, когда активной тренировки не стало (finish/discard).
 */
@AndroidEntryPoint
class WorkoutSessionService : LifecycleService() {

    @Inject lateinit var activeWorkoutRepository: ActiveWorkoutRepository

    @Inject lateinit var restTimerEngine: RestTimerEngine

    @Inject lateinit var settingsRepository: SettingsRepository

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private var currentWorkout: WorkoutFull? = null
    private var currentRest: RestTimerState? = null

    // Защита от преждевременного stopSelf: гасим сервис только увидев, что тренировка исчезла
    // ПОСЛЕ того как хотя бы раз её наблюдали.
    private var sawWorkout = false

    /** Внутренний приёмник действий уведомления отдыха (+15 / пропустить). Не экспортируется. */
    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_ADD_15 -> restTimerEngine.addSeconds(REST_STEP_SECONDS)
                ACTION_SKIP -> restTimerEngine.skip()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        registerReceiver(
            actionReceiver,
            IntentFilter().apply {
                addAction(ACTION_ADD_15)
                addAction(ACTION_SKIP)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Здесь, а не в onCreate: покрывает и первый старт, и повторную доставку intent — так
        // startForeground гарантированно вызывается в отведённое окно после startForegroundService.
        startForeground(
            SESSION_NOTIFICATION_ID,
            buildSessionNotification(currentWorkout),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        // NOT_STICKY: после гибели процесса состояние движка/тренировки не восстановить, а sticky
        // рестарт оставил бы «зомби»-уведомление «Тренировка» без реальной сессии.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(actionReceiver)
        super.onDestroy()
    }

    private fun observeState() {
        lifecycleScope.launch {
            activeWorkoutRepository.observeActive().collect { workout ->
                if (workout == null) {
                    if (sawWorkout) stopSelf()
                    return@collect
                }
                sawWorkout = true
                currentWorkout = workout
                if (currentRest == null) updateForegroundNotification()
            }
        }
        lifecycleScope.launch {
            restTimerEngine.state.collect { rest ->
                currentRest = rest
                updateForegroundNotification()
            }
        }
        lifecycleScope.launch {
            restTimerEngine.finished.collect { onRestFinished() }
        }
    }

    private fun updateForegroundNotification() {
        val rest = currentRest
        val notification = if (rest != null) {
            buildRestNotification(rest)
        } else {
            buildSessionNotification(currentWorkout)
        }
        notificationManager.notify(SESSION_NOTIFICATION_ID, notification)
    }

    private fun buildSessionNotification(workout: WorkoutFull?): Notification {
        val title = workout?.workout?.name ?: DEFAULT_WORKOUT_TITLE
        val builder = NotificationCompat.Builder(this, SESSION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rest_timer)
            .setContentTitle(title)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
        // Живое время тренировки без ручных тиков.
        workout?.workout?.startedAt?.let { startedAt ->
            builder.setUsesChronometer(true).setWhen(startedAt).setShowWhen(true)
        }
        currentExerciseName(workout)?.let { builder.setContentText(it) }
        return builder.build()
    }

    private fun buildRestNotification(rest: RestTimerState): Notification {
        return NotificationCompat.Builder(this, SESSION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rest_timer)
            .setContentTitle(REST_TITLE)
            .setContentText(formatRest(rest.remainingSec))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .addAction(0, ACTION_ADD_15_LABEL, broadcastIntent(ACTION_ADD_15, REQUEST_ADD_15))
            .addAction(0, ACTION_SKIP_LABEL, broadcastIntent(ACTION_SKIP, REQUEST_SKIP))
            .build()
    }

    private fun onRestFinished() {
        lifecycleScope.launch {
            val settings = settingsRepository.settings.first()
            if (settings.soundEnabled) playFinishedSound()
            if (settings.vibrationEnabled) vibrate()
        }
        val notification = NotificationCompat.Builder(this, REST_DONE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rest_timer)
            .setContentTitle(REST_DONE_TITLE)
            .setContentText(REST_DONE_TEXT)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setTimeoutAfter(REST_DONE_TIMEOUT_MS)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(REST_DONE_NOTIFICATION_ID, notification)
    }

    private fun playFinishedSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        RingtoneManager.getRingtone(applicationContext, uri)?.play()
    }

    private fun vibrate() {
        val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun currentExerciseName(workout: WorkoutFull?): String? =
        workout?.exercises
            ?.firstOrNull { exercise -> exercise.sets.any { !it.isCompleted } }
            ?.exercise
            ?.name

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun broadcastIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannels() {
        val session = NotificationChannel(
            SESSION_CHANNEL_ID,
            SESSION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = SESSION_CHANNEL_DESC
            setShowBadge(false)
        }
        // Звук/вибрацию канала отключаем — сигнал даём вручную по пользовательским настройкам.
        val restDone = NotificationChannel(
            REST_DONE_CHANNEL_ID,
            REST_DONE_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = REST_DONE_CHANNEL_DESC
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(session)
        notificationManager.createNotificationChannel(restDone)
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, WorkoutSessionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WorkoutSessionService::class.java))
        }

        private const val SESSION_CHANNEL_ID = "workout_session"
        private const val SESSION_CHANNEL_NAME = "Активная тренировка"
        private const val SESSION_CHANNEL_DESC = "Уведомление идущей тренировки и таймера отдыха"
        private const val REST_DONE_CHANNEL_ID = "rest_timer"
        private const val REST_DONE_CHANNEL_NAME = "Окончание отдыха"
        private const val REST_DONE_CHANNEL_DESC = "Сигнал о том, что отдых закончился"

        private const val SESSION_NOTIFICATION_ID = 1001
        private const val REST_DONE_NOTIFICATION_ID = 1002

        private const val ACTION_ADD_15 = "com.valerochka1337.valerochkagym.action.REST_ADD_15"
        private const val ACTION_SKIP = "com.valerochka1337.valerochkagym.action.REST_SKIP"
        private const val ACTION_ADD_15_LABEL = "+15 сек"
        private const val ACTION_SKIP_LABEL = "Пропустить"

        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_ADD_15 = 1
        private const val REQUEST_SKIP = 2

        private const val REST_STEP_SECONDS = 15
        private const val VIBRATION_MS = 500L
        private const val REST_DONE_TIMEOUT_MS = 10_000L

        private const val DEFAULT_WORKOUT_TITLE = "Тренировка"
        private const val REST_TITLE = "Отдых"
        private const val REST_DONE_TITLE = "Отдых окончен"
        private const val REST_DONE_TEXT = "Пора продолжать"
    }
}

/** Форматирует остаток отдыха как M:SS. */
private fun formatRest(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    return "%d:%02d".format(safe / 60, safe % 60)
}
