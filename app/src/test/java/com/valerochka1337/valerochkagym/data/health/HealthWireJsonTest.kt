package com.valerochka1337.valerochkagym.data.health

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class HealthWireJsonTest {
  @Test
  fun `escaped lone surrogates reject while paired unicode survives`() {
    assertNull(HealthWireJson.objectFrom("""{"text":"\uD800"}""".encodeToByteArray()))
    assertNull(HealthWireJson.objectFrom("""{"text":"\uDC00"}""".encodeToByteArray()))
    val parsed =
        requireNotNull(HealthWireJson.objectFrom("""{"text":"\uD83D\uDE00"}""".encodeToByteArray()))
    assertEquals("😀", parsed["text"]!!.jsonPrimitive.content)
  }

  @Test
  fun `duplicate keys at every object depth reject including escaped aliases`() {
    listOf(
            """{"version":1,"version":2}""",
            """{"versions":[{"id":"one","id":"two"}]}""",
            """{"version":1,"\u0076ersion":2}""",
        )
        .forEach { assertNull(HealthWireJson.objectFrom(it.encodeToByteArray())) }
  }

  @Test
  fun `same key in different objects and escaped text remain valid`() {
    val value =
        """{"versions":[{"id":"one"},{"id":"two"}],"text":"Кавычка \" и слэш \\ и {id,id}"}"""
    val parsed = requireNotNull(HealthWireJson.objectFrom(value.encodeToByteArray()))
    assertEquals("Кавычка \" и слэш \\ и {id,id}", parsed["text"]!!.jsonPrimitive.content)
  }

  @Test
  fun `malformed utf8 trailing input and nonobject roots reject`() {
    val malformed =
        "{\"text\":\"".encodeToByteArray() +
            byteArrayOf(0xc3.toByte(), 0x28) +
            "\"}".encodeToByteArray()
    assertNull(HealthWireJson.objectFrom(malformed))
    listOf("{}{}", "[]", "null", "{\"a\":1,}", "{\"a\":NaN}", "{\"a\":true", "{\"a\":01}").forEach {
      assertNull(it, HealthWireJson.objectFrom(it.encodeToByteArray()))
    }
  }

  @Test
  fun `byte bound is enforced without truncation and excess nesting rejects`() {
    val bytes = "{\"text\":\"я\"}".encodeToByteArray()
    assertNotNull(HealthWireJson.objectFrom(bytes, bytes.size))
    assertNull(HealthWireJson.objectFrom(bytes, bytes.size - 1))
    assertNull(
        HealthWireJson.objectFrom(
            ("{\"a\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}").encodeToByteArray()
        )
    )
  }
}
