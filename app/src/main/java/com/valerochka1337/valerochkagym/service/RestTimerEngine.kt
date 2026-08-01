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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Снимок таймера отдыха. [remainingSec] == 0 — финальный кадр перед авто-сбросом в null. */
data class RestTimerState(val totalSec: Int, val remainingSec: Int)

/**
 * Чистый (без Android-зависимостей) движок таймера отдыха. Тикает раз в секунду на
 * инжектированном [scope] (в проде — [ApplicationScope] на Dispatchers.IO).
 *
 * Тестируемость (Стадия 14): единственный сид — сам [scope]. Тест конструирует движок с TestScope,
 * и `delay` управляется виртуальным временем без моков. Поэтому диспетчер [scope] НЕ переопределяем
 * (это сломало бы виртуальное время); вместо жёсткой однопоточной изоляции атомарность всех правок
 * [state] обеспечивают линеаризуемые CAS-операции [MutableStateFlow.update]/[updateAndGet], а корректный
 * перезапуск — счётчик [generation] (устаревший тик-цикл сам прекращается).
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

    // replay=0 → событие теряется, если в момент emit нет подписчиков; extraBufferCapacity=1 лишь
    // делает emit неблокирующим. Подписчик (foreground-сервис) живёт всю тренировку, так что ок.
    private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val finished: SharedFlow<Unit> = _finished.asSharedFlow()

    private var tickerJob: Job? = null

    // Поколение таймера: каждый start()/skip() его инкрементирует; тик-цикл прекращается, как только
    // его поколение устарело — даже до фактической отмены job. Пишется только с одного (главного)
    // потока UI/сервиса, читается из тик-цикла; @Volatile гарантирует видимость.
    @Volatile
    private var generation = 0

    /** Запускает (перезапускает) отсчёт на [sec] секунд. sec <= 0 просто гасит таймер. */
    fun start(sec: Int) {
        tickerJob?.cancel()
        val myGeneration = ++generation
        if (sec <= 0) {
            _state.update { null }
            tickerJob = null
            return
        }
        _state.update { RestTimerState(totalSec = sec, remainingSec = sec) }
        tickerJob = scope.launch {
            while (isActive && myGeneration == generation) {
                delay(TICK_MS)
                if (myGeneration != generation) break
                // Решение «дойти до нуля» атомарно с декрементом: если за этот тик кто-то добавил
                // время (addSeconds), CAS-лямбда видит свежее значение и не финиширует преждевременно.
                val updated = _state.updateAndGet { current ->
                    when {
                        current == null -> null
                        current.remainingSec <= 1 -> current.copy(remainingSec = 0)
                        else -> current.copy(remainingSec = current.remainingSec - 1)
                    }
                }
                if (updated == null) break
                if (updated.remainingSec == 0) {
                    _finished.emit(Unit)
                    delay(FINAL_FRAME_MS)
                    // Не затираем состояние, если за это время уже стартовал новый отдых.
                    if (myGeneration == generation) {
                        _state.update { current -> if (current?.remainingSec == 0) null else current }
                    }
                    break
                }
            }
        }
    }

    /**
     * Правит оставшееся время (+15/−15), не опускаясь ниже нуля. Без финального сигнала.
     * Игнорируется, когда таймер уже отсчитал до нуля ([RestTimerState.remainingSec] == 0): в этот
     * финальный кадр тик-цикл уже завершился, и оживлять обнулённое состояние нельзя — пилюля
     * замерла бы без тикера.
     */
    fun addSeconds(delta: Int) {
        _state.update { current ->
            if (current == null || current.remainingSec == 0) {
                current
            } else {
                val remaining = (current.remainingSec + delta).coerceAtLeast(0)
                current.copy(totalSec = maxOf(current.totalSec, remaining), remainingSec = remaining)
            }
        }
    }

    /** Останавливает отдых немедленно, без финального сигнала [finished]. */
    fun skip() {
        generation++
        tickerJob?.cancel()
        tickerJob = null
        _state.update { null }
    }
}

private const val TICK_MS = 1000L
private const val FINAL_FRAME_MS = 1000L
