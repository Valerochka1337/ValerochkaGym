package com.valerochka1337.valerochkagym.service.heartrate

/**
 * Маленький владелец финальных переходов BLE-сессии. Он отделён от Android callback'ов, чтобы
 * одинаково закрывать GATT и очищать live HR при ошибке, потере связи и конце тренировки.
 */
internal class HeartRateConnectionLifecycle(
    private val closeGatt: () -> Unit,
    private val clearReading: () -> Unit,
    private val emitState: (HeartRateConnectionState) -> Unit,
) {

  fun fail(message: String) {
    closeGatt()
    clearReading()
    emitState(HeartRateConnectionState.Error(message))
  }

  fun lose(device: HeartRateDevice?) {
    closeGatt()
    clearReading()
    emitState(
        device?.let(HeartRateConnectionState::Lost)
            ?: HeartRateConnectionState.Error("Связь с пульсометром потеряна"),
    )
  }

  fun stop() {
    closeGatt()
    clearReading()
    emitState(HeartRateConnectionState.Idle)
  }
}
