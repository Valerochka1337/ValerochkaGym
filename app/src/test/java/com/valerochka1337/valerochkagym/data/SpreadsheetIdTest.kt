package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.google.spreadsheetIdFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Тесты парсера [spreadsheetIdFrom]: полная ссылка, голый ID и граничные/мусорные случаи. */
class SpreadsheetIdTest {

  // 44-символьный ID — типичная длина реального Google Sheets ID.
  private val validId = "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"

  @Test
  fun `full url with edit query and gid fragment yields the id`() {
    val url = "https://docs.google.com/spreadsheets/d/$validId/edit?usp=sharing#gid=0"
    assertEquals(validId, spreadsheetIdFrom(url))
  }

  @Test
  fun `url without d segment is not recognized`() {
    assertNull(spreadsheetIdFrom("https://docs.google.com/spreadsheets/u/0/"))
  }

  @Test
  fun `bare long id is returned as is`() {
    assertEquals(validId, spreadsheetIdFrom(validId))
  }

  @Test
  fun `surrounding whitespace is trimmed`() {
    assertEquals(validId, spreadsheetIdFrom("  $validId  "))
  }

  @Test
  fun `bare id of exactly twenty chars is rejected as too short`() {
    val twentyChars = "a".repeat(20)
    assertNull(spreadsheetIdFrom(twentyChars))
  }

  @Test
  fun `garbage input is not recognized`() {
    assertNull(spreadsheetIdFrom("не ссылка и не id"))
  }

  @Test
  fun `empty input is not recognized`() {
    assertNull(spreadsheetIdFrom(""))
  }
}
