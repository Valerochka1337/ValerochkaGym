package com.valerochka1337.valerochkagym.domain

import org.junit.Assert.*
import org.junit.Test

class CoachTextDecoderTest {
  @Test
  fun `every split reveals only text with escapes and unicode`() {
    for (raw in
        listOf(
            """{"text":"Привет \"мир\"\n💪 \uD83D\uDE00","quick_replies":["СЕКРЕТ"]}""",
            """{"quick_replies":["СЕКРЕТ"],"nested":{"text":"СКРЫТО"},"text":"Привет \"мир\"\n💪 \uD83D\uDE00"}""",
        )) {
      val expected = "Привет \"мир\"\n💪 😀"
      for (split in 0..raw.length) {
        val decoder = CoachTextDecoder()
        val first = decoder.append(raw.take(split))
        assertTrue(expected.startsWith(first))
        assertEquals(expected, decoder.append(raw.drop(split)))
      }
      val decoder = CoachTextDecoder()
      raw.forEach { assertTrue(expected.startsWith(decoder.append(it.toString()))) }
      assertEquals(expected, decoder.append(""))
    }
  }

  @Test
  fun `other response formats stay hidden until completion`() {
    for (raw in
        listOf(
            "Plain response",
            "```json\n{\"text\":\"Hi\"}",
            "{\"arguments\":{\"text\":\"hidden\"}}",
            "{\"text\":42}",
            "{\"text\":\"bad\\q\"}",
        )) {
      assertEquals("", CoachTextDecoder().append(raw))
    }
  }

  @Test
  fun `unfinished string reveals a readable prefix without metadata`() {
    assertEquals("Ещё", CoachTextDecoder().append("{\"quick_replies\":[\"secret\"],\"text\":\"Ещё"))
    assertEquals("", CoachTextDecoder().append("{\"text\":\"\\uD83D"))
  }
}
