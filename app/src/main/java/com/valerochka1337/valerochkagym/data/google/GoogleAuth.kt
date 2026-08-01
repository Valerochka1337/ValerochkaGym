package com.valerochka1337.valerochkagym.data.google

import android.app.Activity
import android.app.PendingIntent

/**
 * Результат запроса OAuth-доступа к Google Sheets/Calendar.
 *
 * [Granted] — доступ уже выдан, токен доступен.
 * [NeedsConsent] — требуется явное согласие пользователя; [pendingIntent] нужно
 * запустить через `StartIntentSenderForResult`, после чего повторить [GoogleAuth.authorize].
 * [Failed] — запрос не удался (нет сети, пользователь не залогинен и т. п.).
 */
sealed interface AuthorizeOutcome {
    data object Granted : AuthorizeOutcome
    data class NeedsConsent(val pendingIntent: PendingIntent) : AuthorizeOutcome
    data class Failed(val error: Throwable?) : AuthorizeOutcome
}

/**
 * Вход через Google и выдача OAuth-доступа к нужным API. Интерфейс отделяет UI и будущие
 * загрузчики (Стадии 19/21) от конкретной реализации на Credential Manager /
 * AuthorizationClient — чтобы их можно было мокать в тестах.
 */
interface GoogleAuth {

    /**
     * Вход через Credential Manager. Требует Activity-контекст (системный UI выбора аккаунта).
     * В случае успеха email сохраняется в настройки и возвращается в [Result]. Отмена
     * пользователем — «тихий» [Result.failure] без побочных эффектов.
     */
    suspend fun signIn(activity: Activity): Result<String>

    /**
     * Запрашивает OAuth-доступ (scopes Sheets + Calendar). Может потребовать согласия
     * пользователя — тогда возвращает [AuthorizeOutcome.NeedsConsent] с [PendingIntent].
     */
    suspend fun authorize(activity: Activity): AuthorizeOutcome

    /**
     * Access-токен для Bearer-авторизации без участия Activity. Возвращает `null`, если
     * доступ ещё не выдан (требуется [authorize]) или пользователь не залогинен.
     */
    suspend fun getAccessToken(): String?

    /** Выход: очищает состояние Credential Manager и стирает сохранённый email. */
    suspend fun signOut()
}
