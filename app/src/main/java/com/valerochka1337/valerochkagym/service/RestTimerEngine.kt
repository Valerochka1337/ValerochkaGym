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
import kotlinx.coroutines.flow.getAndUpdate
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

/** Состояние активного отдыха: обычный таймер или ожидание снижения пульса. */
sealed interface RestTimerState {

    /**
     * Таймерный отдых. [remainingSec] == 0 — финальный кадр перед авто-сбросом в null.
     *
     * [endsAtMillis] — момент окончания по стенным часам ([System.currentTimeMillis]). Это
     * источник истины: [remainingSec] всегда вычисляется из него, а не накапливается тиками.
     */
    data class Timed(
        val totalSec: Int,
        val remainingSec: Int,
        val endsAtMillis: Long,
    ) : RestTimerState

    /**
     * Отдых до новых измерений с пульсом не выше [thresholdBpm] в течение [holdSeconds]. Старое
     * измерение игнорируется: [startedAtMillis] задаёт нижнюю границу времени пакета, который
     * может начать отсчёт. [belowSinceMillis] сбрасывается при высоком или пропавшем пульсе.
     */
    data class HeartRate(
        val thresholdBpm: Int,
        val holdSeconds: Int,
        val startedAtMillis: Long,
        val belowSinceMillis: Long? = null,
    ) : RestTimerState
}

/**
 * Чистый (без Android-зависимостей) движок отдыха. Таймерный режим тикает раз в секунду на
 * инжектированном [scope] (в проде — [ApplicationScope] на Dispatchers.IO); режим пульса ждёт
 * вызова [onHeartRate] от foreground-сервиса.
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

    /** Запускает (перезапускает) таймерный отдых на [sec] секунд. sec <= 0 просто гасит отдых. */
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
            RestTimerState.Timed(totalSec = sec, remainingSec = sec, endsAtMillis = endsAt)
        }
        tickerJob = scope.launch {
            while (isActive && myGeneration == generation) {
                delay(TICK_MS.milliseconds)
                if (myGeneration != generation) break
                // Время читаем один раз до CAS: лямбда может быть вызвана повторно при гонке, и
                // разные показания часов внутри неё дали бы неповторяемый результат.
                val now = clock.nowMillis()
                val updated = _state.updateAndGet { current ->
                    (current as? RestTimerState.Timed)?.copy(
                        remainingSec = remainingSeconds(current.endsAtMillis, now),
                    ) ?: current
                }
                val timed = updated as? RestTimerState.Timed ?: break
                if (timed.remainingSec == 0) {
                    _finished.emit(Unit)
                    delay(FINAL_FRAME_MS.milliseconds)
                    // Не затираем состояние, если за это время уже стартовал новый отдых.
                    if (myGeneration == generation) {
                        _state.update { current ->
                            if ((current as? RestTimerState.Timed)?.remainingSec == 0) null else current
                        }
                    }
                    break
                }
            }
        }
    }

    /** Запускает отдых, который завершит только непрерывный [holdSeconds]-интервал ниже порога. */
    fun startUntilHeartRateAtMost(thresholdBpm: Int, holdSeconds: Int) {
        tickerJob?.cancel()
        tickerJob = null
        generation++
        val startedAtMillis = clock.nowMillis()
        _state.update {
            RestTimerState.HeartRate(
                thresholdBpm = thresholdBpm,
                holdSeconds = holdSeconds,
                startedAtMillis = startedAtMillis,
            )
        }
    }

    /**
     * Принимает уже проверенное на свежесть измерение. Пакет, полученный до старта отдыха,
     * намеренно не используется. Значение выше порога начинает выдержку заново.
     */
    fun onHeartRate(bpm: Int, updatedAtMillis: Long) {
        val previous = _state.getAndUpdate { current ->
            val rest = current as? RestTimerState.HeartRate
            when {
                rest == null || updatedAtMillis <= rest.startedAtMillis -> current
                bpm > rest.thresholdBpm -> rest.copy(belowSinceMillis = null)
                else -> {
                    val belowSince = rest.belowSinceMillis ?: updatedAtMillis
                    if (updatedAtMillis - belowSince >= rest.holdSeconds * MILLIS_PER_SECOND) {
                        null
                    } else {
                        rest.copy(belowSinceMillis = belowSince)
                    }
                }
            }
        }
        if (
            previous is RestTimerState.HeartRate &&
            updatedAtMillis > previous.startedAtMillis &&
            bpm <= previous.thresholdBpm &&
            updatedAtMillis - (previous.belowSinceMillis ?: updatedAtMillis) >=
                previous.holdSeconds * MILLIS_PER_SECOND
        ) {
            _finished.tryEmit(Unit)
        }
    }

    /** Потерянное или устаревшее измерение разрывает непрерывное удержание пульса ниже порога. */
    fun onHeartRateUnavailable() {
        _state.update { current ->
            (current as? RestTimerState.HeartRate)?.copy(belowSinceMillis = null) ?: current
        }
    }

    /**
     * Правит оставшееся время (+15/−15), сдвигая дедлайн и не опуская его раньше «сейчас».
     * Игнорируется для отдыха по пульсу и финального кадра таймера.
     */
    fun addSeconds(delta: Int) {
        val now = clock.nowMillis()
        _state.update { current ->
            val timed = current as? RestTimerState.Timed
            if (timed == null || timed.remainingSec == 0) {
                current
            } else {
                val endsAt = (timed.endsAtMillis + delta * MILLIS_PER_SECOND).coerceAtLeast(now)
                val remaining = remainingSeconds(endsAt, now)
                timed.copy(
                    totalSec = maxOf(timed.totalSec, remaining),
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
