package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.ui.analysis.charts.NiceScale
import org.junit.Assert.fail
import org.junit.Test

class ZzScratchDegenerateScaleTest {

    @Test
    fun dumpEverything() {
        val y = 58.333f
        val trend = NiceScale.forRange(rawMin = y, rawMax = y, zeroBased = false)
        val trendTicks = trend.ticks
        val trendLabels = trendTicks.map { "${it.toInt()} кг" }

        val column = NiceScale.forRange(0f, 0f)
        val columnTicks = column.ticks

        val sane = NiceScale.forRange(0f, 40f)

        fail(
            buildString {
                append("TREND scale=$trend ")
                append("ticksCount=${trendTicks.size} ticks=$trendTicks ")
                append("labels=$trendLabels ")
                append("fractionOfData=${trend.fraction(y)} || ")
                append("COLUMN scale=$column ticksCount=${columnTicks.size} ticks=$columnTicks ")
                append("fractionOfZero=${column.fraction(0f)} || ")
                append("SANITY scale=$sane ticks=${sane.ticks}")
            },
        )
    }
}
