package com.valerochka1337.valerochkagym.domain

import org.junit.Assert.*
import org.junit.Test

class CoachReplyTest {
  @Test
  fun `embedded fenced reply separates options from prose`() {
    val reply = CoachReply.decode("""Хаммер уже на позиции 4.
      ```json
      {"text":"Хаммер уже на позиции 4. Перенести?", "quick_replies":["Перенеси", "Оставь"]}
      ```
      Дополнительное пояснение.
    """)
    assertEquals("Хаммер уже на позиции 4. Перенести?", reply.text)
    assertEquals(listOf("Перенеси", "Оставь"), reply.quickReplies)
  }

  @Test
  fun `reply options appended to prose are extracted`() {
    val reply = CoachReply.decode("""Хаммер уже на позиции 4.
      {"quick_replies":["Перенеси", "Оставь"]}
    """)
    assertEquals("Хаммер уже на позиции 4.", reply.text)
    assertEquals(listOf("Перенеси", "Оставь"), reply.quickReplies)
  }

  @Test
  fun `embedded object preserves braces and escaped quotes in text`() {
    val expected = CoachReply("Пояснение {жим} и \"Хаммер\"", listOf("Да"))
    val json = """{"text":"Пояснение {жим} и \"Хаммер\"", "quick_replies":["Да"]}"""
    assertEquals(expected, CoachReply.decode("Ответ: $json Готово"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `malformed metadata never leaks into plain text`() {
    CoachReply.decode("""Хаммер уже есть. {"quick_replies":["Перенеси"""")
  }

  @Test
  fun `plain answer remains readable`() {
    assertEquals(CoachReply("Продолжай"), CoachReply.decode("Продолжай"))
  }
}
