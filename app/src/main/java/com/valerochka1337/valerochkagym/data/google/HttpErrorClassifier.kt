package com.valerochka1337.valerochkagym.data.google

/**
 * Классификация HTTP-ошибок Google API — одна на выгрузку и импорт, чтобы пользователь видел
 * одинаковые формулировки в обоих направлениях.
 *
 * Постоянные ошибки (нужно вмешательство пользователя): 401/403 — нет доступа, 404 — таблицы нет,
 * прочие 4xx — ошибка запроса. Временные (можно повторить): 429, 5xx и всё остальное.
 */
object HttpErrorClassifier {

  /** true — ошибка постоянная: ретраи бессмысленны, пока пользователь не вмешается. */
  fun isPermanent(code: Int): Boolean = code in 400..499 && code != TOO_MANY_REQUESTS

  fun message(code: Int): String =
      when (code) {
        401,
        403 -> "Нет доступа к таблице — проверьте вход и права"
        404 -> "Таблица не найдена — проверьте ссылку"
        TOO_MANY_REQUESTS -> "Слишком много запросов (HTTP 429)"
        in 500..599 -> "Ошибка сервера (HTTP $code)"
        in 400..499 -> "Ошибка запроса (HTTP $code)"
        else -> "Неожиданный ответ (HTTP $code)"
      }

  private const val TOO_MANY_REQUESTS = 429
}
