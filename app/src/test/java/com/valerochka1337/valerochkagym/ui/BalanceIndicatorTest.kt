package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.domain.analysis.BalanceId
import com.valerochka1337.valerochkagym.domain.analysis.BalanceRatio
import com.valerochka1337.valerochkagym.ui.analysis.BalanceIndicator
import com.valerochka1337.valerochkagym.ui.analysis.balanceIndicator
import org.junit.Assert.assertEquals
import org.junit.Test

/** Семантика цветной точки в карточке баланса. */
class BalanceIndicatorTest {

    @Test
    fun `a ratio in the target corridor stays balanced regardless of total sets`() {
        assertEquals(BalanceIndicator.BALANCED, balanceIndicator(balance(left = 100.0, right = 115.0)))
    }

    @Test
    fun `an out of target ratio with a small nonzero gap is a warning`() {
        assertEquals(BalanceIndicator.IMBALANCED, balanceIndicator(balance(left = 3.0, right = 6.0)))
    }

    @Test
    fun `an out of target ratio with a gap over ten sets is severe`() {
        assertEquals(BalanceIndicator.SEVERE, balanceIndicator(balance(left = 10.0, right = 25.0)))
    }

    @Test
    fun `a gap of exactly ten sets remains a warning`() {
        assertEquals(BalanceIndicator.IMBALANCED, balanceIndicator(balance(left = 20.0, right = 30.0)))
    }

    @Test
    fun `a zero numerator is severe even with one set on the opposite side`() {
        assertEquals(BalanceIndicator.SEVERE, balanceIndicator(balance(left = 0.0, right = 1.0)))
    }

    @Test
    fun `a zero denominator is severe even with one set on the numerator side`() {
        assertEquals(BalanceIndicator.SEVERE, balanceIndicator(balance(left = 1.0, right = 0.0)))
    }

    private fun balance(left: Double, right: Double): BalanceRatio = BalanceRatio(
        id = BalanceId.PUSH_PULL,
        leftSets = left,
        rightSets = right,
        ratio = right.takeIf { it > 0.0 }?.let { left / it },
        targetLow = 0.8,
        targetHigh = 1.25,
    )
}
