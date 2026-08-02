package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.ui.analysis.balanceMarkerPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторона перекоса на шкале баланса.
 *
 * Подписи под полосой говорят «← больше тяги» и «больше жима →», поэтому знак метки — это
 * утверждение о тренировках, а не украшение. Отдельная строка — отсутствующее отношение
 * (тяги не было вовсе): раньше оно уводило метку влево, то есть карточка сообщала «перекос
 * в тягу» ровно там, где тяги не делали ни разу.
 */
class BalanceMarkerTest {

    private val limit = 1f

    @Test
    fun `equal volume keeps the marker in the centre`() {
        assertEquals(0f, balanceMarkerPosition(1.0, limit), 1e-6f)
    }

    @Test
    fun `more of the right side moves the marker right`() {
        assertTrue(balanceMarkerPosition(1.5, limit) > 0f)
    }

    @Test
    fun `more of the left side moves the marker left`() {
        assertTrue(balanceMarkerPosition(0.6, limit) < 0f)
    }

    @Test
    fun `a missing opposite side pins the marker to the right`() {
        assertEquals(limit, balanceMarkerPosition(null, limit), 1e-6f)
    }

    @Test
    fun `a zero numerator pins the marker to the left`() {
        assertEquals(-limit, balanceMarkerPosition(0.0, limit), 1e-6f)
    }

    @Test
    fun `the marker never leaves the scale`() {
        assertEquals(limit, balanceMarkerPosition(100.0, limit), 1e-6f)
        assertEquals(-limit, balanceMarkerPosition(0.01, limit), 1e-6f)
    }
}
