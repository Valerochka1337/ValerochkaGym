package com.valerochka1337.valerochkagym.service.heartrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateMeasurementTest {

  @Test
  fun `eight bit HRS measurement returns bpm`() {
    assertEquals(128, parseHeartRateMeasurement(byteArrayOf(0x00, 0x80.toByte())))
  }

  @Test
  fun `sixteen bit HRS measurement is read as little endian`() {
    assertEquals(300, parseHeartRateMeasurement(byteArrayOf(0x01, 0x2c, 0x01)))
  }

  @Test
  fun `malformed and zero HRS measurements are ignored`() {
    assertNull(parseHeartRateMeasurement(byteArrayOf()))
    assertNull(parseHeartRateMeasurement(byteArrayOf(0x01, 0x2c)))
    assertNull(parseHeartRateMeasurement(byteArrayOf(0x00, 0x00)))
  }

  @Test
  fun `a reading older than the live timeout is not fresh`() {
    val now = 20_000L
    val stale =
        HeartRateReading(bpm = 120, updatedAtMillis = now - HEART_RATE_STALE_AFTER_MILLIS - 1)

    assertNull(stale.freshAt(now))
    assertEquals(120, HeartRateReading(120, now - HEART_RATE_STALE_AFTER_MILLIS).freshAt(now)?.bpm)
  }
}
