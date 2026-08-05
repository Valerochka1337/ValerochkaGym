package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.ui.analysis.balanceMarkerPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторона перекоса на шкале баланса.
 *
 * Подписи под шкалой говорят «← больше жима» и «больше тяги →», поэтому знак метки — это
 * утверждение о тренировках, а не украшение. Отдельная строка — отсутствующее отношение
 * (тяги не было вовсе): точка должна остаться слева, на стороне жима.
 */
class BalanceMarkerTest {

    private val limit = 1f

    @Test
    fun `equal volume keeps the marker in the centre`() {
        assertEquals(0f, balanceMarkerPosition(1.0, limit), 1e-6f)
    }

    @Test
    fun `more of the left part moves the marker left`() {
        assertTrue(balanceMarkerPosition(1.5, limit) < 0f)
    }

    @Test
    fun `more of the right part moves the marker right`() {
        assertTrue(balanceMarkerPosition(0.6, limit) > 0f)
    }

    @Test
    fun `a missing right part pins the marker to the left`() {
        assertEquals(-limit, balanceMarkerPosition(null, limit), 1e-6f)
    }

    @Test
    fun `a zero numerator pins the marker to the right`() {
        assertEquals(limit, balanceMarkerPosition(0.0, limit), 1e-6f)
    }

    @Test
    fun `the marker never leaves the scale`() {
        assertEquals(-limit, balanceMarkerPosition(100.0, limit), 1e-6f)
        assertEquals(limit, balanceMarkerPosition(0.01, limit), 1e-6f)
    }
}
