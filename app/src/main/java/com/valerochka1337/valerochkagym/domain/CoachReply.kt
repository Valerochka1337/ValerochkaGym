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
      val value = runCatching { Json.parseToJsonElement(candidate) }.getOrNull() as? JsonObject
      if (value == null) {
        require(!candidate.startsWith("{")) { "Malformed coach reply" }
        return CoachReply(trimmed)
      }
      val text = (value["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
      require(!text.isNullOrEmpty()) { "Missing coach reply text" }
      return CoachReply(text, decodeQuickReplies(value["quick_replies"]?.toString()))
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
