package com.valerochka1337.valerochkagym.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RestTimerEngine]. The engine is Android-free: its seams are the injected
 * [kotlinx.coroutines.CoroutineScope] and [WallClock], so every test constructs it over
 * [TestScope.backgroundScope], whose dispatcher shares the test's
 * [kotlinx.coroutines.test.TestCoroutineScheduler]. That makes the `delay`-driven ticker run in
 * virtual time — no real waiting, no Robolectric.
 *
 * The scope uses a [kotlinx.coroutines.test.StandardTestDispatcher] (the `runTest` default), so the
 * ticker never advances on its own: a second of the timer only elapses when the test explicitly moves
 * the clock. That is exactly what [advanceSeconds] does — `advanceTimeBy(1000)` walks up to (but not
 * including) the next tick, then `runCurrent()` fires the tick scheduled at that instant. Stepping one
 * second at a time lets each intermediate frame be asserted deterministically.
 *
 * By default the [WallClock] is wired to the scheduler's virtual clock, so wall time and coroutine
 * time move together. The stall test deliberately decouples them: that is the only way to model a
 * frozen process, where `delay` does not fire while real time keeps running.
 *
 * [RestTimerEngine.finished] has `replay = 0`, so events are lost without a live subscriber. Each test
 * attaches one on an [UnconfinedTestDispatcher] before starting a timer; the unconfined dispatcher
 * subscribes eagerly, so any later `emit` is delivered synchronously into [collectFinished]'s list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestTimerEngineTest {

    @Test
    fun `counting to zero decrements every second, shows the final frame, finishes once, then clears`() =
        runTest {
            val engine = engine()
            val finished = collectFinished(engine)

            engine.start(3)
            assertEquals(RestTimerState(3, remainingSec = 3, endsAtMillis = 3_000), engine.state.value)

            advanceSeconds(1)
            assertEquals(RestTimerState(3, remainingSec = 2, endsAtMillis = 3_000), engine.state.value)
            advanceSeconds(1)
            assertEquals(RestTimerState(3, remainingSec = 1, endsAtMillis = 3_000), engine.state.value)

            advanceSeconds(1)
            // Final frame: remainingSec == 0 is observable, and finished fires exactly once.
            assertEquals(RestTimerState(3, remainingSec = 0, endsAtMillis = 3_000), engine.state.value)
            assertEquals(1, finished.size)

            // After FINAL_FRAME_MS the state auto-resets to null (inactive).
            advanceSeconds(1)
            assertNull(engine.state.value)
            assertEquals(1, finished.size)
        }

    @Test
    fun `addSeconds extends the remaining time, grows total and pushes the deadline`() = runTest {
        val engine = engine()
        collectFinished(engine)

        engine.start(10)
        advanceSeconds(3)
        assertEquals(RestTimerState(10, remainingSec = 7, endsAtMillis = 10_000), engine.state.value)

        engine.addSeconds(15)
        // Deadline moves 10s → 25s; remaining becomes 22 and total grows to max(10, 22) so it stays
        // >= remaining (the progress bar would otherwise overflow).
        assertEquals(RestTimerState(22, remainingSec = 22, endsAtMillis = 25_000), engine.state.value)
    }

    @Test
    fun `addSeconds with a large negative delta clamps the deadline to now without finishing immediately`() =
        runTest {
            val engine = engine()
            val finished = collectFinished(engine)

            engine.start(10)
            advanceSeconds(5)
            assertEquals(RestTimerState(10, remainingSec = 5, endsAtMillis = 10_000), engine.state.value)

            engine.addSeconds(-999)
            // The deadline is pinned at "now" rather than sent far into the past, so remaining is 0
            // right away — but addSeconds never emits finished and the ticker keeps running.
            assertEquals(RestTimerState(10, remainingSec = 0, endsAtMillis = 5_000), engine.state.value)
            assertTrue(finished.isEmpty())

            // The next tick observes remainingSec == 0, emits finished once, then FINAL_FRAME_MS clears it.
            advanceSeconds(1)
            assertEquals(1, finished.size)
            advanceSeconds(1)
            assertNull(engine.state.value)
            assertEquals(1, finished.size)
        }

    @Test
    fun `addSeconds is a no-op on the final frame and does not revive the timer`() = runTest {
        val engine = engine()
        val finished = collectFinished(engine)

        engine.start(1)
        advanceSeconds(1)
        assertEquals(RestTimerState(1, remainingSec = 0, endsAtMillis = 1_000), engine.state.value)
        assertEquals(1, finished.size)

        engine.addSeconds(15)
        // remainingSec == 0 ⇒ addSeconds returns the state unchanged; the timer must not come back to life.
        assertEquals(RestTimerState(1, remainingSec = 0, endsAtMillis = 1_000), engine.state.value)

        advanceSeconds(1)
        assertNull(engine.state.value)
        assertEquals(1, finished.size)
    }

    @Test
    fun `skip stops immediately without a finished event`() = runTest {
        val engine = engine()
        val finished = collectFinished(engine)

        engine.start(60)
        advanceSeconds(5)
        assertEquals(RestTimerState(60, remainingSec = 55, endsAtMillis = 60_000), engine.state.value)

        engine.skip()
        assertNull(engine.state.value)

        // The old ticker is cancelled: no more decrements, no finished, however far the clock moves.
        advanceSeconds(60)
        assertNull(engine.state.value)
        assertTrue(finished.isEmpty())
    }

    @Test
    fun `a second start restarts the timer and the stale ticker has no effect`() = runTest {
        val engine = engine()
        val finished = collectFinished(engine)

        engine.start(60)
        advanceSeconds(10)
        assertEquals(RestTimerState(60, remainingSec = 50, endsAtMillis = 60_000), engine.state.value)

        engine.start(30)
        assertEquals(RestTimerState(30, remainingSec = 30, endsAtMillis = 40_000), engine.state.value)

        // Only the fresh 30s ticker reaches zero; the old one never fires.
        advanceSeconds(30)
        assertEquals(RestTimerState(30, remainingSec = 0, endsAtMillis = 40_000), engine.state.value)
        assertEquals(1, finished.size)
    }

    @Test
    fun `start with zero seconds leaves the timer inactive and emits nothing`() = runTest {
        val engine = engine()
        val finished = collectFinished(engine)

        engine.start(0)
        assertNull(engine.state.value)

        advanceSeconds(5)
        assertNull(engine.state.value)
        assertTrue(finished.isEmpty())
    }

    @Test
    fun `start with negative seconds leaves the timer inactive and emits nothing`() = runTest {
        val engine = engine()
        val finished = collectFinished(engine)

        engine.start(-5)
        assertNull(engine.state.value)

        advanceSeconds(5)
        assertNull(engine.state.value)
        assertTrue(finished.isEmpty())
    }

    @Test
    fun `restarting during the final window keeps the new timer alive`() = runTest {
        val engine = engine()
        val finished = collectFinished(engine)

        engine.start(1)
        advanceSeconds(1)
        assertEquals(RestTimerState(1, remainingSec = 0, endsAtMillis = 1_000), engine.state.value)
        assertEquals(1, finished.size)

        // Restart before FINAL_FRAME_MS elapses: the generation guard must stop the stale ticker from
        // resetting state to null after its delay.
        engine.start(45)
        assertEquals(RestTimerState(45, remainingSec = 45, endsAtMillis = 46_000), engine.state.value)

        advanceSeconds(1)
        // The stale ticker's reset window has passed, yet the new timer is untouched and ticking.
        assertEquals(RestTimerState(45, remainingSec = 44, endsAtMillis = 46_000), engine.state.value)
        assertEquals(1, finished.size)
    }

    @Test
    fun `after a stall the countdown catches up to the deadline instead of drifting`() = runTest {
        // Wall time is driven by hand here, independent of the scheduler: while the process is frozen
        // no tick runs, yet real seconds keep passing. A decrementing counter would come back 45s
        // behind; recomputing from the deadline must not.
        var wallMillis = 0L
        val engine = RestTimerEngine(backgroundScope, { wallMillis })
        val finished = collectFinished(engine)

        engine.start(60)
        assertEquals(RestTimerState(60, remainingSec = 60, endsAtMillis = 60_000), engine.state.value)

        wallMillis = 45_000
        advanceSeconds(1)
        assertEquals(15, engine.state.value?.remainingSec)
        assertTrue(finished.isEmpty())

        // Stalled straight past the deadline: the first tick after the thaw finishes the rest.
        wallMillis = 61_000
        advanceSeconds(1)
        assertEquals(0, engine.state.value?.remainingSec)
        assertEquals(1, finished.size)
    }

    /** Engine whose wall clock follows the test's virtual time, so both clocks move together. */
    private fun TestScope.engine(): RestTimerEngine =
        RestTimerEngine(backgroundScope, { testScheduler.currentTime })

    /**
     * Subscribes to [RestTimerEngine.finished] on an eager unconfined dispatcher and returns the
     * accumulating list of received events. Must be called before starting a timer.
     */
    private fun TestScope.collectFinished(engine: RestTimerEngine): List<Unit> {
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.finished.collect { events += it }
        }
        return events
    }

    /** Drives the virtual clock forward one tick at a time so each per-second frame is observable. */
    private fun TestScope.advanceSeconds(seconds: Int) {
        repeat(seconds) {
            advanceTimeBy(1000)
            runCurrent()
        }
    }
}
