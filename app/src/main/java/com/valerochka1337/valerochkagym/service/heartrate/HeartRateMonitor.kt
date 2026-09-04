package com.valerochka1337.valerochkagym.service.heartrate

import kotlinx.coroutines.flow.StateFlow

/**
 * Live-пульс для активной тренировки. Значение живёт только в памяти: история пульса и Room для
 * этого сценария намеренно не используются.
 */
interface HeartRateMonitor {

  val state: StateFlow<HeartRateConnectionState>

  /** Null, когда измерения ещё нет или последнее уведомление от датчика уже устарело. */
  val reading: StateFlow<HeartRateReading?>

  /** Начинает короткий поиск BLE-датчиков со стандартным Heart Rate Service. */
  fun scan()

  /**
   * Подключает выбранный пользователем источник после результата
   * [HeartRateConnectionState.Selection].
   */
  fun connect(device: HeartRateDevice)

  /** Снимает scan/GATT и забывает временное значение при завершении тренировки. */
  fun stop()

  /** Переводит плитку в ошибку, если Android не разрешил удерживать BLE-сессию в FGS. */
  fun reportError(message: String)
}

data class HeartRateReading(
    val bpm: Int,
    val updatedAtMillis: Long,
)

data class HeartRateDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
) {
  val label: String
    get() = name?.takeIf { it.isNotBlank() } ?: "Пульсометр • ${address.takeLast(5)}"
}

/** Небольшая, честная модель статусов для плитки пульса активной тренировки. */
sealed interface HeartRateConnectionState {
  data object Idle : HeartRateConnectionState

  data object PermissionRequired : HeartRateConnectionState

  data object Searching : HeartRateConnectionState

  data class Selection(val devices: List<HeartRateDevice>) : HeartRateConnectionState

  data class Connecting(val device: HeartRateDevice) : HeartRateConnectionState

  data class Connected(val device: HeartRateDevice) : HeartRateConnectionState

  data class Live(val device: HeartRateDevice, val reading: HeartRateReading) :
      HeartRateConnectionState

  data class Lost(val device: HeartRateDevice) : HeartRateConnectionState

  data class Error(val message: String) : HeartRateConnectionState
}

/** Bluetooth Heart Rate Measurement (0x2A37): flag bit 0 switches the value to uint16 LE. */
internal fun parseHeartRateMeasurement(value: ByteArray): Int? {
  if (value.size < HEART_RATE_8_BIT_PACKET_SIZE) return null

  val flags = value[0].toInt() and BYTE_MASK
  val isSixteenBit = flags and HEART_RATE_16_BIT_FLAG != 0
  val bpm =
      if (isSixteenBit) {
        if (value.size < HEART_RATE_16_BIT_PACKET_SIZE) return null
        (value[1].toInt() and BYTE_MASK) or ((value[2].toInt() and BYTE_MASK) shl 8)
      } else {
        value[1].toInt() and BYTE_MASK
      }
  return bpm.takeIf { it > 0 }
}

internal fun HeartRateReading?.freshAt(
    nowMillis: Long,
    maxAgeMillis: Long = HEART_RATE_STALE_AFTER_MILLIS,
): HeartRateReading? = this?.takeIf { nowMillis - it.updatedAtMillis in 0..maxAgeMillis }

internal const val HEART_RATE_STALE_AFTER_MILLIS = 10_000L

private const val BYTE_MASK = 0xFF
private const val HEART_RATE_16_BIT_FLAG = 0x01
private const val HEART_RATE_8_BIT_PACKET_SIZE = 2
private const val HEART_RATE_16_BIT_PACKET_SIZE = 3
