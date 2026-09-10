package com.valerochka1337.valerochkagym.data.health

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Rejects ambiguous objects before the normal decoder can replace a duplicate key. */
internal object HealthWireJson {
  private val json = Json {
    isLenient = false
    allowTrailingComma = false
  }
  private val number = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

  fun objectFrom(bytes: ByteArray, maxBytes: Int = 5_242_880): JsonObject? {
    if (bytes.isEmpty() || bytes.size > maxBytes) return null
    return try {
      val text =
          Charsets.UTF_8.newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString()
      ShapeReader(text).readDocument()
      json.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
      null
    }
  }

  /** Syntax/number validity remains the JSON decoder's job; this checks depth and object keys. */
  private class ShapeReader(private val text: String) {
    private var index = 0

    fun readDocument() {
      readValue(0)
      whitespace()
      require(index == text.length)
    }

    private fun whitespace() {
      while (index < text.length && text[index] in " \t\r\n") index++
    }

    private fun readValue(depth: Int) {
      require(depth < 64)
      whitespace()
      require(index < text.length)
      when (text[index]) {
        '{' -> {
          index++
          val keys = HashSet<String>()
          whitespace()
          if (consume('}')) return
          do {
            whitespace()
            val key = readString()
            require(keys.add(key))
            whitespace()
            require(consume(':'))
            readValue(depth + 1)
            whitespace()
            if (consume('}')) return
            require(consume(','))
          } while (true)
        }
        '[' -> {
          index++
          whitespace()
          if (consume(']')) return
          do {
            readValue(depth + 1)
            whitespace()
            if (consume(']')) return
            require(consume(','))
          } while (true)
        }
        '"' -> readString()
        else -> {
          val start = index
          while (index < text.length && text[index] !in ",]} \t\r\n") index++
          require(index > start)
          val token = text.substring(start, index)
          require(token == "true" || token == "false" || token == "null" || number.matches(token))
        }
      }
    }

    private fun readString(): String {
      require(index < text.length && text[index] == '"')
      val start = index++
      while (index < text.length) {
        val char = text[index++]
        require(char.code >= 0x20)
        when (char) {
          '"' -> {
            val decoded = json.decodeFromString<String>(text.substring(start, index))
            var offset = 0
            while (offset < decoded.length) {
              val value = decoded[offset++]
              if (value.isHighSurrogate()) {
                require(offset < decoded.length && decoded[offset++].isLowSurrogate())
              } else require(!value.isLowSurrogate())
            }
            return decoded
          }
          '\\' -> {
            require(index < text.length)
            index++
          }
        }
      }
      error("Unterminated JSON string")
    }

    private fun consume(char: Char): Boolean {
      if (index >= text.length || text[index] != char) return false
      index++
      return true
    }
  }
}
