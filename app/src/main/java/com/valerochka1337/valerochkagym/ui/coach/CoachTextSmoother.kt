package com.valerochka1337.valerochkagym.ui.coach

import android.icu.text.BreakIterator
import com.valerochka1337.valerochkagym.ui.theme.GymMotion
import java.util.Locale
import kotlin.math.ceil

/** Display-only buffer. Each received suffix has its own bounded deadline. */
internal class CoachTextSmoother(initial: String = "") {
  private data class Portion(val from: Int, val to: Int, val at: Long)

  private val portions = ArrayDeque<Portion>()
  private var target = initial
  var visible: String = initial
    private set

  fun update(text: String, now: Long, immediate: Boolean = false): String {
    if (immediate || !text.startsWith(target)) {
      portions.clear()
      target = text
      visible = text
      return visible
    }
    if (text.length > target.length) portions.addLast(Portion(target.length, text.length, now))
    target = text
    var end = visible.length
    for (part in portions) {
      val progress = ((now - part.at).toDouble() / GymMotion.CoachCatchUpMillis).coerceIn(0.0, 1.0)
      if (progress > 0) end = maxOf(end, part.from + ceil((part.to - part.from) * progress).toInt())
    }
    if (end > visible.length) {
      val chars = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(target) }
      end = if (chars.isBoundary(end)) end else chars.following(end)
      // Prefer a nearby word boundary without delaying the bounded catch-up.
      val wordEnd = target.indexOfFirstFrom(end) { it.isWhitespace() }
      if (wordEnd in end..minOf(end + GymMotion.CoachWordLookAhead, target.length)) end = wordEnd
      visible = target.substring(0, end)
    }
    while (
        portions.firstOrNull()?.let { now - it.at >= GymMotion.CoachCatchUpMillis } == true
    ) portions.removeFirst()
    return visible
  }
}

private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
  for (index in start until length) if (predicate(this[index])) return index
  return -1
}
