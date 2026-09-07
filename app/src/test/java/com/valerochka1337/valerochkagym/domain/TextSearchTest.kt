package com.valerochka1337.valerochkagym.domain

import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {
  @Test
  fun `search ignores case and folds Russian spelling variants in both directions`() {
    assertTrue(TextSearch("ПОДЬЕМ").matches(listOf("Подъём ног")))
    assertTrue(TextSearch("подъём").matches(listOf("ПОДЬЕМ ног")))
    assertTrue(TextSearch("ТРЕНАЖЕР").matches(listOf("Тренажёр для жима")))
  }

  @Test
  fun `case folding stays independent of device locale`() {
    val original = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      assertTrue(TextSearch("BIKE").matches(listOf("Air bike")))
    } finally {
      Locale.setDefault(original)
    }
  }

  @Test
  fun `search matches fragments and unordered words across labels`() {
    assertTrue(TextSearch("  ГАНТЕЛ   жим\nгруд ").matches(listOf("Жим гантелей", "Грудь")))
    assertTrue(TextSearch("гриф ez").matches(listOf("EZ-гриф")))
    assertFalse(TextSearch("жим гантел ноги").matches(listOf("Жим гантелей", "Грудь")))
  }

  @Test
  fun `search tolerates substitution insertion deletion and adjacent transposition`() {
    listOf("гантали", "ганттели", "гантли", "ганетли").forEach { typo ->
      assertTrue(typo, TextSearch(typo).matches(listOf("Гантели")))
    }
    assertTrue(TextSearch("подтягиваняи").matches(listOf("Подтягивания")))
    assertTrue(TextSearch("подтягеваня").matches(listOf("Подтягивания")))
  }

  @Test
  fun `short words numbers and excessive typos do not produce fuzzy matches`() {
    assertFalse(TextSearch("жим").matches(listOf("Бег", "Жир", "Жи")))
    assertFalse(TextSearch("бег").matches(listOf("Бёрпи")))
    assertFalse(TextSearch("гонтали").matches(listOf("Гантели")))
    assertFalse(TextSearch("1235").matches(listOf("1234")))
    assertFalse(TextSearch("несуществующее").matches(listOf("Жим гантелей")))
  }

  @Test
  fun `empty and punctuation only queries preserve the full catalog`() {
    assertTrue(TextSearch(" \n ").matches(emptyList()))
    assertTrue(TextSearch(" / — ").matches(listOf("Гантели")))
  }
}
