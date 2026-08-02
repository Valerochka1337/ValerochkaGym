package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.domain.analysis.OneRepMax
import com.valerochka1337.valerochkagym.ui.analysis.charts.NiceScale
import com.valerochka1337.valerochkagym.ui.analysis.formatDecimal
import com.valerochka1337.valerochkagym.ui.analysis.formatKg
import org.junit.Assert.assertEquals
import org.junit.Test

class ScratchCalloutFormatTest {

    @Test
    fun headlineAndChartCalloutDisagreeForSameE1rm() {
        val e1rm = OneRepMax.epley(100.0, 5)!!
        assertEquals(116.666, e1rm, 0.001)

        // Заголовок и таблица-двойник
        val table = formatKg(e1rm)
        // Формат выноски графика из ProgressCards.kt:133
        val callout = "${e1rm.toFloat().toInt()} кг"

        println("table=$table callout=$callout")
        assertEquals("117 кг", table)
        assertEquals("116 кг", callout)
    }

    @Test
    fun repMaxAxisTicksAreMislabelled() {
        // Силовая кривая: лучший вес 10 кг (гантельная изоляция)
        val scale = NiceScale.forRange(0f, 10f)
        val ticks = scale.ticks
        println("ticks=$ticks")
        assertEquals(listOf(0f, 2.5f, 5f, 7.5f, 10f), ticks)
        // ProgressCards.kt:159
        assertEquals(listOf("0", "2", "5", "7", "10"), ticks.map { "${it.toInt()}" })
        // LoadCards.kt:82 (подходы)
        assertEquals(
            listOf("0", "3", "5", "8", "10"),
            ticks.map { formatDecimal(it.toDouble(), 0) },
        )
    }
}
