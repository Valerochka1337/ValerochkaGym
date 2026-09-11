package com.valerochka1337.valerochkagym.data.ai

import android.util.Log
import com.valerochka1337.valerochkagym.BuildConfig

/** Explicitly enabled for Live Coach debugging; response bodies never enter release logs. */
internal object CoachResponseLogger {
  fun log(requestId: String, model: String, responseBody: String) {
    if (!BuildConfig.DEBUG) return
    // Logging must not interrupt the coach, including in local JVM tests without Android Log.
    runCatching {
      // Leave room for the prefix and up to four UTF-8 bytes per character in Logcat entries.
      val chunks = responseBody.chunked(800)
      chunks.forEachIndexed { index, chunk ->
        Log.d(
            "LiveCoachAI",
            "request=$requestId model=$model part=${index + 1}/${chunks.size}\n$chunk",
        )
      }
    }
  }
}
