package com.valerochka1337.valerochkagym.domain.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** МЕТ кардио-подхода: уравнения ACSM для дорожки и табличные значения тренажёров. */
class CardioMetTest {

  /** VO2 ходьбы по ACSM: 0.1·S + 1.8·S·G + 3.5, S — м/мин, G — доля уклона. */
  private fun walkingMet(speedKmh: Double, inclinePct: Double = 0.0): Double {
    val metersPerMinute = speedKmh * 1000.0 / 60.0
    return (0.1 * metersPerMinute + 1.8 * metersPerMinute * inclinePct / 100.0 + 3.5) / 3.5
  }

  /** VO2 бега по ACSM: 0.2·S + 0.9·S·G + 3.5. */
  private fun runningMet(speedKmh: Double, inclinePct: Double = 0.0): Double {
    val metersPerMinute = speedKmh * 1000.0 / 60.0
    return (0.2 * metersPerMinute + 0.9 * metersPerMinute * inclinePct / 100.0 + 3.5) / 3.5
  }

  @Test
  fun `who target band is five hundred to a thousand met minutes`() {
    assertEquals(500.0, CardioMet.WHO_RANGE.start, 1e-9)
    assertEquals(1000.0, CardioMet.WHO_RANGE.endInclusive, 1e-9)
  }

  @Test
  fun `treadmill walking uses the ACSM walking equation`() {
    assertEquals(walkingMet(5.0), CardioMet.forSet("Беговая дорожка", 5.0, null), 1e-9)
  }

  @Test
  fun `treadmill running uses the ACSM running equation from the transition speed`() {
    // 6.4 км/ч — граница: уже беговое уравнение (условие «строго меньше 6.4» — ходьба).
    assertEquals(runningMet(6.4), CardioMet.forSet("дорожка", 6.4, null), 1e-9)
    assertEquals(runningMet(10.0), CardioMet.forSet("Дорожка", 10.0, null), 1e-9)
  }

  @Test
  fun `incline raises treadmill met and negative incline counts as flat`() {
    assertEquals(walkingMet(5.0, 5.0), CardioMet.forSet("дорожка", 5.0, 5.0), 1e-9)
    assertTrue(CardioMet.forSet("дорожка", 5.0, 5.0) > CardioMet.forSet("дорожка", 5.0, 0.0))
    assertEquals(
        CardioMet.forSet("дорожка", 5.0, 0.0),
        CardioMet.forSet("дорожка", 5.0, -3.0),
        1e-9,
    )
  }

  @Test
  fun `treadmill without speed falls back to the default met`() {
    assertEquals(6.0, CardioMet.forSet("дорожка", null, null), 1e-9)
    assertEquals(6.0, CardioMet.forSet("дорожка", 0.0, 2.0), 1e-9)
  }

  @Test
  fun `known machines use compendium values`() {
    assertEquals(7.0, CardioMet.forSet("Велотренажёр", null, null), 1e-9)
    assertEquals(7.0, CardioMet.forSet("велосипед", 20.0, null), 1e-9)
    assertEquals(5.0, CardioMet.forSet("Эллиптический тренажёр", null, null), 1e-9)
    assertEquals(7.0, CardioMet.forSet("Гребной тренажёр", null, null), 1e-9)
    assertEquals(8.0, CardioMet.forSet("Степпер", null, null), 1e-9)
  }

  @Test
  fun `unknown cardio falls back to the default met`() {
    assertEquals(6.0, CardioMet.forSet("Скакалка", null, null), 1e-9)
  }
}
