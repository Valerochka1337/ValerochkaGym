package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.ui.analysis.charts.NiceScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Шкала осей: круглые подписи на обычных данных и отсутствие ловушек на вырожденных.
 *
 * Отдельно проверяется случай «все значения одинаковые» — одна тренировка в периоде или повторённый
 * вес. Раньше он давал диапазон шириной в тысячную долю: шаг оказывался мельче точности Float,
 * `value += step` не сдвигал значение, и построение меток не заканчивалось никогда — график ронял
 * приложение по памяти.
 */
class NiceScaleTest {

  @Test
  fun `a normal range gets round ticks`() {
    val scale = NiceScale.forRange(0f, 37f)

    assertEquals(listOf(0f, 10f, 20f, 30f, 40f), scale.ticks)
  }

  @Test
  fun `the top tick is never lost to float error`() {
    val scale = NiceScale.forRange(0f, 113.4f)

    assertTrue("верхняя метка обязана быть на оси", scale.ticks.last() >= 113.4f)
    assertEquals(scale.max, scale.ticks.last(), 1e-3f)
  }

  @Test(timeout = 5_000)
  fun `equal values give a finite scale around them`() {
    val scale = NiceScale.forRange(rawMin = 10_000f, rawMax = 10_000f, zeroBased = false)
    val ticks = scale.ticks

    assertTrue("меток должно быть по-человечески мало, а не бесконечность", ticks.size in 2..10)
    assertEquals("подписи обязаны различаться", ticks.size, ticks.distinct().size)
    assertTrue("значение должно попасть внутрь шкалы", scale.min < 10_000f && scale.max > 10_000f)
  }

  @Test
  fun `a flat trend sits in the middle of its scale`() {
    val scale = NiceScale.forRange(rawMin = 58.33f, rawMax = 58.33f, zeroBased = false)

    assertTrue(
        "линия не должна лежать на краю: ${scale.fraction(58.33f)}",
        scale.fraction(58.33f) in 0.2f..0.8f,
    )
  }

  @Test
  fun `empty data still gives a usable scale`() {
    val scale = NiceScale.forRange(0f, 0f)

    assertTrue(scale.max > scale.min)
    assertTrue(scale.ticks.size >= 2)
  }
}
