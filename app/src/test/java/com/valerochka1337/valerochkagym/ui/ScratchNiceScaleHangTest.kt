package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.ui.analysis.charts.NiceScale
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** SCRATCH — delete after verification. */
class ScratchNiceScaleHangTest {

    private fun probe(v: Float): String {
        val scale = NiceScale.forRange(rawMin = v, rawMax = v, zeroBased = false)
        val counter = AtomicInteger(0)
        var report = "?"
        val t = Thread {
            // Replica of NiceScale.ticks with a counter so we can detect non-termination.
            var value = scale.min
            while (value <= scale.max + scale.step * 0.5f) {
                if (counter.incrementAndGet() > 5_000_000) {
                    report = "NON-TERMINATING (value stuck at $value)"
                    return@Thread
                }
                value += scale.step
            }
            report = "terminated with ${counter.get()} ticks"
        }
        t.isDaemon = true
        t.start()
        t.join(3000)
        return "v=$v min=${scale.min} max=${scale.max} step=${scale.step} -> $report (alive=${t.isAlive})"
    }

    @Test
    fun probeValues() {
        listOf(100f, 1000f, 8191f, 8192f, 10000f, 11666.667f, 20000f, 65536f).forEach {
            println(probe(it))
        }
        assertTrue(true)
    }

    @Test(timeout = 5000)
    fun realTicksAtTenThousand() {
        val scale = NiceScale.forRange(rawMin = 10000f, rawMax = 10000f, zeroBased = false)
        val ticks = scale.ticks
        println("real ticks size=${ticks.size}")
    }
}
