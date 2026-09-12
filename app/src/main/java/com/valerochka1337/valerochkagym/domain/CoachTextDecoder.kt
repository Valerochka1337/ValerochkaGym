package com.valerochka1337.valerochkagym.domain

import kotlinx.serialization.json.Json

/**
 * Incremental preview of a top-level JSON text string. Never decodes incomplete JSON as a reply.
 */
class CoachTextDecoder {
  private enum class State {
    ROOT,
    KEY,
    COLON,
    VALUE,
    STRING,
    SKIP,
    AFTER,
    DONE,
    INVALID,
  }

  private var state = State.ROOT
  private val token = StringBuilder()
  private val text = StringBuilder()
  private var key = ""
  private val keys = mutableSetOf<String>()
  private var readingKey = false
  private var escaped = false
  private var unicode: StringBuilder? = null
  private var high: Char? = null
  private var depth = 0
  private var quoted = false
  private var count = 0

  fun append(delta: String): String {
    count += delta.length
    if (count > 256 * 1024) state = State.INVALID
    for (char in delta) {
      if (state == State.INVALID) break
      when (state) {
        State.ROOT ->
            if (!char.isWhitespace()) {
              state = if (char == '{') State.KEY else State.INVALID
            }
        State.KEY ->
            if (!char.isWhitespace()) {
              if (char == '"') {
                readingKey = true
                token.setLength(0)
                state = State.STRING
              } else state = State.INVALID
            }
        State.COLON ->
            if (!char.isWhitespace()) {
              state = if (char == ':') State.VALUE else State.INVALID
            }
        State.VALUE ->
            if (!char.isWhitespace()) {
              token.setLength(0)
              if (key == "text") {
                if (char == '"') {
                  readingKey = false
                  state = State.STRING
                } else state = State.INVALID
              } else {
                state = State.SKIP
                depth = 0
                quoted = false
                escaped = false
                skip(char)
              }
            }
        State.STRING -> string(char)
        State.SKIP -> skip(char)
        State.AFTER -> after(char)
        State.DONE -> if (!char.isWhitespace()) state = State.INVALID
        State.INVALID -> Unit
      }
    }
    return if (state == State.INVALID) "" else text.toString().trimStart()
  }

  private fun after(char: Char) {
    if (char.isWhitespace()) return
    state =
        when (char) {
          ',' -> State.KEY
          '}' -> State.DONE
          else -> State.INVALID
        }
  }

  private fun skip(char: Char) {
    if (!quoted && depth == 0 && (char == ',' || char == '}')) {
      if (runCatching { Json.parseToJsonElement(token.toString()) }.isFailure) state = State.INVALID
      else {
        state = State.AFTER
        after(char)
      }
      return
    }
    token.append(char)
    if (quoted) {
      if (escaped) escaped = false
      else if (char == '\\') escaped = true else if (char == '"') quoted = false
    } else
        when (char) {
          '"' -> quoted = true
          '{',
          '[' -> depth++
          '}',
          ']' -> depth--
        }
    if (depth < 0) state = State.INVALID
  }

  private fun string(char: Char) {
    unicode?.let { digits ->
      if (char.digitToIntOrNull(16) == null) {
        state = State.INVALID
        return
      }
      digits.append(char)
      if (digits.length == 4) {
        unicode = null
        decoded(digits.toString().toInt(16).toChar())
      }
      return
    }
    if (escaped) {
      escaped = false
      when (char) {
        'u' -> unicode = StringBuilder()
        '"',
        '\\',
        '/' -> decoded(char)
        'b' -> decoded('\b')
        'f' -> decoded('\u000c')
        'n' -> decoded('\n')
        'r' -> decoded('\r')
        't' -> decoded('\t')
        else -> state = State.INVALID
      }
    } else
        when {
          char == '\\' -> escaped = true
          char == '"' -> {
            if (high != null) {
              state = State.INVALID
              return
            }
            if (readingKey) {
              key = token.toString()
              state = if (keys.add(key)) State.COLON else State.INVALID
            } else state = State.AFTER
          }
          char < ' ' -> state = State.INVALID
          else -> decoded(char)
        }
  }

  private fun decoded(char: Char) {
    val out = if (readingKey) token else text
    val pending = high
    if (pending != null) {
      high = null
      if (!char.isLowSurrogate()) {
        state = State.INVALID
        return
      }
      out.append(pending).append(char)
    } else if (char.isHighSurrogate()) high = char
    else if (char.isLowSurrogate()) state = State.INVALID else out.append(char)
    if (!readingKey && text.length > 16_000) state = State.INVALID
  }
}
