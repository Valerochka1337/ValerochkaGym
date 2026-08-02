package com.valerochka1337.valerochkagym.domain.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Оценка одноповторного максимума: формула и границы доверия. */
class OneRepMaxTest {

    @Test
    fun `a single rep is the weight itself`() {
        assertEquals(120.0, OneRepMax.epley(120.0, 1)!!, 1e-9)
    }

    @Test
    fun `epley adds one thirtieth of the weight per rep`() {
        assertEquals(100.0 * (1 + 5 / 30.0), OneRepMax.epley(100.0, 5)!!, 1e-9)
        assertEquals(100.0 * (1 + 10 / 30.0), OneRepMax.epley(100.0, 10)!!, 1e-9)
    }

    @Test
    fun `reps beyond the trusted cap give no estimate`() {
        assertEquals(60.0 * (1 + 12 / 30.0), OneRepMax.epley(60.0, 12)!!, 1e-9)
        assertNull(OneRepMax.epley(60.0, 13))
        assertNull(OneRepMax.epley(60.0, 30))
    }

    @Test
    fun `missing or non-positive input gives no estimate`() {
        assertNull(OneRepMax.epley(null, 5))
        assertNull(OneRepMax.epley(100.0, null))
        assertNull(OneRepMax.epley(0.0, 5))
        assertNull(OneRepMax.epley(-10.0, 5))
        assertNull(OneRepMax.epley(100.0, 0))
    }
}
