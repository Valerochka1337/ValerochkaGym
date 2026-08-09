package com.valerochka1337.valerochkagym.service.wear

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.service.RestTimerState
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class XiaomiWearWorkoutBridgeTest {

    @Test
    fun `publishing rest sends a stable state and a fresh deadline sends another snapshot`() = runTest {
        val transport = FakeWearableTransport()
        val bridge = XiaomiWearWorkoutBridge(transport)
        bridge.start()
        transport.connect()

        val workout = workout(
            exercise(
                "Жим лёжа",
                set(id = 1, completed = true, completedAt = 1_000),
                set(id = 2),
            ),
        )
        bridge.publish(
            workout,
            RestTimerState.Timed(totalSec = 90, remainingSec = 89, endsAtMillis = 10_000),
        )

        val first = payload(transport.sent.single())
        assertEquals("state", first["type"]?.jsonPrimitive?.content)
        assertEquals("rest", first["phase"]?.jsonPrimitive?.content)
        assertEquals("Тренировка груди", first["workoutName"]?.jsonPrimitive?.content)
        assertEquals("Жим лёжа", first["exerciseName"]?.jsonPrimitive?.content)
        assertEquals(1, first["setNumber"]?.jsonPrimitive?.content?.toInt())
        assertEquals(10_000L, first["restEndsAtMillis"]?.jsonPrimitive?.long)

        // remainingSec меняется каждую секунду, но endsAt остаётся тем же: RPK считает его сам.
        bridge.publish(
            workout,
            RestTimerState.Timed(totalSec = 90, remainingSec = 88, endsAtMillis = 10_000),
        )
        assertEquals(1, transport.sent.size)

        bridge.publish(
            workout,
            RestTimerState.Timed(totalSec = 105, remainingSec = 103, endsAtMillis = 25_000),
        )
        assertEquals(2, transport.sent.size)
        val second = payload(transport.sent.last())
        assertEquals(25_000L, second["restEndsAtMillis"]?.jsonPrimitive?.long)
        assertTrue(
            second["sequence"]!!.jsonPrimitive.long > first["sequence"]!!.jsonPrimitive.long,
        )
    }

    @Test
    fun `heart rate rest keeps the existing protocol and omits a timer deadline`() = runTest {
        val transport = FakeWearableTransport()
        val bridge = XiaomiWearWorkoutBridge(transport)
        bridge.start()
        transport.connect()

        bridge.publish(
            workout(exercise("Жим лёжа", set(id = 1))),
            RestTimerState.HeartRate(thresholdBpm = 110, holdSeconds = 10, startedAtMillis = 1_000),
        )

        val state = payload(transport.sent.single())
        assertEquals("rest", state["phase"]?.jsonPrimitive?.content)
        assertTrue(state["restEndsAtMillis"]!!.jsonPrimitive.isString.not())
        assertEquals("timer", state["restMode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ready requests the latest state and duplicate watch command is ignored`() = runTest {
        val transport = FakeWearableTransport()
        val bridge = XiaomiWearWorkoutBridge(transport)
        val commands = collectCommands(bridge)
        bridge.start()
        transport.connect()
        bridge.publish(workout(exercise("Тяга", set(id = 1))), rest = null)
        transport.sent.clear()

        transport.receive("""{"v":1,"type":"ready","source":"watch"}""")
        assertEquals(1, transport.sent.size)
        assertEquals("work", payload(transport.sent.single())["phase"]?.jsonPrimitive?.content)

        transport.receive(
            """{"v":1,"type":"command","id":"command-1","command":"add_rest_seconds","seconds":15}""",
        )
        transport.receive(
            """{"v":1,"type":"command","id":"command-1","command":"add_rest_seconds","seconds":15}""",
        )
        transport.receive(
            """{"v":1,"type":"command","id":"command-2","command":"add_rest_seconds","seconds":20}""",
        )
        transport.receive(
            """{"v":1,"type":"command","id":"command-3","command":"add_rest_seconds","seconds":-15}""",
        )
        assertEquals(
            listOf(
                XiaomiWearWorkoutBridge.WatchCommand.AddRestSeconds(seconds = 15),
                XiaomiWearWorkoutBridge.WatchCommand.AddRestSeconds(seconds = -15),
            ),
            commands,
        )
    }

    @Test
    fun `fresh heart rate is serialized once and stale clearing is sent once`() = runTest {
        val transport = FakeWearableTransport()
        val bridge = XiaomiWearWorkoutBridge(transport)
        bridge.start()
        transport.connect()
        val reading = HeartRateReading(bpm = 128, updatedAtMillis = System.currentTimeMillis())

        bridge.publishHeartRate(reading)
        bridge.publishHeartRate(reading)

        assertEquals(1, transport.sent.size)
        val heartRate = payload(transport.sent.single())
        assertEquals("heart_rate", heartRate["type"]?.jsonPrimitive?.content)
        assertEquals(128, heartRate["heartRateBpm"]?.jsonPrimitive?.content?.toInt())
        assertEquals(
            reading.updatedAtMillis,
            heartRate["heartRateUpdatedAtMillis"]?.jsonPrimitive?.long,
        )

        bridge.publishHeartRate(null)
        bridge.publishHeartRate(null)

        assertEquals(2, transport.sent.size)
        assertTrue(payload(transport.sent.last())["heartRateBpm"]!!.jsonPrimitive.isString.not())
    }

    private fun TestScope.collectCommands(
        bridge: XiaomiWearWorkoutBridge,
    ): List<XiaomiWearWorkoutBridge.WatchCommand> {
        val commands = mutableListOf<XiaomiWearWorkoutBridge.WatchCommand>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bridge.commands.collect { commands += it }
        }
        return commands
    }

    private fun payload(bytes: ByteArray) = JSON.parseToJsonElement(bytes.decodeToString()).jsonObject

    private fun workout(vararg exercises: WorkoutExerciseWithSets) = WorkoutFull(
        workout = WorkoutEntity(
            id = "workout-1",
            name = "Тренировка груди",
            startedAt = 1_000,
        ),
        exercises = exercises.toList(),
    )

    private fun exercise(
        name: String,
        vararg sets: WorkoutSetEntity,
    ) = WorkoutExerciseWithSets(
        workoutExercise = WorkoutExerciseEntity(
            id = 1,
            workoutId = "workout-1",
            exerciseId = 1,
            position = 0,
        ),
        exercise = ExerciseEntity(
            id = 1,
            name = name,
            muscleGroup = MuscleGroup.CHEST,
            type = ExerciseType.STRENGTH,
        ),
        sets = sets.toList(),
    )

    private fun set(
        id: Long,
        completed: Boolean = false,
        completedAt: Long? = null,
    ) = WorkoutSetEntity(
        id = id,
        workoutExerciseId = 1,
        setIndex = id.toInt(),
        isCompleted = completed,
        completedAt = completedAt,
    )

    private class FakeWearableTransport : WearableTransport {
        private var onConnected: (() -> Unit)? = null
        private var onMessage: ((ByteArray) -> Unit)? = null
        private var connected = false

        val sent = mutableListOf<ByteArray>()

        override fun start(
            onConnected: () -> Unit,
            onMessage: (ByteArray) -> Unit,
        ) {
            this.onConnected = onConnected
            this.onMessage = onMessage
        }

        override fun stop() {
            connected = false
            onConnected = null
            onMessage = null
        }

        override fun send(message: ByteArray) {
            if (connected) sent += message
        }

        fun connect() {
            connected = true
            onConnected?.invoke()
        }

        fun receive(message: String) {
            onMessage?.invoke(message.encodeToByteArray())
        }
    }

    private companion object {
        val JSON = Json
    }
}
