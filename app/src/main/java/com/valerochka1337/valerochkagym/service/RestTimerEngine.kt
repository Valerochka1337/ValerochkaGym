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
import kotlin.time.Duration.Companion.milliseconds

/** Источник «сейчас» в миллисекундах стенных часов. Единственный сид времени для [RestTimerEngine]. */
fun interface WallClock {
    fun nowMillis(): Long
}

/**
 * Снимок таймера отдыха. [remainingSec] == 0 — финальный кадр перед авто-сбросом в null.
 *
 * [endsAtMillis] — момент окончания по стенным часам ([System.currentTimeMillis]). Это
 * источник истины: [remainingSec] всегда вычисляется из него, а не накапливается тиками.
 * Уведомление отдаёт это же значение в `Notification.Builder.setWhen` вместе с
 * `setChronometerCountDown(true)`, и система рисует живой отсчёт в чипе статус-бара сама —
 * поэтому часы обязаны быть стенными, а не [android.os.SystemClock.elapsedRealtime].
 */
data class RestTimerState(
    val totalSec: Int,
    val remainingSec: Int,
    val endsAtMillis: Long,
)

/**
 * Чистый (без Android-зависимостей) движок таймера отдыха. Тикает раз в секунду на
 * инжектированном [scope] (в проде — [ApplicationScope] на Dispatchers.IO).
 *
 * Тик — не декремент, а пересчёт остатка от [RestTimerState.endsAtMillis]. Разница видна, когда
 * процесс замораживают (Doze, экономия батареи): счётчик бы отстал ровно на время простоя, а
 * пересчёт от дедлайна сам себя догоняет на первом же тике после разморозки.
 *
 * Тестируемость: сиды — [scope] и [clock]. Тест конструирует движок с TestScope, и `delay`
 * управляется виртуальным временем без моков. Поэтому диспетчер [scope] НЕ переопределяем
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
    private val clock: WallClock,
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
        val endsAt = clock.nowMillis() + sec * MILLIS_PER_SECOND
        _state.update {
            RestTimerState(totalSec = sec, remainingSec = sec, endsAtMillis = endsAt)
        }
        tickerJob = scope.launch {
            while (isActive && myGeneration == generation) {
                delay(TICK_MS.milliseconds)
                if (myGeneration != generation) break
                // Время читаем один раз до CAS: лямбда может быть вызвана повторно при гонке, и
                // разные показания часов внутри неё дали бы неповторяемый результат.
                val now = clock.nowMillis()
                val updated = _state.updateAndGet { current ->
                    current?.copy(remainingSec = remainingSeconds(current.endsAtMillis, now))
                }
                if (updated == null) break
                if (updated.remainingSec == 0) {
                    _finished.emit(Unit)
                    delay(FINAL_FRAME_MS.milliseconds)
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
     * Правит оставшееся время (+15/−15), сдвигая дедлайн и не опуская его раньше «сейчас».
     * Без финального сигнала.
     *
     * Игнорируется, когда таймер уже отсчитал до нуля ([RestTimerState.remainingSec] == 0): в этот
     * финальный кадр тик-цикл уже завершился, и оживлять обнулённое состояние нельзя — пилюля
     * замерла бы без тикера.
     */
    fun addSeconds(delta: Int) {
        val now = clock.nowMillis()
        _state.update { current ->
            if (current == null || current.remainingSec == 0) {
                current
            } else {
                val endsAt = (current.endsAtMillis + delta * MILLIS_PER_SECOND).coerceAtLeast(now)
                val remaining = remainingSeconds(endsAt, now)
                current.copy(
                    totalSec = maxOf(current.totalSec, remaining),
                    remainingSec = remaining,
                    endsAtMillis = endsAt,
                )
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

/**
 * Остаток в секундах, округлённый вверх: реальный тик приходит чуть позже ровной секунды, и
 * округление вниз съедало бы по единице на каждом кадре («3, 1, 0» вместо «3, 2, 1, 0»).
 */
private fun remainingSeconds(endsAtMillis: Long, nowMillis: Long): Int {
    val left = endsAtMillis - nowMillis
    if (left <= 0) return 0
    return ((left + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()
}

private const val MILLIS_PER_SECOND = 1000L
private const val TICK_MS = 1000L
private const val FINAL_FRAME_MS = 1000L
