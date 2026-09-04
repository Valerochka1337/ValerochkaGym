package com.valerochka1337.valerochkagym.service.heartrate

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateConnectionLifecycleTest {

  @Test
  fun `BLE error closes GATT clears reading and exposes error`() {
    val result = lifecycle()

    result.lifecycle.fail("Не удалось подключиться")

    assertEquals(1, result.closedGatt)
    assertEquals(1, result.clearedReading)
    assertEquals(HeartRateConnectionState.Error("Не удалось подключиться"), result.state)
  }

  @Test
  fun `lost connection closes GATT and exposes the lost device`() {
    val result = lifecycle()
    val device = HeartRateDevice("AA:BB:CC:DD:EE:FF", "Band 10", -45)

    result.lifecycle.lose(device)

    assertEquals(1, result.closedGatt)
    assertEquals(1, result.clearedReading)
    assertEquals(HeartRateConnectionState.Lost(device), result.state)
  }

  @Test
  fun `stopping a session closes GATT and resets the state`() {
    val result = lifecycle()

    result.lifecycle.stop()

    assertEquals(1, result.closedGatt)
    assertEquals(1, result.clearedReading)
    assertEquals(HeartRateConnectionState.Idle, result.state)
  }

  private fun lifecycle(): LifecycleResult {
    val result = LifecycleResult()
    result.lifecycle =
        HeartRateConnectionLifecycle(
            closeGatt = { result.closedGatt += 1 },
            clearReading = { result.clearedReading += 1 },
            emitState = { result.state = it },
        )
    return result
  }

  private class LifecycleResult {
    lateinit var lifecycle: HeartRateConnectionLifecycle
    var closedGatt = 0
    var clearedReading = 0
    var state: HeartRateConnectionState? = null
  }
}
