package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.ui.analysis.charts.formatAxisValue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Подписи осей: без хвостовых нулей, максимум один знак после запятой. */
class ChartCommonTest {

    @Test
    fun `whole values lose the decimal part`() {
        assertEquals("0", formatAxisValue(0f))
        assertEquals("12", formatAxisValue(12f))
        assertEquals("1000", formatAxisValue(1000f))
    }

    @Test
    fun `fractional values keep a single digit`() {
        assertEquals("12.3", formatAxisValue(12.34f))
        assertEquals("0.5", formatAxisValue(0.55f))
    }

    @Test
    fun `values just below a whole number stay truncated not rounded up`() {
        assertEquals("9.9", formatAxisValue(9.99f))
    }
}
