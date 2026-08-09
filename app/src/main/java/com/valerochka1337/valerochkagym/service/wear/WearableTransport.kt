package com.valerochka1337.valerochkagym.service.wear

/**
 * Двунаправленный байтовый канал до приложения на браслете.
 *
 * Реализация изолирует Xiaomi Wear SDK, поэтому протокол тренировки можно тестировать без
 * Android/Binder и без подключённого браслета.
 */
interface WearableTransport {

    /** Начинает поиск сопряжённого устройства и вызывает [onConnected], когда канал готов. */
    fun start(
        onConnected: () -> Unit,
        onMessage: (ByteArray) -> Unit,
    )

    /** Останавливает поиск и снимает listener сообщений. */
    fun stop()

    /** Отправляет один пакет приложению на браслете, если канал готов. */
    fun send(message: ByteArray)

}
