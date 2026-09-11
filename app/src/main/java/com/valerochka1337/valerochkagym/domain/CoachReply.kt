package com.valerochka1337.valerochkagym.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Model-authored reply options are conversation text, never executable approval controls. */
data class CoachReply(val text: String, val quickReplies: List<String> = emptyList()) {
  companion object {
    fun decode(raw: String): CoachReply {
      val trimmed = raw.trim()
      val candidate = trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
      structuredReply(candidate)?.let { return it }

      // Recover complete objects embedded in prose or Markdown without displaying wire metadata.
      jsonObjects(candidate).toList().asReversed().forEach { (start, objectText) ->
        structuredReply(objectText)?.let { return it }
        val value = Json.parseToJsonElement(objectText) as? JsonObject
        if (value?.keys == setOf("quick_replies")) {
          val prose = candidate.substring(0, start).trim().removeSuffix("```json")
              .removeSuffix("```").trim()
          if (prose.isNotEmpty() && !prose.contains("quick_replies"))
            return CoachReply(prose, decodeQuickReplies(value["quick_replies"]?.toString()))
        }
      }

      require(!candidate.startsWith("{") && !candidate.contains("\"quick_replies\"") &&
          !candidate.contains("\"text\"")) { "Malformed coach reply" }
      return CoachReply(trimmed)
    }

    private fun structuredReply(candidate: String): CoachReply? {
      val value = runCatching { Json.parseToJsonElement(candidate) }.getOrNull() as? JsonObject
      if (value == null) return null
      val text = (value["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
      if (text.isNullOrEmpty()) return null
      return CoachReply(text, decodeQuickReplies(value["quick_replies"]?.toString()))
    }

    /** Braces inside escaped JSON strings do not delimit objects. */
    private fun jsonObjects(raw: String): Sequence<Pair<Int, String>> = sequence {
      var start = -1
      var depth = 0
      var quoted = false
      var escaped = false
      raw.forEachIndexed { index, char ->
        if (depth == 0) {
          if (char == '{') { start = index; depth = 1 }
        } else if (quoted) {
          if (escaped) escaped = false
          else if (char == '\\') escaped = true
          else if (char == '"') quoted = false
        } else {
          when (char) {
            '"' -> quoted = true
            '{' -> depth++
            '}' -> {
              depth--
              if (depth == 0) {
                val candidate = raw.substring(start, index + 1)
                if (runCatching { Json.parseToJsonElement(candidate) }.isSuccess)
                  yield(start to candidate)
              }
            }
          }
        }
      }
    }

    fun decodeQuickReplies(raw: String?): List<String> {
      val array = raw?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonArray
      return array
          .orEmpty()
          .mapNotNull {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.trim()
          }
          .filter { it.isNotEmpty() && it.length <= 120 && '\n' !in it && '\r' !in it }
          .distinctBy { it.lowercase() }
          .take(4)
    }

    fun encodeQuickReplies(replies: List<String>): String =
        JsonArray(replies.map { JsonPrimitive(it) }).toString()
  }
}
