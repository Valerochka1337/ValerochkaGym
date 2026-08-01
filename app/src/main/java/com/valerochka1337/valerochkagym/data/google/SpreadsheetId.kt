package com.valerochka1337.valerochkagym.data.google

/** ID таблицы внутри ссылки вида `https://docs.google.com/spreadsheets/d/<ID>/edit`. */
private val URL_ID_REGEX = Regex("/spreadsheets/d/([a-zA-Z0-9\\-_]+)")

/** Голый ID: только буквы, цифры, дефис и подчёркивание. */
private val BARE_ID_REGEX = Regex("^[a-zA-Z0-9\\-_]+$")

/** Голые ID Google Sheets длиннее этого порога; более короткая строка — не ID. */
private const val MIN_BARE_ID_LENGTH = 20

/**
 * Извлекает ID таблицы Google Sheets из пользовательского ввода.
 *
 * Принимает как полную ссылку `https://docs.google.com/spreadsheets/d/<ID>/...`
 * (возвращает `<ID>`), так и голый ID (строку из букв/цифр/`-`/`_` длиной
 * более [MIN_BARE_ID_LENGTH] символов — возвращается как есть). Во всех остальных
 * случаях возвращает `null` — ввод не похож на ссылку или ID.
 */
fun spreadsheetIdFrom(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    URL_ID_REGEX.find(trimmed)?.let { return it.groupValues[1] }
    if (trimmed.length > MIN_BARE_ID_LENGTH && BARE_ID_REGEX.matches(trimmed)) return trimmed
    return null
}
