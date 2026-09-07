package com.valerochka1337.valerochkagym.domain

import java.util.Locale
import kotlin.math.abs

/** Shared local search: unordered word fragments, Russian spelling folds and bounded typos. */
class TextSearch(query: String) {
  private val terms = words(query).distinct()

  fun matches(labels: Iterable<String>): Boolean {
    if (terms.isEmpty()) return true
    val candidates = labels.flatMap(::words).distinct()
    return terms.all { term ->
      candidates.any { it.contains(term) } ||
          candidates.any { candidate ->
            // Short words (жим, бег) and numbers must not turn into unrelated matches.
            val limit =
                when {
                  term.any(Char::isDigit) || candidate.any(Char::isDigit) -> 0
                  minOf(term.length, candidate.length) < 4 -> 0
                  minOf(term.length, candidate.length) < 8 -> 1
                  else -> 2
                }
            limit > 0 &&
                abs(term.length - candidate.length) <= limit &&
                withinEditDistance(term, candidate, limit)
          }
    }
  }

  companion object {
    fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace('ё', 'е').replace('ъ', 'ь')

    private val separators = Regex("[^\\p{L}\\p{N}]+")

    private fun words(text: String): List<String> =
        normalize(text).split(separators).filter(String::isNotEmpty)

    /**
     * Optimal string alignment distance, including adjacent transpositions; O(word length) space.
     */
    private fun withinEditDistance(left: String, right: String, limit: Int): Boolean {
      var previousPrevious = IntArray(right.length + 1)
      var previous = IntArray(right.length + 1) { it }
      var current = IntArray(right.length + 1)
      for (i in 1..left.length) {
        current[0] = i
        var rowMinimum = i
        for (j in 1..right.length) {
          current[j] =
              minOf(
                  previous[j] + 1,
                  current[j - 1] + 1,
                  previous[j - 1] + if (left[i - 1] == right[j - 1]) 0 else 1,
              )
          if (i > 1 && j > 1 && left[i - 1] == right[j - 2] && left[i - 2] == right[j - 1]) {
            current[j] = minOf(current[j], previousPrevious[j - 2] + 1)
          }
          rowMinimum = minOf(rowMinimum, current[j])
        }
        if (rowMinimum > limit) return false
        val reusable = previousPrevious
        previousPrevious = previous
        previous = current
        current = reusable
      }
      return previous[right.length] <= limit
    }
  }
}
