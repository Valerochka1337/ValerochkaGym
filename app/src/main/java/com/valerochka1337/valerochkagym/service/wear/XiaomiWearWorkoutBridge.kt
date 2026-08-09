package com.valerochka1337.valerochkagym.service.wear

import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.domain.currentFocus
import com.valerochka1337.valerochkagym.domain.formatSet
import com.valerochka1337.valerochkagym.domain.lastCompletedFocus
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateReading
import com.valerochka1337.valerochkagym.service.heartrate.freshAt
import com.valerochka1337.valerochkagym.service.RestTimerState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Протокольный мост между foreground-сервисом тренировки и RPK ValGym.
 *
 * Транспорт отделён в [WearableTransport], а эта часть владеет только JSON-контрактом. В итоге
 * команды браслета поступают в [commands], а их применение остаётся в
 * [com.valerochka1337.valerochkagym.service.WorkoutSessionService] — единственном месте, где
 * известен актуальный подход и уже работают действия уведомления.
 */
@Singleton
class XiaomiWearWorkoutBridge @Inject constructor(
    private val transport: WearableTransport,
) {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val _commands = MutableSharedFlow<WatchCommand>(extraBufferCapacity = COMMAND_BUFFER_SIZE)
    val commands: SharedFlow<WatchCommand> = _commands.asSharedFlow()

    private val lock = Any()
    private var started = false
    private var latestState: PhoneState? = null
    private var lastSentState: PhoneState? = null
    private var latestHeartRate: HeartRateReading? = null
    private var lastSentHeartRate: HeartRateReading? = null
    private var heartRateWasSent = false
    private var lastSequence = 0L
    private val handledCommandIds = LinkedHashSet<String>()

    fun start() {
        val shouldStart = synchronized(lock) {
            if (started) {
                false
            } else {
                started = true
                true
            }
        }
        if (!shouldStart) return

        transport.start(
            onConnected = {
                sendLatest(force = true)
                sendHeartRate(force = true)
            },
            onMessage = ::handleWatchMessage,
        )
    }

    fun stop() {
        val shouldStop = synchronized(lock) {
            if (!started) {
                false
            } else {
                started = false
                latestState = null
                lastSentState = null
                latestHeartRate = null
                lastSentHeartRate = null
                heartRateWasSent = false
                handledCommandIds.clear()
                true
            }
        }
        if (shouldStop) transport.stop()
    }

    /**
     * Обновляет снимок активной тренировки. Тики обычного отдыха не отправляются: RPK сам считает
     * оставшееся время по [RestTimerState.endsAtMillis], поэтому пакет уходит только при реальном
     * изменении упражнения, фазы или дедлайна.
     */
    fun publish(workout: WorkoutFull?, rest: RestTimerState?) {
        val state = workout?.toPhoneState(rest)
        val changed = synchronized(lock) {
            latestState = state
            state != null && state != lastSentState
        }
        if (changed) sendLatest(force = false)
    }

    /**
     * Передаёт только свежее live-измерение. Когда монитор сам сбрасывает просроченное значение,
     * RPK получает один пустой пакет и перестаёт рисовать его как живой.
     */
    fun publishHeartRate(reading: HeartRateReading?) {
        val fresh = reading.freshAt(System.currentTimeMillis())
        val changed = synchronized(lock) {
            if (latestHeartRate == fresh) return@synchronized false
            latestHeartRate = fresh
            true
        }
        if (changed) sendHeartRate(force = false)
    }

    private fun handleWatchMessage(bytes: ByteArray) {
        val message = runCatching {
            json.decodeFromString<WatchMessage>(bytes.decodeToString())
        }.getOrNull() ?: return

        if (message.version != PROTOCOL_VERSION) return

        when (message.type) {
            TYPE_READY -> {
                if (synchronized(lock) { started }) {
                    sendLatest(force = true)
                    sendHeartRate(force = true)
                }
            }

            TYPE_REQUEST_STATE -> {
                sendLatest(force = true)
                sendHeartRate(force = true)
            }

            TYPE_PING -> sendPong()
            TYPE_COMMAND -> message.toCommandOrNull()?.let { command ->
                if (rememberCommand(message.id)) {
                    _commands.tryEmit(command)
                }
            }
        }
    }

    private fun rememberCommand(id: String?): Boolean = synchronized(lock) {
        if (!started) return@synchronized false
        if (id == null) return@synchronized true
        if (!handledCommandIds.add(id)) return@synchronized false

        if (handledCommandIds.size > MAX_RECORDED_COMMANDS) {
            handledCommandIds.remove(handledCommandIds.first())
        }
        true
    }

    private fun sendLatest(force: Boolean) {
        val message = synchronized(lock) {
            val state = latestState ?: return
            if (!started || (!force && state == lastSentState)) return

            lastSentState = state
            state.copy(sequence = nextSequence())
        }
        transport.send(json.encodeToString(message).encodeToByteArray())
    }

    private fun sendPong() {
        val shouldSend = synchronized(lock) { started }
        if (!shouldSend) return

        transport.send(
            json.encodeToString(PhonePong()).encodeToByteArray(),
        )
    }

    private fun sendHeartRate(force: Boolean) {
        val message = synchronized(lock) {
            if (
                !started ||
                (latestHeartRate == null && !heartRateWasSent) ||
                (!force && heartRateWasSent && latestHeartRate == lastSentHeartRate)
            ) {
                return
            }
            lastSentHeartRate = latestHeartRate
            heartRateWasSent = true
            PhoneHeartRate(
                heartRateBpm = latestHeartRate?.bpm,
                heartRateUpdatedAtMillis = latestHeartRate?.updatedAtMillis,
            )
        }
        transport.send(json.encodeToString(message).encodeToByteArray())
    }

    private fun nextSequence(): Long {
        lastSequence = maxOf(lastSequence + 1, System.currentTimeMillis())
        return lastSequence
    }

    private fun WorkoutFull.toPhoneState(rest: RestTimerState?): PhoneState {
        val focus = if (rest == null) currentFocus() else lastCompletedFocus() ?: currentFocus()
        return PhoneState(
            sequence = 0,
            phase = if (rest == null) PHASE_WORK else PHASE_REST,
            workoutName = workout.name,
            exerciseName = focus?.exerciseName ?: ALL_SETS_DONE_TEXT,
            setNumber = focus?.setNumber,
            setsInExercise = focus?.setsInExercise,
            setValue = focus?.let { formatSet(it.set, it.type) },
            restEndsAtMillis = rest?.endsAtMillis,
            restMode = REST_MODE_TIMER,
            targetHeartRateMinBpm = DEFAULT_TARGET_HEART_RATE_MIN,
            targetHeartRateMaxBpm = DEFAULT_TARGET_HEART_RATE_MAX,
        )
    }

    private fun WatchMessage.toCommandOrNull(): WatchCommand? = when (command) {
        COMMAND_ADD_REST_SECONDS -> seconds
            ?.takeIf { it == REST_STEP_SECONDS || it == -REST_STEP_SECONDS }
            ?.let { WatchCommand.AddRestSeconds(it) }

        COMMAND_SKIP_REST -> WatchCommand.SkipRest
        COMMAND_COMPLETE_SET -> WatchCommand.CompleteSet
        else -> null
    }

    @Serializable
    private data class PhoneState(
        val v: Int = PROTOCOL_VERSION,
        val type: String = TYPE_STATE,
        val sequence: Long,
        val phase: String,
        val workoutName: String,
        val exerciseName: String,
        val setNumber: Int? = null,
        val setsInExercise: Int? = null,
        val setValue: String? = null,
        val restEndsAtMillis: Long? = null,
        val restMode: String = REST_MODE_TIMER,
        val targetHeartRateMinBpm: Int = DEFAULT_TARGET_HEART_RATE_MIN,
        val targetHeartRateMaxBpm: Int = DEFAULT_TARGET_HEART_RATE_MAX,
    )

    @Serializable
    private data class PhonePong(
        val v: Int = PROTOCOL_VERSION,
        val type: String = TYPE_PONG,
        val source: String = SOURCE_PHONE,
    )

    @Serializable
    private data class PhoneHeartRate(
        val v: Int = PROTOCOL_VERSION,
        val type: String = TYPE_HEART_RATE,
        val heartRateBpm: Int? = null,
        val heartRateUpdatedAtMillis: Long? = null,
    )

    @Serializable
    private data class WatchMessage(
        val v: Int = 0,
        val type: String = "",
        val id: String? = null,
        val command: String? = null,
        val seconds: Int? = null,
    ) {
        val version: Int get() = v
    }

    sealed interface WatchCommand {
        data class AddRestSeconds(val seconds: Int) : WatchCommand
        data object SkipRest : WatchCommand
        data object CompleteSet : WatchCommand
    }

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val TYPE_STATE = "state"
        const val TYPE_READY = "ready"
        const val TYPE_REQUEST_STATE = "request_state"
        const val TYPE_PING = "ping"
        const val TYPE_PONG = "pong"
        const val TYPE_HEART_RATE = "heart_rate"
        const val TYPE_COMMAND = "command"
        const val PHASE_WORK = "work"
        const val PHASE_REST = "rest"
        const val REST_MODE_TIMER = "timer"
        const val COMMAND_ADD_REST_SECONDS = "add_rest_seconds"
        const val COMMAND_SKIP_REST = "skip_rest"
        const val COMMAND_COMPLETE_SET = "complete_set"
        const val SOURCE_PHONE = "phone"
        const val REST_STEP_SECONDS = 15
        const val DEFAULT_TARGET_HEART_RATE_MIN = 100
        const val DEFAULT_TARGET_HEART_RATE_MAX = 120
        const val MAX_RECORDED_COMMANDS = 64
        const val COMMAND_BUFFER_SIZE = 16
        const val ALL_SETS_DONE_TEXT = "Все подходы выполнены"
    }
}
