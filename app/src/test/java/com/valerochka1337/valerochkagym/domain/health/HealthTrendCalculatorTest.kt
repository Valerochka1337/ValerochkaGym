package com.valerochka1337.valerochkagym.domain.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthTrendCalculatorTest {
    @Test
    fun `numeric observations split series when unit material method or source differs`() {
        val base = HealthObservationDraft("Глюкоза", HealthRawValue.Number(5.0), "mmol/L", method = "enzymatic", material = "serum", source = "Lab A", canonicalKey = "glucose", observedAt = 1)
        val same = base.copy(value = HealthRawValue.Number(5.5), observedAt = 2)
        val otherMethod = base.copy(value = HealthRawValue.Number(5.2), method = "strip", observedAt = 3)

        val series = HealthTrendCalculator.series(listOf(base, same, otherMethod))

        assertEquals(2, series.size)
        assertEquals(listOf(5.0, 5.5), series.single { it.method == "enzymatic" }.points.map { it.value })
    }

    @Test
    fun `ranges categories and text never become numeric trend points and zero base has no relative change`() {
        val common = HealthObservationDraft("Unknown", HealthRawValue.Range(1.0, 2.0, "1-2"), canonicalKey = "unknown", observedAt = 1)
        val category = common.copy(value = HealthRawValue.Category("positive"), observedAt = 2)
        val text = common.copy(value = HealthRawValue.Text("see note"), observedAt = 3)

        assertEquals(emptyList<HealthTrendSeries>(), HealthTrendCalculator.series(listOf(common, category, text)))
        assertNull(HealthTrendCalculator.relativeChangePercent(HealthTrendPoint(1, 0.0, null), HealthTrendPoint(2, 1.0, null)))
    }
}
