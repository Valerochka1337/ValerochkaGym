package com.valerochka1337.valerochkagym

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.ui.navigation.MainScaffold
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val accentFlow = remember {
                settingsRepository.settings.map { it.accent }.distinctUntilChanged()
            }
            // Пока акцент не прочитан из DataStore, не рисуем ничего: показать интерфейс дефолтным
            // цветом и перекрасить его через кадр заметнее, чем короткий тёмный фон окна.
            val accent by accentFlow.collectAsStateWithLifecycle(initialValue = null)
            accent?.let {
                GymTheme(accent = it) {
                    MainScaffold()
                }
            }
        }
    }
}
