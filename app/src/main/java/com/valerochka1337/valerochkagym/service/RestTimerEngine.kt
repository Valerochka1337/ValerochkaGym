package com.valerochka1337.valerochkagym.service

import com.valerochka1337.valerochkagym.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Снимок таймера отдыха. [remainingSec] == 0 — финальный кадр перед авто-сбросом в null. */
data class RestTimerState(val totalSec: Int, val remainingSec: Int)

/**
 * Чистый (без Android-зависимостей) движок таймера отдыха. Тикает раз в секунду на
 * инжектированном [scope] (в проде — [ApplicationScope] на Dispatchers.IO). Единственный сид
 * тестируемости — сам [scope]: Стадия 14 конструирует движок напрямую с TestScope, и `delay`
 * управляется виртуальным временем без каких-либо моков.
 *
 * [state] == null означает «таймер неактивен». По достижении нуля движок эмитит финальный кадр
 * (remainingSec == 0), шлёт событие [finished] и через ~1 секунду сбрасывает [state] в null,
 * чтобы UI успел показать 0:00.
 */
@Singleton
class RestTimerEngine @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<RestTimerState?>(null)
    val state: StateFlow<RestTimerState?> = _state.asStateFlow()

    // extraBufferCapacity=1 — событие не теряется, даже если в момент эмита нет подписчиков.
    private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val finished: SharedFlow<Unit> = _finished.asSharedFlow()

    private var tickerJob: Job? = null

    /** Запускает (перезапускает) отсчёт на [sec] секунд. sec <= 0 просто гасит таймер. */
    fun start(sec: Int) {
        tickerJob?.cancel()
        if (sec <= 0) {
            _state.value = null
            tickerJob = null
            return
        }
        _state.value = RestTimerState(totalSec = sec, remainingSec = sec)
        tickerJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val current = _state.value ?: break
                val next = current.remainingSec - 1
                if (next <= 0) {
                    _state.value = current.copy(remainingSec = 0)
                    _finished.emit(Unit)
                    delay(FINAL_FRAME_MS)
                    // Не затираем состояние, если за это время уже стартовал новый отдых.
                    if (_state.value?.remainingSec == 0) {
                        _state.value = null
                    }
                    break
                }
                _state.value = current.copy(remainingSec = next)
            }
        }
    }

    /** Правит оставшееся время (+15/−15), не опускаясь ниже нуля. Без финального сигнала. */
    fun addSeconds(delta: Int) {
        val current = _state.value ?: return
        val remaining = (current.remainingSec + delta).coerceAtLeast(0)
        _state.value = current.copy(
            totalSec = maxOf(current.totalSec, remaining),
            remainingSec = remaining,
        )
    }

    /** Останавливает отдых немедленно, без финального сигнала [finished]. */
    fun skip() {
        tickerJob?.cancel()
        tickerJob = null
        _state.value = null
    }
}

private const val TICK_MS = 1000L
private const val FINAL_FRAME_MS = 1000L
