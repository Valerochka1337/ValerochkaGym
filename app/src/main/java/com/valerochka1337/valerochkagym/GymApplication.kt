package com.valerochka1337.valerochkagym

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.valerochka1337.valerochkagym.data.appicon.AppIconManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Точка входа приложения. Реализует [Configuration.Provider], чтобы WorkManager использовал
 * [HiltWorkerFactory] и мог создавать воркеры с внедрёнными зависимостями (см.
 * [com.valerochka1337.valerochkagym.worker.UploadWorkoutWorker]). Дефолтный инициализатор
 * WorkManager отключён в манифесте — конфигурация берётся отсюда, лениво при первом обращении.
 */
@HiltAndroidApp
class GymApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var appIconManager: AppIconManager

    override fun onCreate() {
        super.onCreate()
        // Иконка лаунчера — часть настройки акцента, а не разовое действие экрана: подписываемся
        // на неё на весь процесс, чтобы состояние alias'ов совпадало с сохранённым выбором.
        appIconManager.startSync()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
